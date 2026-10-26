package com.vliveconvert.app.service

import android.content.Context
import android.os.PowerManager

/**
 * 批次 wakelock：前台服务并不阻止 CPU 休眠——熄屏长批次会被 Doze 限速到近乎停滞
 * （真机实测：亮屏 20 秒走 3000 张，熄屏后几分钟才走几千）。只在批次执行期间持有
 * partial 锁（不点屏），2 小时超时兜底；异常路径（取消 / 服务被回收 / onTimeout）
 * 由 onDestroy 与超时共同保证最终放锁。保活失败不阻塞转换，静默降级。
 */
internal object BatchWakeLock {

    private const val TAG = "yingzhongying:convert"
    private const val TIMEOUT_MS = 2L * 60 * 60 * 1000

    @Volatile private var lock: PowerManager.WakeLock? = null

    /** 幂等：已持锁时直接返回（pendingStart 重入 runBatch 会再次调用） */
    fun acquire(context: Context) {
        if (lock?.isHeld == true) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            wl.setReferenceCounted(false)
            wl.acquire(TIMEOUT_MS)
            lock = wl
        } catch (_: Exception) {
        }
    }

    /** 未持有时调用必须安全（取消与销毁可能乱序到达） */
    fun release(context: Context) {
        try {
            lock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        } finally {
            lock = null
        }
    }
}
