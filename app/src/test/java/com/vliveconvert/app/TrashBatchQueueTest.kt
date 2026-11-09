package com.vliveconvert.app

import android.net.Uri
import com.vliveconvert.app.service.TrashBatchQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 分批确认队列的记账不变量：批边界、确认推进、取消保留剩余、部分成功可如实汇报。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashBatchQueueTest {

    private fun uris(n: Int): List<Uri> = (0 until n).map { Uri.parse("content://media/$it") }

    @Test
    fun emptyQueueHasNothing() {
        val q = TrashBatchQueue(emptyList())
        assertEquals(0, q.total)
        assertFalse(q.hasMore)
        assertTrue(q.remaining().isEmpty())
        assertTrue(q.confirmed.isEmpty())
    }

    @Test
    fun chunkBoundariesAndConfirmProgression() {
        val q = TrashBatchQueue(uris(301), batchSize = 150)
        assertEquals(301, q.total)
        assertEquals(150, q.peek().size)
        assertEquals(150, q.confirm().size)
        assertTrue(q.hasMore)
        assertEquals(150, q.peek().size)
        q.confirm()
        assertTrue(q.hasMore)
        assertEquals(1, q.peek().size)
        q.confirm()
        assertFalse(q.hasMore)
        assertEquals(301, q.confirmed.size)
        assertTrue(q.remaining().isEmpty())
    }

    @Test
    fun cancelLeavesRemainingUnconfirmed() {
        val q = TrashBatchQueue(uris(300), batchSize = 150)
        q.confirm()
        assertEquals(150, q.confirmed.size)
        assertEquals(150, q.remaining().size)
        assertTrue(q.hasMore)
    }

    @Test
    fun underLimitIsSingleBatch() {
        val q = TrashBatchQueue(uris(100), batchSize = 150)
        assertEquals(100, q.peek().size)
        q.confirm()
        assertFalse(q.hasMore)
        assertEquals(100, q.confirmed.size)
    }
}