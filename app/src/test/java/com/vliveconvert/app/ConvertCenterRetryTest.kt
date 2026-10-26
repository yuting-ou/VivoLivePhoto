package com.vliveconvert.app

import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.ui.ConvertItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「重试失败 N」的复位语义：只复位 failed 条目，done/待转换条目原样保留；
 * 没有失败项时返回 0（UI 据此不发起空批次）。
 */
class ConvertCenterRetryTest {

    private fun mediaItem(id: Long) = MediaItem(
        id = id, path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        name = "IMG_$id.jpg", bucketId = 1L,
        dateTaken = 1_754_000_000_000L + id, dateModified = 1_754_000_000L + id,
        size = 1024L + id
    )

    private fun entry(
        id: Long,
        failed: Boolean = false,
        done: Boolean = false
    ) = ConvertItem(
        item = mediaItem(id),
        status = when {
            failed -> "失败：转换中断"
            done -> "完成：已导出"
            else -> "待转换"
        },
        failed = failed,
        done = done
    )

    @Before
    fun setUp() = ConvertCenter.items.clear()

    @After
    fun tearDown() = ConvertCenter.items.clear()

    @Test
    fun resetFailedReturnsCountAndResetsOnlyFailed() {
        ConvertCenter.items.addAll(
            listOf(
                entry(1, failed = true),
                entry(2),
                entry(3, done = true),
                entry(4, failed = true)
            )
        )

        assertEquals(2, ConvertCenter.resetFailed())

        val reset = ConvertCenter.items.filter { it.item.id == 1L || it.item.id == 4L }
        assertTrue("失败项必须复位为待转换", reset.all { !it.failed && it.status == "待转换" })
        assertEquals("完成项不得被触碰", true, ConvertCenter.items.first { it.item.id == 3L }.done)
        assertEquals("待转换项不得被触碰", "待转换",
            ConvertCenter.items.first { it.item.id == 2L }.status)
    }

    @Test
    fun noFailedItemsReturnsZero() {
        ConvertCenter.items.addAll(listOf(entry(1), entry(2, done = true)))
        assertEquals(0, ConvertCenter.resetFailed())
        assertEquals("列表不得被改动", 2, ConvertCenter.items.size)
    }

    @Test
    fun emptyListReturnsZero() {
        assertEquals(0, ConvertCenter.resetFailed())
    }
}
