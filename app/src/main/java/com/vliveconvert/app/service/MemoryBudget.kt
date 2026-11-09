package com.vliveconvert.app.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 转换内存准入闸：并发上限（Semaphore）之外再按估算峰值申请字节配额，配额不足自动排队，
 * 避免多张大图的瞬时副本叠加触顶应用堆。配额是**估算准入**，不是硬性内存上限。
 * used==0 时单项超预算也放行（独占串行），避免超大单张永久等待死锁。
 */
internal class MemoryBudget(private val totalBytes: Long) {

    private val lock = Mutex()
    private var usedBytes = 0L

    suspend fun <T> withCharge(bytes: Long, block: suspend () -> T): T {
        val want = bytes.coerceIn(MIN_CHARGE, totalBytes.coerceAtLeast(MIN_CHARGE))
        while (true) {
            val granted = lock.withLock {
                if (usedBytes == 0L || usedBytes + want <= totalBytes) {
                    usedBytes += want
                    true
                } else {
                    false
                }
            }
            if (granted) break
            delay(40)
        }
        try {
            return block()
        } finally {
            lock.withLock { usedBytes = (usedBytes - want).coerceAtLeast(0L) }
        }
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val MIN_CHARGE = 8L * MB
    }
}

/** 单张峰值估算：JPG+MP4 各持原始与重组两份，再加固定余量；地板 24MiB。 */
internal fun estimatePeakBytes(jpgBytes: Long, mp4Bytes: Long): Long {
    val mb = 1024L * 1024L
    return ((jpgBytes + mp4Bytes) * 2 + 16L * mb).coerceAtLeast(24L * mb)
}

/** 默认预算：堆上限的 45%，限制在 [128MiB, 堆的 70%]。 */
internal fun defaultBudgetBytes(maxMemory: Long = Runtime.getRuntime().maxMemory()): Long {
    val mb = 1024L * 1024L
    val upper = (maxMemory * 70 / 100).coerceAtLeast(128L * mb)
    return (maxMemory * 45 / 100).coerceIn(128L * mb, upper)
}