package com.vliveconvert.app

import com.vliveconvert.app.convert.GpsRecovery
import com.vliveconvert.app.core.JpegUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从产物视频段恢复 GPS：vivo 相机在 mp4 的 udta/©xyz 原子写 ISO 6709 串
 * （实测 `+22.8389+108.1780/`），合并产物原样拼接源 mp4——老版本产物即使
 * JPEG 的 EXIF GPS 被系统剥掉，坐标仍在视频段里。
 */
class GpsRecoveryTest {

    /** vivo 实测样例：南宁 21:37 拍摄 */
    private val vivoXyzTail: ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x26, 0x75, 0x64, 0x74, 0x61) +            // udta box
            byteArrayOf(0x00, 0x00, 0x00, 0x1E) +                                 // ©xyz box
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +                        // "©xyz"
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +                        // 版本/flags
            "+22.8389+108.1780/".toByteArray() + byteArrayOf(0x00)

    /** 造一个「合并产物」：JPEG（SOI+XMP+EOI）+ 尾部（可含 ©xyz 视频段） */
    private fun mergedFile(tail: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF/></x:xmpmeta>") +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02) +               // SOS
            byteArrayOf(0x12, 0x34) +                                             // 熵编码数据
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + tail                      // EOI + 尾部

    @Test
    fun parsesVivoStylePositiveCoordinates() {
        val (lat, lon) = GpsRecovery.parseIso6709From(
            "+22.8389+108.1780/".toByteArray())!!
        assertEquals(22.8389, lat, 1e-9)
        assertEquals(108.1780, lon, 1e-9)
    }

    @Test
    fun negativeSignsMeanSouthAndWest() {
        val (lat, lon) = GpsRecovery.parseIso6709From(
            "-33.865000-70.123400/".toByteArray())!!
        assertEquals(-33.865, lat, 1e-9)
        assertEquals(-70.1234, lon, 1e-9)
    }

    @Test
    fun noCoordinateStringReturnsNull() {
        assertNull(GpsRecovery.parseIso6709From(byteArrayOf(1, 2, 3)))
        assertNull(GpsRecovery.parseIso6709From(
            "垃圾数据无坐标".toByteArray()))
    }

    /** 只搜视频段（EOI 之后）：JPEG 部分里出现的坐标串不算数（可能是残留文本） */
    @Test
    fun searchesOnlyAfterJpegEnd() {
        val fake = "+11.1111+22.2222/".toByteArray()
        val jpegWithFake = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(),              // COM 段装假坐标
                ((fake.size + 2) shr 8 and 0xFF).toByte(),
                ((fake.size + 2) and 0xFF).toByte()) + fake +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02) + // SOS
            byteArrayOf(0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) +
            "+33.3333+44.4444/".toByteArray()
        val (lat, lon) = GpsRecovery.recoverFromVideoSegment(jpegWithFake)!!
        assertEquals(33.3333, lat, 1e-9)
        assertEquals(44.4444, lon, 1e-9)
    }

    /** 端到端：合并产物（无 EXIF GPS）+ vivo 视频段 → 恢复出正确坐标 */
    @Test
    fun recoversFromMergedOutputVideoSegment() {
        val data = mergedFile(vivoXyzTail)
        val (lat, lon) = GpsRecovery.recoverFromVideoSegment(data)!!
        assertEquals(22.8389, lat, 1e-9)
        assertEquals(108.1780, lon, 1e-9)
    }

    /** 视频段没有 ©xyz 时返回 null（该文件恢复不了，不抛异常） */
    @Test
    fun tailWithoutGpsReturnsNull() {
        assertNull(GpsRecovery.recoverFromVideoSegment(mergedFile(ByteArray(32) { 0x5A })))
    }
}
