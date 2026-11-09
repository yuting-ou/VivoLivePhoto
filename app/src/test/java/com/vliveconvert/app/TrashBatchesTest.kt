package com.vliveconvert.app

import android.net.Uri
import com.vliveconvert.app.service.DEFAULT_TRASH_BATCH
import com.vliveconvert.app.service.chunkTrashBatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 分批不变量：批不超上限、jpg/mp4 相邻对不拆批、空/单/尾批边界正确。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashBatchesTest {

    private fun uris(n: Int): List<Uri> = (0 until n).map { Uri.parse("content://media/$it") }

    @Test
    fun emptyInputYieldsNoBatches() {
        assertTrue(chunkTrashBatches(emptyList()).isEmpty())
    }

    @Test
    fun batchesRespectLimitAndPairAlignment() {
        val batches = chunkTrashBatches(uris(300))
        assertEquals(2, batches.size)
        assertEquals(listOf(150, 150), batches.map { it.size })
        // 成对：同批内偶数下标是 jpg、紧随其 mp4（序列相邻）
        batches.forEach { b ->
            assertEquals(0, b.size % 2)
        }
        // 相邻对绝不被拆进两批：全序列两两一组，每组必须落在同一批
        val all = uris(300)
        val flat = batches.flatten()
        assertEquals(all, flat)
        val pos = HashMap<Uri, Int>()
        flat.forEachIndexed { idx, u -> pos[u] = idx }
        for (i in 0 until 300 step 2) {
            assertEquals(pos[all[i]]!! / DEFAULT_TRASH_BATCH, pos[all[i + 1]]!! / DEFAULT_TRASH_BATCH)
        }
    }

    @Test
    fun oddCountKeepsLastSingleInTailBatch() {
        val batches = chunkTrashBatches(uris(301))
        assertEquals(listOf(150, 150, 1), batches.map { it.size })
    }

    @Test
    fun singleUriIsSingleBatch() {
        assertEquals(listOf(uris(1)), chunkTrashBatches(uris(1)))
    }
}