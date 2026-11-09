package com.vliveconvert.app

import com.vliveconvert.app.service.MemoryBudget
import com.vliveconvert.app.service.defaultBudgetBytes
import com.vliveconvert.app.service.estimatePeakBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 内存准入闸：超预算串行、超大单张不死锁、异常释放配额；估算与默认预算的精确值。 */
class MemoryBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun serializesWhenOverBudget() = runBlocking {
        val budget = MemoryBudget(100 * mb)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val jobs = (1..2).map {
            launch(Dispatchers.Default) {
                budget.withCharge(60 * mb) {
                    val a = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, a) }
                    delay(80)
                    active.decrementAndGet()
                }
            }
        }
        jobs.joinAll()
        assertEquals("两个 60MiB 任务在 100MiB 预算下必须串行", 1, peak.get())
    }

    @Test
    fun oversizedSingleStillRuns() = runBlocking {
        val budget = MemoryBudget(100 * mb)
        val ran = budget.withCharge(150 * mb) { 42 }
        assertEquals("单项超预算应独占放行，不死锁", 42, ran)
    }

    @Test
    fun releasesChargeOnException() = runBlocking {
        val budget = MemoryBudget(100 * mb)
        try {
            budget.withCharge(80 * mb) { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }
        // 配额若未释放，第二个 80MiB 会永久等待；withTimeout 把死锁变成失败
        val ok = withTimeout(2000) { budget.withCharge(80 * mb) { true } }
        assertTrue(ok)
    }

    @Test
    fun estimatePeakValues() {
        assertEquals(76 * mb, estimatePeakBytes(10 * mb, 20 * mb))
        assertEquals(24 * mb, estimatePeakBytes(0, 0))
    }

    @Test
    fun defaultBudgetValues() {
        assertEquals(241591910L, defaultBudgetBytes(512 * mb))
        assertEquals(134217728L, defaultBudgetBytes(256 * mb)) // 45% 低于 128MiB → 取下限
    }
}