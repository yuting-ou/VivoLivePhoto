package com.vliveconvert.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vliveconvert.app.MainActivity
import com.vliveconvert.app.R
import com.vliveconvert.app.convert.LocationDisplayRepair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 找回位置·后台重建前台服务（v2.6.0-test21）：
 *
 * 重建曾跑在页面协程里——离开页面即中断，几百张的大批量只能守着屏幕等。
 * 挪进前台服务后切后台不中断，进度走通知栏；页面在前时经 [ConvertCenter]
 * 的共享状态渲染同款进度条，完成后回到页面自动重扫。
 *
 * 行重建机制与全量扫同源（LocationDisplayRepair.recreateRow）：每张独立
 * delete+insert，服务在两张之间被取消/超时都安全（已完成的已落地）。
 */
class LocationRepairService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            finishRepair(aborted = true)
            return START_NOT_STICKY
        }
        val uris = intent?.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val names = intent?.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        if (uris.isEmpty() || ConvertCenter.isLocationRepairing) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                NOTIF_ID, buildNotification(0, uris.size, indeterminate = true))
        } catch (e: Exception) {
            ConvertCenter.repairResult = "重建服务无法启动（${e.javaClass.simpleName}），请重试"
            stopSelf()
            return START_NOT_STICKY
        }
        ConvertCenter.isLocationRepairing = true
        ConvertCenter.repairResult = null
        ConvertCenter.repairProgress = 0 to uris.size
        ConvertCenter.repairCurrent = null
        scope.launch {
            runRepair(uris, names)
        }
        return START_NOT_STICKY
    }

    private suspend fun runRepair(uris: List<String>, names: List<String>) {
        val rows = uris.mapIndexed { i, u ->
            LocationDisplayRepair.MissingRow(
                uri = Uri.parse(u),
                name = names.getOrNull(i) ?: "IMG_$i.jpg",
                dateTaken = 0L)
        }
        val processed = LocationDisplayRepair.loadProcessed(applicationContext)
        val stats = LocationDisplayRepair.rebuildSelected(
            applicationContext, rows, processed,
            onProgress = { done, total ->
                ConvertCenter.repairProgress = done to total
                ConvertCenter.repairCurrent =
                    rows.getOrNull(done)?.name ?: rows.getOrNull(done - 1)?.name
                notifyProgress(done, total)
            })
        LocationDisplayRepair.saveProcessed(applicationContext, processed)
        // 修复记录落盘（与页面内重建、破损修复同一通道）
        BatchLog.appendRepair(applicationContext, stats.rebuilt, stats.failed, null)
        finishRepair(aborted = false, stats = stats)
    }

    /** 收尾：状态复位 + 结果摘要（页面消费）+ 完成通知 + 停服 */
    private fun finishRepair(aborted: Boolean, stats: LocationDisplayRepair.RebuildStats? = null) {
        val msg = when {
            aborted -> "已取消重建：已完成的照片保持已修状态，其余仍在列表里"
            stats == null -> "重建结束"
            else -> StringBuilder("重建成功 ${stats.rebuilt} 张").apply {
                if (stats.noCoords > 0) append("，无坐标可恢复跳过 ${stats.noCoords} 张")
                if (stats.failed > 0) append("，失败 ${stats.failed} 张")
                append("。")
                if (stats.pausedForSpace) {
                    append("\n\n可用空间不足已暂停——请到相册「最近删除 / 第三方删除拦截」" +
                        "清空回收站后重新扫描，剩余的会继续出现在列表里。")
                }
                if (stats.rebuilt > 0) {
                    append("\n\n重建过的照片「加入相册时间」已变为今天（拍摄时间不受影响）。")
                }
            }.toString()
        }
        ConvertCenter.isLocationRepairing = false
        ConvertCenter.repairResult = msg
        try {
            getSystemService(NotificationManager::class.java)?.apply {
                cancel(NOTIF_ID)
                notify(
                    NOTIF_DONE_ID, NotificationCompat.Builder(this@LocationRepairService, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_convert)
                        .setContentTitle("找回位置")
                        .setContentText(msg.substringBefore("。\n\n").substringBefore("。"))
                        .setAutoCancel(true)
                        .setContentIntent(mainIntent())
                        .build())
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    /** Android 15+ dataSync 6 小时配额：立刻收摊，已完成的照片保持已修状态 */
    override fun onTimeout(startId: Int, fgiType: Int) {
        finishRepair(aborted = true)
    }

    override fun onDestroy() {
        scope.cancel()
        // 兜底复位：协程被回收而状态没走到收尾时，别让界面永远显示「重建中」
        if (ConvertCenter.isLocationRepairing) {
            ConvertCenter.isLocationRepairing = false
            ConvertCenter.repairResult = "重建已中止（服务被系统回收），可重新扫描继续"
        }
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "找回位置", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(
        done: Int, total: Int, indeterminate: Boolean
    ): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle(getString(R.string.app_display_name))
            .setContentText(
                if (indeterminate) "正在准备重建…" else "正在重建位置 $done/$total")
            .setProgress(total, done, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent())
            .addAction(
                0, "取消",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, LocationRepairService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun notifyProgress(done: Int, total: Int) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(done, total, indeterminate = false))
        } catch (_: Exception) {}
    }

    private fun mainIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val ACTION_START = "com.vliveconvert.app.action.START_LOCATION_REPAIR"
        const val ACTION_CANCEL = "com.vliveconvert.app.action.CANCEL_LOCATION_REPAIR"
        private const val CHANNEL_ID = "location_repair"
        private const val NOTIF_ID = 110
        private const val NOTIF_DONE_ID = 111
        const val EXTRA_URIS = "uris"
        const val EXTRA_NAMES = "names"

        /** 主线程调用：打包选中项并拉起后台重建服务 */
        internal fun start(context: android.content.Context, rows: List<LocationDisplayRepair.MissingRow>): Boolean {
            if (rows.isEmpty() || ConvertCenter.isLocationRepairing) return false
            val intent = Intent(context, LocationRepairService::class.java)
                .setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(rows.map { it.uri.toString() }))
                .putStringArrayListExtra(EXTRA_NAMES, ArrayList(rows.map { it.name }))
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                ConvertCenter.repairResult =
                    "无法启动重建服务（${e.javaClass.simpleName}），请回到应用后重试"
                false
            }
        }
    }
}
