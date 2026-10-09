package com.vliveconvert.app

import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.XmpTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「文件是否为单文件实况」判别的 JVM 测试。
 *
 * 用途是把输出目录里的「本工具产物」与无关照片区分开——「把输出移到相机相册」只应移动前者。
 * 判别条件 = XMP 含 `GCamera:MotionPhoto` / `MicroVideo` 标记；源双文件的 XMP 恰恰不含该标记，
 * 故与 MediaExport 识别「既往产物」用的是同一条件。
 */
class XmpMotionDetectionTest {

    private fun jpegWithXmp(xmp: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(xmp) +
            ByteArray(32) { 0x11 } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun xmp(attrs: String): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
            """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
            """xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" """ +
            attrs + """></rdf:RDF></x:xmpmeta>"""

    @Test
    fun distinguishesConvertedOutputsFromOrdinaryPhotos() {
        val dir = Files.createTempDirectory("vlc_xmp").toFile()
        val motion = File(dir, "IMG_MOTION.jpg").apply {
            writeBytes(jpegWithXmp(xmp("""GCamera:MotionPhoto="1"""")))
        }
        val micro = File(dir, "IMG_MICRO.jpg").apply {
            writeBytes(jpegWithXmp(xmp("""GCamera:MicroVideo="1"""")))
        }
        // 源双文件的 XMP：无任何动态照片标记（正是本工具识别双文件的条件）
        val plain = File(dir, "IMG_PLAIN.jpg").apply {
            writeBytes(jpegWithXmp(xmp("""hdrgm:Version="1.0"""")))
        }
        // 连 XMP 段都没有
        val noXmp = File(dir, "IMG_NOXMP.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
                ByteArray(64) { 0x22 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        }
        val missing = File(dir, "NO_SUCH_FILE.jpg")

        assertTrue("MotionPhoto=1 → 本工具产物", XmpTemplate.isMotionPhoto(motion.path))
        assertTrue("MicroVideo=1 → 本工具产物", XmpTemplate.isMotionPhoto(micro.path))
        assertFalse("普通 XMP（无标记）→ 不应判为产物", XmpTemplate.isMotionPhoto(plain.path))
        assertFalse("无 XMP 段 → 不应判为产物", XmpTemplate.isMotionPhoto(noXmp.path))
        assertFalse("文件不存在 → 安全返回 false", XmpTemplate.isMotionPhoto(missing.path))
    }
}
