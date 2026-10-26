package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.service.BatchLog
import com.vliveconvert.app.service.BatchLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 批次日志的持久化语义：往返一致、上限 50（挤掉最旧）、文件损坏静默空表、可清空。
 * 「静默空表」是硬要求：日志只是辅助记忆，绝不能因为它崩掉主流程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchLogTest {

    private val context get() = ApplicationProvider.getApplicationContext<VliveApp>()

    private fun entry(timeMs: Long, ok: Int = 1, fail: Int = 0) = BatchLogEntry(
        timeMs = timeMs, ok = ok, fail = fail, noGps = 0,
        reconvert = false, deletedOriginals = 0, movedToCamera = 0,
        dest = "DCIM/Camera"
    )

    private val logFile: File get() = File(context.filesDir, "batch_log.json")

    @Before
    fun cleanSlate() {
        BatchLog.clear(context)
    }

    @Test
    fun appendLoadRoundTrip() {
        BatchLog.append(context, entry(1_000L, ok = 3))
        BatchLog.append(context, entry(2_000L, ok = 5, fail = 1))
        val loaded = BatchLog.load(context)
        assertEquals(2, loaded.size)
        assertEquals("最新在前", 2_000L, loaded.first().timeMs)
        assertEquals(5, loaded.first().ok)
        assertEquals(1, loaded.first().fail)
        assertEquals("DCIM/Camera", loaded.first().dest)
    }

    @Test
    fun capsAtFiftyKeepingNewest() {
        repeat(55) { BatchLog.append(context, entry(it.toLong() + 1)) }
        val loaded = BatchLog.load(context)
        assertEquals(50, loaded.size)
        assertEquals("最新在前", 55L, loaded.first().timeMs)
        assertEquals("最旧的 5 条被挤出", 6L, loaded.last().timeMs)
    }

    @Test
    fun corruptFileYieldsEmptyNotCrash() {
        logFile.writeText("{ 不是 json ]")
        assertTrue(BatchLog.load(context).isEmpty())
    }

    @Test
    fun missingFileYieldsEmpty() {
        assertTrue(BatchLog.load(context).isEmpty())
    }

    @Test
    fun clearRemovesEverything() {
        BatchLog.append(context, entry(1L))
        BatchLog.clear(context)
        assertTrue(BatchLog.load(context).isEmpty())
    }

    @Test
    fun firstOutputUriSurvivesRoundTrip() {
        BatchLog.append(context, entry(1_000L).copy(firstOutUri = "content://media/external/images/media/9"))
        assertEquals("content://media/external/images/media/9",
            BatchLog.load(context).first().firstOutUri)
    }

    @Test
    fun entryWithoutOutputUriLoadsNull() {
        BatchLog.append(context, entry(1_000L))
        assertNull(BatchLog.load(context).first().firstOutUri)
    }
}
