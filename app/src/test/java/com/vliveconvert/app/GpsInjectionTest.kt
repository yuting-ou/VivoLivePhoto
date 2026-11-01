package com.vliveconvert.app

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

/**
 * 位置保障原语（v2.6.0-test27 加固）：
 *
 * - **注入写 EXIF**：[GpsRecovery.injectGpsExif] 切 JPEG 处理、尾部（GainMap/streamdata/
 *   视频段/footer）逐字节保留；失败返回带原因的 Fail（显式，不再静默）
 * - **坐标恢复**：[GpsRecovery.recoverFromMp4Bytes] 先在 ©xyz 盒子窗口内找、
 *   再全量扫兜底，全部命中都过**范围校验**（纬度 ±90、经度 ±180）——二进制误命中
 *   产生的超范围坐标不会再流进 EXIF（它曾让注入整批静默失败）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsInjectionTest {

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

    private fun mp4WithXyz(coords: String): ByteArray =
        ByteArray(64) { 0x00 } +
            byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            coords.toByteArray() + ByteArray(32) { 0x00 }

    /** 注入后：EXIF 可解析出坐标、字节尾部（footer/视频段）逐字节保留 */
    @Test
    fun injectWritesExifAndPreservesTail() {
        val tail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        val source = jpegWithXmpGpsOnly() + tail

        val result = GpsRecovery.injectGpsExif(source, 22.8389 to 108.1780)

        assertTrue("注入应成功", result is GpsRecovery.InjectResult.Ok)
        val injected = (result as GpsRecovery.InjectResult.Ok).bytes
        val coords = JpegUtil.gpsCoordinates(injected)
        assertNotNull("注入后 EXIF 应带坐标", coords)
        assertEquals(22.8389, coords!!.first, 1e-4)
        assertEquals(108.1780, coords.second, 1e-4)
        assertTrue(
            "EOI 之后的尾部（footer/视频段）必须逐字节保留",
            injected.size > source.size - 4096 &&
                injected.takeLast(tail.size) == tail.toList())
    }

    /** 注入是可验证的：写后自检读回坐标（防「写进去的不是我们要的」） */
    @Test
    fun injectIsVerifiable() {
        val result = GpsRecovery.injectGpsExif(jpegWithXmpGpsOnly(), 31.0 to 121.0)
        assertTrue(result is GpsRecovery.InjectResult.Ok)
        val coords = JpegUtil.gpsCoordinates((result as GpsRecovery.InjectResult.Ok).bytes)
        assertEquals(31.0, coords!!.first, 1e-4)
    }

    /** 注入失败显式化：非 JPEG 数据返回带原因的 Fail，不再静默吞掉 */
    @Test
    fun injectFailureIsExplicitWithReason() {
        val result = GpsRecovery.injectGpsExif(
            byteArrayOf(0x11, 0x22, 0x33, 0x44), 22.0 to 108.0)
        assertTrue("垃圾数据必须失败", result is GpsRecovery.InjectResult.Fail)
        val reason = (result as GpsRecovery.InjectResult.Fail).reason
        assertTrue("失败必须带可读原因", reason.isNotBlank())
    }

    /** ©xyz 盒子窗口内恢复坐标（vivo 标准写入位置） */
    @Test
    fun recoversCoordsFromMp4XyzBox() {
        val coords = GpsRecovery.recoverFromMp4Bytes(mp4WithXyz("+22.8389+108.1780/"))
        assertEquals(22.8389, coords!!.first, 1e-9)
        assertEquals(108.1780, coords.second, 1e-9)
    }

    /** 无 ©xyz 且无坐标串：返回 null（不抛异常、不编造） */
    @Test
    fun mp4WithoutCoordsReturnsNull() {
        assertNull(GpsRecovery.recoverFromMp4Bytes(ByteArray(128) { 0x11 }))
        assertNull(GpsRecovery.recoverFromMp4Bytes(ByteArray(0)))
    }

    /** 范围校验：纬度超 ±90 的「命中」是二进制误命中，必须拒绝（曾为注入失败根因） */
    @Test
    fun outOfRangeLatitudeIsRejected() {
        // 纬度 322°：合法字符串形态但地理非法
        assertNull(GpsRecovery.recoverFromMp4Bytes(mp4WithXyz("+322.8389+108.1780/")))
        // 经度超 ±180 同样拒绝
        assertNull(GpsRecovery.recoverFromMp4Bytes(mp4WithXyz("+22.8389+208.1780/")))
    }

    /** 0,0 是无位置（海上原点），不是坐标 */
    @Test
    fun zeroZeroIsRejected() {
        assertNull(GpsRecovery.recoverFromMp4Bytes(mp4WithXyz("+00.0000+000.0000/")))
    }

    /** 边界值合法：极点与对蹠经线必须能通过 */
    @Test
    fun boundaryValuesAreAccepted() {
        val c1 = GpsRecovery.recoverFromMp4Bytes(mp4WithXyz("+90.0000+180.0000/"))
        assertEquals(90.0, c1!!.first, 1e-9)
        assertEquals(180.0, c1.second, 1e-9)
        val c2 = GpsRecovery.recoverFromMp4Bytes(mp4WithXyz("-90.0000-180.0000/"))
        assertEquals(-90.0, c2!!.first, 1e-9)
        assertEquals(-180.0, c2.second, 1e-9)
    }

    /** 误命中不挡真坐标：前面有超范围假命中时，继续找后面合法的 ©xyz */
    @Test
    fun invalidMatchDoesNotShadowValidOne() {
        val data = "+322.0000+108.0000/".toByteArray() + ByteArray(16) { 0x55 } +
            mp4WithXyz("+22.8389+108.1780/")
        val coords = GpsRecovery.recoverFromMp4Bytes(data)
        assertEquals(22.8389, coords!!.first, 1e-9)
    }
}
