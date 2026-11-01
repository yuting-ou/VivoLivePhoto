package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.GpsRecovery
import com.vliveconvert.app.core.JpegUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 转换前位置预注入（零星丢位置根因的治本修复）：
 *
 * vivo 相册的媒体扫描按**文件 EXIF** 重算媒体库行的坐标列——文件无 EXIF GPS 时，
 * 坐标即使写进行也会被抹掉（「找回位置」行重建 200+ 次全存活，正因它先写回 EXIF；
 * 只写列的会被抹）。故源 EXIF 无 GPS 但 XMP / 伴生 mp4 ©xyz 有坐标时，
 * 必须先把 GPS 注入源副本 EXIF 再转换，产物天生带 EXIF GPS。
 *
 * 本类钉住注入原语与 mp4 扫描；「源 XMP 有 GPS」夹具复用 GCamera 约定形态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsInjectionTest {

    private val context get() = ApplicationProvider.getApplicationContext<VliveApp>()

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    private fun jpegWithXmpGpsOnly(): ByteArray {
        val gpsXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
            """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
            """xmlns:exif="http://ns.adobe.com/exif/1.0/" """ +
            """exif:GPSLatitude="22,50.2088N" exif:GPSLongitude="108,17.84E">""" +
            """</rdf:RDF></x:xmpmeta>"""
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(gpsXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** 注入后：EXIF 可解析出坐标、字节尾部（footer/视频段）逐字节保留 */
    @Test
    fun injectWritesExifAndPreservesTail() {
        val tail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        val source = jpegWithXmpGpsOnly() + tail

        val injected = GpsRecovery.injectGpsExif(
            context, source, 22.8389 to 108.1780)

        assertNotNull(injected)
        val coords = JpegUtil.gpsCoordinates(injected!!)
        assertNotNull("注入后 EXIF 应带坐标", coords)
        assertEquals(22.8389, coords!!.first, 1e-4)
        assertEquals(108.1780, coords.second, 1e-4)
        assertTrue(
            "EOI 之后的尾部（footer/视频段）必须逐字节保留",
            injected.size > source.size - 4096 &&
                injected.takeLast(tail.size) == tail.toList())
    }

    /** 无坐标来源的文件注入后应能解析（自检失败返回 null 而非半成品） */
    @Test
    fun injectIsVerifiable() {
        val injected = GpsRecovery.injectGpsExif(
            context, jpegWithXmpGpsOnly(), 31.0 to 121.0)
        assertNotNull(injected)
        val coords = JpegUtil.gpsCoordinates(injected!!)
        assertEquals(31.0, coords!!.first, 1e-4)
    }

    /** 独立伴生 mp4 的 ©xyz 扫描（转换前注入的第三来源） */
    @Test
    fun recoversCoordsFromStandaloneMp4() {
        val dir = Files_temp()
        val mp4 = File(dir, "IMG_X.mp4")
        mp4.writeBytes(
            ByteArray(64) { 0x00 } +
                byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
                byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
                byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
                "+22.8389+108.1780/".toByteArray() +
                ByteArray(32) { 0x00 })
        val coords = GpsRecovery.recoverFromMp4File(mp4.path)
        assertEquals(22.8389, coords!!.first, 1e-9)
        assertEquals(108.1780, coords.second, 1e-9)
        mp4.delete(); dir.delete()
    }

    @Test
    fun mp4WithoutCoordsReturnsNull() {
        val dir = Files_temp()
        val mp4 = File(dir, "IMG_Y.mp4")
        mp4.writeBytes(ByteArray(128) { 0x11 })
        assertNull(GpsRecovery.recoverFromMp4File(mp4.path))
        assertNull(GpsRecovery.recoverFromMp4File("/nonexistent/a.mp4"))
        mp4.delete(); dir.delete()
    }

    private fun Files_temp(): File =
        File.createTempFile("vlc_inj", "").parentFile
}
