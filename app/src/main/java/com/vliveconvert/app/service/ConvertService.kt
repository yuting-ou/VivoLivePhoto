package com.vliveconvert.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.vliveconvert.app.MainActivity
import com.vliveconvert.app.R
import com.vliveconvert.app.convert.Converter
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.PhotoTime
import com.vliveconvert.app.core.VivoDual
import com.vliveconvert.app.ui.ConvertItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 转换前台服务：批次实际执行处。
 *
 * 转换不再挂在 Activity 的 lifecycleScope 上——用户切后台、旋转屏幕、分屏
 * 都不影响批次进行；通知栏实时显示进度。队列状态经 [ConvertCenter] 共享，
 * 进程意外被杀后列表可从磁盘恢复（loadQueue）。
 *
 * 删除原图策略：
 * - 已授予「所有文件访问权限」→ 服务内直接删除（IO 线程）
 * - 未授权 → URI 暂存到 ConvertCenter.pendingTrashUris，
 *   由 Activity（onStart 观察到非空时）拉起系统回收站确认弹窗
 */
class ConvertService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildProgressNotification(0, 0, indeterminate = true))
        if (intent?.action == ACTION_START && !running) {
            running = true
            serviceScope.launch(Dispatchers.IO) { runBatch() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------- 批次执行 ----------

    private suspend fun runBatch() {
        val app = applicationContext
        val prefs = app.getSharedPreferences("vliveconvert", MODE_PRIVATE)
        val deleteOriginal = prefs.getBoolean("delete_original", false)
        val moveToCamera = prefs.getBoolean("move_to_camera", true)
        val outputRelPath =
            prefs.getString("output_rel_path", "Pictures/VLiveConvert") ?: "Pictures/VLiveConvert"
        val tempDir = File(app.cacheDir, "output").apply { mkdirs() }

        // 快照纪律：遍历队列必须在主线程取不可变快照（IO 直接迭代 Compose 列表
        // 会与主线程写并发 → ConcurrentModificationException）
        val targets = withContext(Dispatchers.Main) {
            ConvertCenter.itemsSnapshot().filter { !it.done && !it.failed }
        }
        val total = targets.size
        val done = AtomicInteger(0)
        val ok = AtomicInteger(0)
        val fail = AtomicInteger(0)
        // 源文件「读到的字节」不含 GPS 的张数（可能是拍摄时无位置，也可能是被系统脱敏）
        val noGps = AtomicInteger(0)

        // 并发度按应用堆大小动态决定：字节级转换的内存峰值约为源文件的 3~5 倍，
        // 大堆设备最多 2 路、小堆设备串行；配合 largeHeap 与 Throwable 兜底防 OOM 闪退
        val parallelism =
            if (Runtime.getRuntime().maxMemory() >= 384L * 1024 * 1024) 2 else 1
        val sem = Semaphore(parallelism)

        val jobs = targets.map { ci ->
            // 必须显式 IO：serviceScope 默认 Main 调度器，不指定会把转换跑在主线程（ANR）
            serviceScope.launch(Dispatchers.IO) {
                sem.withPermit {
                    convertOne(ci, tempDir, moveToCamera, outputRelPath, deleteOriginal, ok, fail, noGps)
                    val d = done.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        ConvertCenter.progress = d.toFloat() / total
                        ConvertCenter.progressDetail = "已处理 $d/$total"
                    }
                    persistQueueSnapshot()
                    notifyProgress(d, total)
                }
            }
        }
        jobs.joinAll()

        val finalDest = if (moveToCamera) "DCIM/Camera" else outputRelPath
        // 位置权限缺失时系统在读取层剥离 GPS（脱敏），转换产物必丢地点信息——
        // 批次结束必须把「丢了几个」明确告诉用户，而不是静默完成
        val locationGranted = try {
            checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { true }
        val noGpsCount = noGps.get()
        val reconvertBatch = targets.isNotEmpty() && targets.all { it.reconvert }
        val gpsNote = when {
            reconvertBatch && noGpsCount > 0 ->
                "；$noGpsCount 张源文件本身不含 GPS 位置（位置权限已授予，非脱敏丢失）"
            reconvertBatch ->
                "；位置信息已找回并保留"
            noGpsCount > 0 && !locationGranted ->
                "；警告：$noGpsCount 张照片读取时无 GPS（未授予「位置」权限，系统已剥离位置信息），" +
                    "可在列表上方点「重新转换」找回"
            noGpsCount > 0 && noGpsCount == ok.get() ->
                "；注意：$noGpsCount 张源文件读取时均无 GPS（「位置」权限已授予）。" +
                    "若拍摄时开启了定位，可尝试给本应用开启「所有文件访问权限」后重新转换"
            noGpsCount > 0 ->
                "；$noGpsCount 张照片源文件本身不含 GPS 位置"
            else -> ""
        }
        val batchTitle = if (reconvertBatch) "重新转换完成" else "转换完成"
        val finalStatus = "$batchTitle：成功 ${ok.get()} 个，失败 ${fail.get()} 个" +
            (if (reconvertBatch) "" else "（输出目录：$finalDest）") + gpsNote
        withContext(Dispatchers.Main) {
            ConvertCenter.isConverting = false
            ConvertCenter.progress = 0f
            ConvertCenter.progressDetail = ""
            ConvertCenter.statusText = finalStatus
        }
        persistQueueSnapshot()
        notifyDone(ok.get(), fail.get(), deleteOriginal)

        // 删除原图（已授权所有文件访问 → 服务内直接删；否则交由 Activity 弹系统确认）
        if (deleteOriginal) {
            handleDeleteOriginals(finalStatus)
        }

        // 清理暂存目录
        try { tempDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        running = false
        stopSelf()
    }

    /** 转换单张：detect → read → write → 导出 + 写后自检 → 标记结果 */
    private suspend fun convertOne(
        ci: ConvertItem,
        tempDir: File,
        moveToCamera: Boolean,
        outputRelPath: String,
        deleteOriginal: Boolean,
        ok: AtomicInteger,
        fail: AtomicInteger,
        noGps: AtomicInteger
    ) {
        val app = applicationContext
        // 重转模式（找回位置）：跳过删除原图收集（上次批次已处理过），
        // 输出原地覆盖旧产物（不产生重名序号文件）
        val isReconvert = ci.reconvert
        // 「移到相机相册 + 转换后删除原图」同时开启：
        // 产物先导出到输出目录（中转），原图删除确认完成后以原名 move 进 DCIM/Camera——
        // 先删原图腾出文件名，转换结果保持原名、无 "(1)" 序号
        val deferToCamera = !isReconvert && moveToCamera && deleteOriginal
        withContext(Dispatchers.Main) {
            ConvertCenter.replaceItem(ci, ci.copy(
                status = if (isReconvert) "重新转换中…" else "转换中…"))
        }
        var staged: String? = null
        try {
            val result = Converter.convertToVivoSingle(
                path = ci.item.path,
                outDir = tempDir.absolutePath,
                log = { level, msg, _ ->
                    if (level == "warning") {
                        // 日志回调在 IO 线程触发，状态写入需切回 Main
                        serviceScope.launch(Dispatchers.Main) { ConvertCenter.statusText = msg }
                    }
                }
            )
            staged = result.path
            // 取消检查：Converter.convertToVivoSingle 是阻塞式 JVM 调用（读取/写出文件），
            // 协程取消对它无效——用户点「取消转换」后它仍会跑完。若此处不显式检查，
            // 取消后依然会执行下面的导出，导致「用户已取消，相册却多出一张」，
            // 且该条目被复位成「待转换」、产物却已存在，重转时还会产生 (1) 重复文件。
            currentCoroutineContext().ensureActive()
            // 拍摄时间优先；缺失回退文件名解析，再回退修改时间
            val ts = when {
                ci.item.dateTaken > 0 -> ci.item.dateTaken
                else -> PhotoTime.parseFromName(ci.item.name)
                    ?: (if (ci.item.dateModified > 0) ci.item.dateModified * 1000L
                        else System.currentTimeMillis())
            }
            // 导出 + 写后自检（自检不过会抛异常 → 该项失败，原图不会被删）；
            // 重转：优先原地覆盖上次导出的记录
            val exportUri = if (isReconvert) {
                MediaExport.replaceOrExportAndVerify(
                    app, result, ts, outputRelPath, moveToCamera, ci.outUri)
            } else {
                MediaExport.exportAndVerify(
                    app, result, ts, outputRelPath, moveToCamera && !deferToCamera)
            }
            ok.incrementAndGet()
            // 源图读到的字节无 GPS（拍摄无位置，或读取层被系统脱敏）→ 单项标注 + 计数
            if (!result.sourceHasGps) noGps.incrementAndGet()
            if (!isReconvert && deferToCamera) {
                // 中转条目：删除原图确认完成后由 finalize 以原名移入相机相册
                ConvertCenter.pendingFinalize.add(
                    ConvertCenter.FinalizeEntry(exportUri, ci.item.name, ci.item.key))
            }
            // 开关开启：收集本项原图（jpg + 伴生 mp4），批次结束统一删除（重转不收集）
            if (!isReconvert && deleteOriginal) collectOriginalUris(ci)
            val statusText = when {
                isReconvert && result.sourceHasGps ->
                    "完成：已重新转换，位置信息已保留"
                isReconvert ->
                    // 重转在位置权限已授予的前提下执行：仍无 GPS = 源本身没位置
                    "完成：已重新转换（源文件本身不含 GPS 位置）"
                deferToCamera -> "完成：待原图删除后以原名移入相机相册"
                moveToCamera -> "完成：已导出到相册 DCIM/Camera"
                else -> "完成：已导出到相册 $outputRelPath"
            } + (if (!isReconvert && !result.sourceHasGps) "（源文件无 GPS 位置数据）" else "")
            // 状态写入用 NonCancellable：若取消恰好发生在导出过程中（产物已入库），
            // 这一笔必须如实记成「完成」——否则条目显示「待转换」而文件已存在，
            // 用户再转一次就会得到 (1) 重复文件。
            withContext(NonCancellable + Dispatchers.Main) {
                ConvertCenter.replaceItem(ci, ci.copy(
                    status = statusText, done = true,
                    // 重转已获权限，结果即最终结论：找到位置或源本身无位置，均不再标记丢失
                    lostGps = !isReconvert && !result.sourceHasGps,
                    outUri = exportUri.toString(),
                    reconvert = false))
            }
        } catch (e: CancellationException) {
            throw e // 协程取消必须继续传播（服务销毁等场景）
        } catch (e: Throwable) {
            // 兜底捕获 OutOfMemoryError 等 Error：单张标记失败，不再闪退整个应用
            val reason = if (e is OutOfMemoryError)
                "内存不足（文件过大），请减少单批数量后重试" else (e.message ?: "未知错误")
            fail.incrementAndGet()
            withContext(Dispatchers.Main) {
                ConvertCenter.replaceItem(ci, ci.copy(status = "失败：$reason", failed = true))
            }
        } finally {
            // 清理本地暂存产物（已导出 / 失败均清理）
            staged?.let { p -> try { File(p).delete() } catch (_: Exception) {} }
        }
    }

    // ---------- 删除原图 ----------

    /** 收集待删除原图 URI：jpg 用媒体 URI；伴生 mp4 按 DATA 路径回查视频集合 */
    private fun collectOriginalUris(ci: ConvertItem) {
        try { ConvertCenter.pendingTrashUris.add(ci.item.uri) } catch (_: Exception) {}
        // 复用 VivoDual 的伴生视频解析（含大小写兼容），避免两处逻辑分叉：
        // 原先这里独立硬拼 ".mp4"，与识别链路的规则不一致
        val mp4Path = VivoDual.siblingMp4(ci.item.path) ?: return
        val mp4 = File(mp4Path)
        if (mp4.length() > 8L) {
            resolveVideoUriByPath(mp4Path)?.let { ConvertCenter.pendingTrashUris.add(it) }
        }
    }

    /** 按 DATA 绝对路径在视频媒体库查 URI */
    private fun resolveVideoUriByPath(path: String): Uri? {
        return try {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val id = contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            id?.let { ContentUris.withAppendedId(collection, it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 批次结束后的原图删除：
     * 已授权所有文件访问 → 直接删除，随后把中转的转换产物以原名移入 DCIM/Camera；
     * 未授权 → 把有效 URI 留在 ConvertCenter.pendingTrashUris，等 Activity 在前台时
     * 拉起系统回收站确认弹窗（确认删除后再落地原名）。
     */
    private suspend fun handleDeleteOriginals(baseStatus: String) {
        val all = synchronized(ConvertCenter.pendingTrashUris) {
            ConvertCenter.pendingTrashUris.distinct().toList()
        }
        ConvertCenter.pendingTrashUris.clear()
        if (all.isEmpty()) return
        // 过滤已失效条目
        val valid = all.filter { uri ->
            try {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.moveToFirst() } == true
            } catch (_: Exception) {
                false
            }
        }
        if (valid.isEmpty()) {
            withContext(Dispatchers.Main) {
                ConvertCenter.statusText = "$baseStatus；原图删除失败（无法访问原文件）"
            }
            return
        }
        if (Environment.isExternalStorageManager()) {
            var deleted = 0
            for (uri in valid) {
                try {
                    if (contentResolver.delete(uri, null, null) > 0) deleted++
                } catch (_: Exception) {}
            }
            // 原图已删 → 名字已腾空，中转产物立即以原名移入相机相册
            val moved = finalizePendingMoves()
            val msg = "$baseStatus；已直接删除 $deleted 个原文件" +
                "（vivo 相册「第三方删除拦截」中可查看/恢复）" +
                (if (moved > 0) "；$moved 个转换结果已以原名移入相机相册" else "")
            withContext(Dispatchers.Main) { ConvertCenter.statusText = msg }
            return
        }
        // 未授权：交由 Activity 发起系统回收站确认（回到应用时触发）
        synchronized(ConvertCenter.pendingTrashUris) {
            ConvertCenter.pendingTrashUris.addAll(valid)
        }
        withContext(Dispatchers.Main) {
            // 可观察计数：Activity 在前台时（onStart 不会再触发）据此拉起确认弹窗
            ConvertCenter.pendingTrashCount = valid.size
            ConvertCenter.statusText = "$baseStatus；回到本应用确认删除原图（共 ${valid.size} 项）"
        }
    }

    /**
     * 把中转目录中的转换产物以原名移入 DCIM/Camera（原名落地）：
     * 原图已删除时无同名冲突，文件保持原名、无序号；
     * 若仍有同名（真实冲突，如用户手动复制过），uniqueCameraName 兜底加序号。
     * @return 成功移入的条数
     */
    private suspend fun finalizePendingMoves(): Int {
        val entries = synchronized(ConvertCenter.pendingFinalize) {
            ConvertCenter.pendingFinalize.toList()
        }
        ConvertCenter.pendingFinalize.clear()
        var moved = 0
        for (e in entries) {
            val okMove = MediaExport.moveUriToCamera(this, e.uri, e.originalName)
            if (okMove) moved++
            // 回写单项状态（无论成败都给用户明确结果）
            withContext(Dispatchers.Main) {
                val idx = ConvertCenter.items.indexOfFirst { it.item.key == e.itemKey }
                if (idx >= 0) {
                    val ci = ConvertCenter.items[idx]
                    ConvertCenter.items[idx] = ci.copy(
                        status = if (okMove) "完成：已以原名移入相机相册"
                                 else "完成：原名移入失败，文件保留在输出目录"
                    )
                }
            }
        }
        persistQueueSnapshot()
        return moved
    }

    /** 主线程取队列快照 → IO 写文件（绝不直接迭代 Compose 列表） */
    private suspend fun persistQueueSnapshot() {
        val snap = withContext(Dispatchers.Main) { ConvertCenter.itemsSnapshot() }
        ConvertCenter.persistQueue(applicationContext, snap)
    }

    // ---------- 通知 ----------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "转换进度", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildProgressNotification(done: Int, total: Int, indeterminate: Boolean): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle(getString(R.string.app_display_name))
            .setContentText(
                if (total > 0) "正在转换实况照片 $done/$total" else "正在准备转换…")
            .setProgress(total, done, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent())
            .build()
    }

    private fun notifyProgress(done: Int, total: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildProgressNotification(done, total, false))
        } catch (_: Exception) {}
    }

    private fun notifyDone(ok: Int, fail: Int, needTrashConfirm: Boolean) {
        try {
            ensureChannel()
            val text = "成功 $ok 个，失败 $fail 个" +
                (if (needTrashConfirm && !Environment.isExternalStorageManager())
                    "；回到应用确认删除原图" else "")
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_convert)
                .setContentTitle("转换完成")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(mainIntent())
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_DONE_ID, n)
            nm.cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val ACTION_START = "com.vliveconvert.app.action.START_CONVERT"
        private const val CHANNEL_ID = "convert"
        private const val NOTIF_ID = 100
        private const val NOTIF_DONE_ID = 101
    }
}
