package com.vliveconvert.app

import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.mergeClearedUndo
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「清空」撤销的合并语义：恢复原顺序，但不回退清空后用户新加入的条目。 */
class ClearAllUndoTest {

    private fun item(id: Long) = MediaItem(
        id = id, path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        name = "IMG_$id.jpg", bucketId = 1L,
        dateTaken = 1_754_000_000_000L + id, dateModified = 1_754_000_000L + id,
        size = 1024L + id
    )

    private fun entry(id: Long) = ConvertItem(item = item(id))

    @Test
    fun undoRestoresClearedItemsInOriginalOrder() {
        val cleared = listOf(entry(1), entry(2), entry(3))
        val merged = mergeClearedUndo(cleared, current = emptyList())
        assertEquals("清空的条目必须一张不少", 3, merged.size)
        assertEquals("恢复原顺序", listOf(1L, 2L, 3L), merged.map { it.item.id })
    }

    @Test
    fun undoKeepsItemsAddedAfterClearing() {
        val cleared = listOf(entry(1), entry(2))
        val newlyAdded = entry(9)
        val merged = mergeClearedUndo(cleared, current = listOf(newlyAdded))
        assertEquals("快照排在前面、新加的保持在后", listOf(1L, 2L, 9L), merged.map { it.item.id })
    }

    @Test
    fun undoDoesNotRollBackStateOfNewlyAddedDuplicate() {
        val cleared = listOf(entry(1))
        val freshDuplicate = entry(1).copy(
            status = "失败：源文件已丢失", failed = true, done = true)
        val merged = mergeClearedUndo(cleared, current = listOf(freshDuplicate))
        assertEquals("同一张只保留一份", 1, merged.size)
        val kept = merged.single()
        assertTrue("必须保留当前条目而不是清空前的旧状态", kept.failed && kept.done)
        assertEquals("失败：源文件已丢失", kept.status)
    }
}
