package com.vliveconvert.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.ui.ShareIntents
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 分享导入的安全边界：外部 Provider 给的文件名是不可信输入。
 * 三条不变量：不越出缓存目录、同名再次导入不覆盖前次（队列可能仍引用）、
 * 失败只清理本次导入自己的半成品。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareImportIsolationTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()

    private fun sourcePair(stem: String, imgByte: Int, mp4Byte: Int): Pair<File, File> {
        val dir = Files.createTempDirectory("share_src").toFile()
        val img = File(dir, "$stem.jpg").apply { writeBytes(ByteArray(16) { imgByte.toByte() }) }
        val mp4 = File(dir, "$stem.mp4").apply { writeBytes(ByteArray(64) { mp4Byte.toByte() }) }
        return img to mp4
    }

    @Test
    fun rejectsTraversalAndAbsoluteNames() {
        val (img, mp4) = sourcePair("IMG_A", 1, 2)
        for (stem in listOf("../../escape", "x/../../y", "/abs/escape", "a\\b")) {
            assertNull("必须拒绝路径性名称: $stem",
                ShareIntents.copyPair(app, Uri.fromFile(img), Uri.fromFile(mp4), stem))
        }
        assertFalse(File(app.cacheDir, "escape.jpg").exists())
        assertFalse(File(app.cacheDir.parentFile, "escape.jpg").exists())
    }

    @Test
    fun sameNameImportsRemainIndependent() {
        val (img1, mp41) = sourcePair("IMG_SAME", 1, 2)
        val first = ShareIntents.copyPair(
            app, Uri.fromFile(img1), Uri.fromFile(mp41), "IMG_SAME")
        assertNotNull(first)
        val firstBytes = first!!.first.readBytes()

        val (img2, mp42) = sourcePair("IMG_SAME", 3, 4)
        val second = ShareIntents.copyPair(
            app, Uri.fromFile(img2), Uri.fromFile(mp42), "IMG_SAME")
        assertNotNull(second)
        assertNotEquals("同名两次导入必须各有独立路径",
            first.first.absolutePath, second!!.first.absolutePath)
        assertArrayEquals("前次导入的字节不能被覆盖", firstBytes, first.first.readBytes())
        assertTrue(second.first.readBytes().all { it == 3.toByte() })
    }

    @Test
    fun secondStreamFailureCleansOnlyOwnImport() {
        val (img1, mp41) = sourcePair("IMG_OK", 1, 2)
        val first = ShareIntents.copyPair(
            app, Uri.fromFile(img1), Uri.fromFile(mp41), "IMG_OK")
        assertNotNull(first)

        val (img2, _) = sourcePair("IMG_BAD", 5, 6)
        val missing = File(img2.parentFile, "NOPE.mp4") // 不存在 → 第二个流读取失败
        assertNull(ShareIntents.copyPair(
            app, Uri.fromFile(img2), Uri.fromFile(missing), "IMG_BAD"))

        assertTrue("前次完整导入必须原样保留", first!!.first.exists() && first.second.exists())
        val shared = File(app.cacheDir, "shared")
        assertTrue("失败导入不得留下半成品",
            shared.walkTopDown().filter { it.isFile }.none { it.name.startsWith("IMG_BAD") })
    }

    @Test
    fun acceptsPlainChineseName() {
        val (img, mp4) = sourcePair("实况照片", 1, 2)
        val copied = ShareIntents.copyPair(
            app, Uri.fromFile(img), Uri.fromFile(mp4), "实况照片")
        assertNotNull(copied)
        assertEquals("实况照片.jpg", copied!!.first.name)
        assertEquals("实况照片.mp4", copied.second.name)
    }
}