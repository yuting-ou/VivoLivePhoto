package com.vliveconvert.app

import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.filterByQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择器文件名搜索的口径：纯 contains（大小写不敏感），**不解释任何通配符**——
 * vivo 文件名 IMG_20260831_134354 本身就带 `_`，把 `_`/`%` 当通配符解释必然误伤。
 */
class PickerSearchTest {

    private fun item(id: Long, name: String) = MediaItem(
        id = id, path = "/storage/emulated/0/DCIM/Camera/$name", name = name,
        bucketId = 1L, dateTaken = 0L, dateModified = 0L, size = 0L
    )

    private val items = listOf(
        item(1, "IMG_20260831_134354.jpg"),
        item(2, "img_20260830_091512.jpg"),
        item(3, "PORTRAIT_01.jpg")
    )

    @Test
    fun blankQueryReturnsAll() {
        assertEquals(items, filterByQuery(items, ""))
        assertEquals(items, filterByQuery(items, "   "))
    }

    @Test
    fun caseInsensitiveContains() {
        assertEquals(listOf(1L), filterByQuery(items, "20260831").map { it.id })
        assertEquals(listOf(2L), filterByQuery(items, "IMG_20260830").map { it.id })
        assertEquals(listOf(3L), filterByQuery(items, "portrait").map { it.id })
    }

    @Test
    fun wildcardsAreLiteral() {
        assertTrue("IMG_% 不应匹配任何文件", filterByQuery(items, "IMG_%").isEmpty())
    }

    @Test
    fun orderPreserved() {
        val out = filterByQuery(items, "IMG")
        assertEquals(listOf(1L, 2L), out.map { it.id })
    }
}
