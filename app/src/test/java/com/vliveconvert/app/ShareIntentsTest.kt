package com.vliveconvert.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.ui.ShareIntents
import org.junit.Assert.assertEquals
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
 * 分享生态的纯逻辑：完成条目的分享 intent 构造、外部分享「恰好一对 .jpg+.mp4」
 * 的配对判定、以及配对文件拷贝（转换管线只认路径，外部分享必须先落地缓存）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentsTest {

    @Test
    fun livePhotoIntentCarriesStreamWithReadGrant() {
        val intent = ShareIntents.forLivePhoto(Uri.parse("content://media/1"))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals(Uri.parse("content://media/1"), intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun e(uri: String, name: String?, mime: String?) =
        Triple(Uri.parse(uri), name, mime)

    @Test
    fun classifyAcceptsExactlyOnePairWithSameStem() {
        val pair = ShareIntents.classify(
            listOf(
                e("content://a/1", "IMG_A.jpg", "image/jpeg"),
                e("content://a/2", "IMG_A.mp4", "video/mp4")
            )
        )
        assertNotNull(pair)
        assertEquals(Uri.parse("content://a/1"), pair!!.first)
        assertEquals(Uri.parse("content://a/2"), pair.second)
    }

    @Test
    fun classifyRejectsWrongCounts() {
        assertNull("两张图不成对", ShareIntents.classify(listOf(
            e("content://a/1", "IMG_A.jpg", "image/jpeg"),
            e("content://a/2", "IMG_B.jpg", "image/jpeg"))))
        assertNull("缺视频", ShareIntents.classify(
            listOf(e("content://a/1", "IMG_A.jpg", "image/jpeg"))))
    }

    @Test
    fun classifyRejectsDifferentStems() {
        assertNull(ShareIntents.classify(listOf(
            e("content://a/1", "IMG_A.jpg", "image/jpeg"),
            e("content://a/2", "OTHER.mp4", "video/mp4"))))
    }

    @Test
    fun copyPairWritesCachePairAndRejectsJunk() {
        val app = ApplicationProvider.getApplicationContext<VliveApp>()
        val dir: File = Files.createTempDirectory("share_src").toFile()
        val srcImg = File(dir, "IMG_X.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val srcMp4 = File(dir, "IMG_X.mp4").apply { writeBytes(ByteArray(64) { 7 }) }

        val copied = ShareIntents.copyPair(
            app, Uri.fromFile(srcImg), Uri.fromFile(srcMp4), "IMG_X")
        assertNotNull(copied)
        assertEquals(4L, copied!!.first.length())
        assertEquals(64L, copied.second.length())
        assertTrue(copied.first.absolutePath.contains("shared"))

        // 视频过小（<8B）视为无效，拒绝成对
        val tinyMp4 = File(dir, "TINY.mp4").apply { writeBytes(byteArrayOf(1)) }
        assertNull(ShareIntents.copyPair(
            app, Uri.fromFile(srcImg), Uri.fromFile(tinyMp4), "TINY"))
    }
}
