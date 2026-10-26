package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.service.ConvertedStore
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
 * 精确「已转」索引：源路径 → 产物 URI 的落盘映射。
 * 只辅助选择器标记/筛选；文件损坏必须静默空表，绝不拖垮主流程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertedStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val storeFile: File get() = File(context.filesDir, "converted_store.json")

    @Before
    fun cleanSlate() = ConvertedStore.clear(context)

    @Test
    fun recordAndLoadRoundTrip() {
        ConvertedStore.record(context, "/DCIM/Camera/IMG_A.jpg", "content://media/1")
        ConvertedStore.record(context, "/DCIM/Camera/IMG_B.jpg", "content://media/2")
        val map = ConvertedStore.load(context)
        assertEquals("content://media/1", map["/dcim/camera/img_a.jpg"])
        assertEquals("content://media/2", map["/dcim/camera/img_b.jpg"])
    }

    @Test
    fun rewritingSamePathKeepsLatestAndNoDuplicates() {
        ConvertedStore.record(context, "/p/IMG_A.jpg", "content://old")
        ConvertedStore.record(context, "/p/IMG_A.jpg", "content://new")
        val map = ConvertedStore.load(context)
        assertEquals(1, map.size)
        assertEquals("content://new", map["/p/img_a.jpg"])
    }

    @Test
    fun capsAtFiveThousandKeepingNewest() {
        repeat(5005) { ConvertedStore.record(context, "/p/IMG_$it.jpg", "content://$it") }
        val map = ConvertedStore.load(context)
        assertEquals(5000, map.size)
        assertEquals("content://5004", map["/p/img_5004.jpg"])
        assertNull("最旧的 5 条应被淘汰", map["/p/img_0.jpg"])
    }

    @Test
    fun corruptFileYieldsEmpty() {
        storeFile.writeText("{ not json ]")
        assertTrue(ConvertedStore.load(context).isEmpty())
    }

    @Test
    fun blankInputIsIgnored() {
        ConvertedStore.record(context, "", "content://x")
        ConvertedStore.record(context, "/p/a.jpg", "")
        assertTrue(ConvertedStore.load(context).isEmpty())
    }
}
