package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.ScanCache
import com.vliveconvert.app.picker.partitionCached
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冷扫描缓存：命中即展示、未命中再扫；文件编辑（key 变化）必须重扫，
 * 已删除项不得从缓存里复活；缓存文件损坏静默空表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCacheTest {

    private val context get() = ApplicationProvider.getApplicationContext<VliveApp>()

    private fun item(id: Long, name: String, size: Long = 100, modified: Long = 1000) = MediaItem(
        id = id, path = "/storage/emulated/0/DCIM/Camera/$name", name = name, bucketId = 7L,
        dateTaken = 1_754_000_000_000L + id, dateModified = modified, size = size
    )

    @Test
    fun saveAndLoadRoundTrip() {
        val cache = ScanCache(context)
        val items = listOf(item(1, "IMG_A.jpg"), item(2, "IMG_B.jpg"))
        cache.save(7L, items)
        val loaded = ScanCache(context).load(7L)
        assertEquals(2, loaded.size)
        assertTrue(loaded.containsKey(items[0].key))
        assertEquals("IMG_B.jpg", loaded[items[1].key]?.name)
    }

    @Test
    fun saveReplacesBucketSnapshot() {
        val cache = ScanCache(context)
        cache.save(7L, listOf(item(1, "IMG_A.jpg"), item(2, "IMG_B.jpg")))
        cache.save(7L, listOf(item(1, "IMG_A.jpg")))
        assertEquals(1, ScanCache(context).load(7L).size)
    }

    @Test
    fun partitionTreatsEditedFileAsNew() {
        val cachedOld = item(1, "IMG_A.jpg", size = 100, modified = 1000)
        val currentEdited = item(1, "IMG_A.jpg", size = 200, modified = 2000)
        val currentSame = item(2, "IMG_B.jpg")
        val cached = mapOf(cachedOld.key to cachedOld)
        val (hits, pending) = partitionCached(listOf(currentEdited, currentSame), cached)
        assertTrue("key 变化必须重扫", hits.isEmpty())
        assertEquals(2, pending.size)
    }

    @Test
    fun corruptCacheYieldsEmpty() {
        java.io.File(context.filesDir, "picker_cache.json").writeText("{ broken ]")
        assertTrue(ScanCache(context).load(7L).isEmpty())
    }
}
