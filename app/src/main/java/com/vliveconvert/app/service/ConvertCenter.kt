package com.vliveconvert.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vliveconvert.app.ui.ConvertItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * 转换中心：进程级共享的转换状态（Compose 可观察）+ 队列落盘。
 *
 * 线程纪律（v1.0.9 起，防 ConcurrentModificationException 闪退）：
 * - [items] 等所有 Compose 状态的**写**一律在主线程（服务内用 withContext(Main)）；
 * - 任何线程要**遍历**队列，只能用 [itemsSnapshot]（主线程取出的不可变快照），
 *   严禁在 IO 线程直接迭代 [items]——曾与主线程写并发导致转换中闪退；
 * - JSON 编解码只走 [QueueJson] 纯函数（普通 List 进出）。
 */
object ConvertCenter {

    /** 转换队列（含每项状态），UI 与服务共同读写（写均发生在主线程） */
    val items = mutableStateListOf<ConvertItem>()

    var isConverting by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var progressDetail by mutableStateOf("")
    /** 应用级状态行（权限提示 / 转换进度 / 完成摘要共用） */
    var statusText by mutableStateOf("")

    /**
     * 批次结束后待 Activity 发起「移入回收站」确认的原图 URI
     * （未授予所有文件访问权限时的删除路径；系统弹窗只能由 Activity 拉起）。
     * 仅存内存：进程被杀时这些 URI 丢失，原图保持原样（宁可漏删不可误删）。
     */
    val pendingTrashUris: MutableList<Uri> =
        Collections.synchronizedList(mutableListOf<Uri>())

    /**
     * 待确认删除的原图数（pendingTrashUris 快照）。
     * 独立成 Compose 状态：批次结束、用户仍停留前台时（onStart 不会再次触发），
     * Activity 通过观察此值变化拉起回收站确认弹窗。
     */
    var pendingTrashCount by mutableIntStateOf(0)

    /**
     * 待落地条目：「移到相机相册 + 转换后删除原图」同时开启时，
     * 转换产物先导出到输出目录（中转），待原图删除确认完成后
     * 以原名 move 进 DCIM/Camera——原图先删腾名，避免 MediaStore 自动加 "(1)" 序号
     * （安全语义不变：原图删除发生在新文件完整落盘并自检通过之后）。
     */
    class FinalizeEntry(val uri: Uri, val originalName: String, val itemKey: String)

    val pendingFinalize: MutableList<FinalizeEntry> =
        Collections.synchronizedList(mutableListOf())

    private const val QUEUE_FILE = "convert_queue.json"

    /** 主线程调用：队列的不可变快照（IO 线程的遍历/持久化只能用它） */
    fun itemsSnapshot(): List<ConvertItem> = items.toList()

    /** 把快照落盘（纯 IO 操作，只接收不可变快照，绝不读 Compose 状态） */
    fun persistQueue(context: Context, snapshot: List<ConvertItem>) {
        try {
            File(context.filesDir, QUEUE_FILE).writeText(QueueJson.toJson(snapshot))
        } catch (_: Exception) {}
    }

    /** 清空队列文件（用户「清空」时调用） */
    fun clearQueue(context: Context) {
        try { File(context.filesDir, QUEUE_FILE).delete() } catch (_: Exception) {}
    }

    /**
     * 从磁盘恢复上次未完成的队列：IO 线程读文件并解析，主线程填充 [items]。
     * @return 恢复后仍未完成的条数（0 表示无需恢复）
     */
    suspend fun loadQueue(context: Context): Int {
        val f = File(context.filesDir, QUEUE_FILE)
        if (!f.exists()) return 0
        val parsed = try { QueueJson.fromJson(f.readText()) } catch (_: Exception) { null }
        if (parsed == null) return 0
        return withContext(Dispatchers.Main) {
            if (items.isNotEmpty()) return@withContext 0
            items.addAll(parsed)
            parsed.count { !it.done && !it.failed }
        }
    }

    /** 开始转换：持久化队列快照并启动前台服务执行批次（主线程调用） */
    fun start(context: Context): Boolean {
        if (isConverting) return false
        val targets = items.filter { !it.done && !it.failed }
        if (targets.isEmpty()) {
            statusText = "没有待转换的照片"
            return false
        }
        persistQueue(context, items.toList())
        isConverting = true
        progress = 0f
        progressDetail = "已处理 0/${targets.size}"
        context.startForegroundService(
            Intent(context, ConvertService::class.java).setAction(ConvertService.ACTION_START))
        return true
    }

    /**
     * 重新转换丢位置的条目（主线程调用）：把 done && lostGps 的条目重置为待转换
     * 并标记 reconvert（跳过删除原图收集、输出原地覆盖旧产物），随后启动服务批次。
     * @param targets 调用方已预过滤的「源文件仍在、可重转」条目
     * @return 实际进入重转的条数（0 = 无可重转项）
     */
    fun beginReconvert(context: Context, targets: List<ConvertItem>): Int {
        if (isConverting) return 0
        if (targets.isEmpty()) return 0
        // 重置目标条目：保留 outUri（覆盖旧产物用），清 done/failed，标记重转模式
        targets.forEach { t ->
            replaceItem(t, t.copy(
                status = "重新转换中…", done = false, failed = false, reconvert = true))
        }
        persistQueue(context, items.toList())
        isConverting = true
        progress = 0f
        progressDetail = "已处理 0/${targets.size}"
        statusText = "正在重新转换 ${targets.size} 张照片以找回位置信息…"
        context.startForegroundService(
            Intent(context, ConvertService::class.java).setAction(ConvertService.ACTION_START))
        return targets.size
    }

    /** 可重转条目数（已完成但丢位置的）：主界面「重新转换找回位置」入口的计数 */
    fun reconvertCandidates(): List<ConvertItem> =
        items.filter { it.done && it.lostGps && !it.failed }

    /** 按源文件 key 替换列表项（主线程调用） */
    fun replaceItem(old: ConvertItem, new: ConvertItem) {
        val idx = items.indexOfFirst { it.item.key == old.item.key }
        if (idx >= 0) items[idx] = new
    }
}
