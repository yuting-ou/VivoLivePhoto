package com.vliveconvert.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全库搜索的查询层：名称 contains（大小写不敏感）+ 日期范围 + 条数上限。
 * 过滤放在 Kotlin 侧而不是 SQL LIKE——Robolectric 的 MediaStore 不支持 LIKE，
 * 且全库投影本身只发生一次（去抖后）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaRepoSearchTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val resolver get() = context.contentResolver
    private val repo get() = MediaRepo(resolver)
    private val now = 1_800_000_000_000L
    private val day = 24L * 3600 * 1000

    private fun insert(name: String, taken: Long, rel: String = "DCIM/Camera/") {
        resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.DATE_TAKEN, taken)
                put(MediaStore.MediaColumns.SIZE, 100)
            }
        )!!
    }

    @Test
    fun nameContainsIsCaseInsensitiveAcrossAlbums() {
        insert("IMG_Alpha_001.jpg", now - day)
        insert("IMG_BETA_001.jpg", now - day, rel = "Pictures/Other/")
        val hits = repo.searchImages("alpha", days = 0, nowMs = now)
        assertEquals(1, hits.size)
        assertEquals("IMG_Alpha_001.jpg", hits[0].name)
    }

    @Test
    fun dateRangeFilterApplies() {
        insert("IMG_RECENT.jpg", now - 2 * day)
        insert("IMG_OLD.jpg", now - 40 * day)
        val hits = repo.searchImages("IMG_", days = 7, nowMs = now)
        assertEquals(listOf("IMG_RECENT.jpg"), hits.map { it.name })
    }

    @Test
    fun limitCapsResultsKeepingNewest() {
        repeat(5) { insert("IMG_$it.jpg", now - it * day) }
        val hits = repo.searchImages("IMG_", days = 0, nowMs = now, limit = 3)
        assertEquals(3, hits.size)
        assertTrue("按拍摄时间倒序", hits[0].sortTime >= hits[1].sortTime)
    }

    @Test
    fun blankQueryReturnsEmpty() {
        insert("IMG_Alpha.jpg", now)
        assertTrue(repo.searchImages("   ", days = 0, nowMs = now).isEmpty())
    }
}
