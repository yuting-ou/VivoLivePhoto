package com.vliveconvert.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SegmentDigest
import com.vliveconvert.app.core.SingleWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 「重复转换不累积 `IMG_x(1)/(2)/(3)…`」的端到端测试（Robolectric + MediaStore）。
 *
 * 背景：`MediaExport.exportAndVerify` 原先一律新建文件、重名时由 MediaStore 追加序号，
 * 用户重复转换同一批照片就会不断堆出 `(1)` `(2)` `(3)` 垃圾文件。
 * 现改为：目标目录若已存在**同一源照片的既往产物**（同名 + XMP 含 MotionPhoto），
 * 则原地覆盖。
 *
 * 本测试同时守住那条**安全攸关**的边界：源双文件（XMP 不含 MotionPhoto）
 * **绝不能**被当作既往产物覆盖，否则会丢原始数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicateOutputTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val collection
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 构造一个「看起来像转换产物」的 JPEG 头部：SOI + 带 MotionPhoto 的 XMP + EOI */
    private fun contentWithMotionPhoto(tag: ByteArray): ByteArray {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
            """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
            """xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" """ +
            """GCamera:MotionPhoto="1"></rdf:RDF></x:xmpmeta>"""
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(xmp) + tag +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** 把字节写入 MediaStore 指定目录下的指定文件名，返回 URI */
    private fun putMedia(relPath: String, name: String, bytes: ByteArray): Uri {
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        return uri
    }

    private fun countIn(relPath: String, likePattern: String? = null): Int {
        val sel: String
        val args: Array<String>
        if (likePattern == null) {
            sel = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?)"
            args = arrayOf("$relPath/", relPath)
        } else {
            sel = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?) AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            args = arrayOf("$relPath/", relPath, likePattern)
        }
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null)
            ?.use { it.count } ?: -1
    }

    private fun readBack(uri: Uri): ByteArray =
        resolver.openInputStream(uri)!!.use { it.readBytes() }

    /** 把「转换产物」写到临时目录并构造带摘要的 SingleWriteResult */
    private fun makeResult(bytes: ByteArray, stem: String): SingleWriteResult {
        val dir = Files.createTempDirectory("vlc_dup").toFile()
        val f = File(dir, "$stem.jpg").apply { writeBytes(bytes) }
        return SingleWriteResult(
            f.path,
            listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes)))
        )
    }

    /**
     * 核心场景：同一源照片再次转换时，应**原地覆盖**上次产物，而不是新增 `IMG_TEST(1).jpg`。
     */
    @Test
    fun repeatedConversionOverwritesPreviousOutputInsteadOfAccumulating() {
        val rel = "Pictures/TestOut"
        // 上次的转换产物（含 MotionPhoto 标记，可被识别为既往产物）
        val first = contentWithMotionPhoto(byteArrayOf(0x11))
        val firstUri = putMedia(rel, "IMG_TEST.jpg", first)
        assertEquals("初始应有 1 个产物", 1, countIn(rel))

        // 本次转换产物（内容不同，便于断言确实被替换）
        val second = contentWithMotionPhoto(byteArrayOf(0x22))
        val result = makeResult(second, "IMG_TEST")
        val returned = MediaExport.exportAndVerify(
            app, result, timestamp = 1_700_000_000_000L,
            relPath = rel, useCameraDir = false
        )

        assertEquals("应原地覆盖，而不是新增记录", 1, countIn(rel))
        assertEquals("应返回同一 URI（未新建文件）", firstUri, returned)
        assertTrue("文件内容应已替换为本次产物",
            readBack(returned).contentEquals(second))
    }

    /**
     * 安全边界：目标目录里的**源双文件**（同名但 XMP 不含 MotionPhoto）绝不能被覆盖，
     * 否则会丢原始数据；此时应新建一个带序号的记录。
     */
    @Test
    fun sourceDualFileIsNeverOverwritten() {
        val rel = "DCIM/Camera"
        // 源双文件的 JPEG：没有 MotionPhoto 标记（正是本工具识别双文件的条件）
        val sourceBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            ByteArray(64) { 0x5A } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val sourceUri = putMedia(rel, "IMG_SRC.jpg", sourceBytes)

        val result = makeResult(contentWithMotionPhoto(byteArrayOf(0x33)), "IMG_SRC")
        val returned = MediaExport.exportAndVerify(
            app, result, timestamp = 1_700_000_000_000L,
            relPath = rel, useCameraDir = true
        )

        assertNotEquals("不得覆盖源文件", sourceUri, returned)
        assertTrue("源文件内容必须原样保留",
            readBack(sourceUri).contentEquals(sourceBytes))
        assertTrue("应另建新文件", countIn(rel) >= 2)
        // 新建时沿用 MediaStore 序号命名（源文件占用了原名）
        val names = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?)",
            arrayOf("$rel/", rel), null
        )?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        } ?: emptyList()
        assertTrue("新增文件应带序号以免与源同名：$names",
            names.any { it.startsWith("IMG_SRC(") })
    }

    /** 目标目录无既往产物时应正常新建，不误覆盖无关文件 */
    @Test
    fun noPreviousOutputInsertsNewFile() {
        val rel = "Pictures/TestClean"
        val result = makeResult(contentWithMotionPhoto(byteArrayOf(0x44)), "IMG_FRESH")
        MediaExport.exportAndVerify(
            app, result, timestamp = 1_700_000_000_000L,
            relPath = rel, useCameraDir = false
        )
        assertEquals("无既往产物时应新建 1 个", 1, countIn(rel))
        assertTrue("文件名应为原名", countIn(rel, "IMG\\_FRESH.jpg") == 1)
    }
}
