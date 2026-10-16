package com.vliveconvert.app

import com.vliveconvert.app.core.JpegUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * JpegUtil.gpsCoordinates：从 EXIF GPS IFD 解出经纬度。
 *
 * vivo 相机把 GPS 坐标写成 SRATIONAL（type 10）——不是标准更常见的 RATIONAL（type 5），
 * 两种都必须支持；N/S、E/W 参考项决定正负号。此坐标用于导出时写入 MediaStore 的
 * latitude/longitude 列（相机的写入方式），让相册能显示转换产物的位置。
 */
class JpegUtilGpsTest {

    /** 构造带 EXIF GPS 的最小 JPEG：SOI + APP1(Exif) + EOI */
    private fun jpegWithGps(
        latRef: String,
        latDms: List<Pair<Long, Long>>,
        lonRef: String,
        lonDms: List<Pair<Long, Long>>,
        signed: Boolean = true
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf(0x4D, 0x4D))                       // "MM" 大端
        tiff.write(byteArrayOf(0x00, 0x2A))                       // TIFF magic
        tiff.write(byteArrayOf(0x00, 0x00, 0x00, 0x08))           // IFD0 @8

        // IFD0：1 项 GPSInfo(0x8825)，值=GIFD 偏移；next IFD=0
        // GIFD 紧跟 IFD0（8 + 2 + 12 + 4 = 26）
        tiff.write(byteArrayOf(0x00, 0x01))
        tiff.write(byteArrayOf(0x88.toByte(), 0x25, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1A))
        tiff.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        // GPS IFD：4 项 + next=0；IFD 占 26..79（count 2 + 4×12 + next 4），数据区从 80 起
        tiff.write(byteArrayOf(0x00, 0x04))
        fun entry(tag: Int, typ: Int, cnt: Int, valueOrOffset: Long) {
            tiff.write(byteArrayOf(
                ((tag shr 8) and 0xFF).toByte(), (tag and 0xFF).toByte(),
                ((typ shr 8) and 0xFF).toByte(), (typ and 0xFF).toByte(),
                ((cnt shr 24) and 0xFF).toByte(), ((cnt shr 16) and 0xFF).toByte(),
                ((cnt shr 8) and 0xFF).toByte(), (cnt and 0xFF).toByte(),
                ((valueOrOffset shr 24) and 0xFF).toByte(), ((valueOrOffset shr 16) and 0xFF).toByte(),
                ((valueOrOffset shr 8) and 0xFF).toByte(), (valueOrOffset and 0xFF).toByte()))
        }
        fun asciiInline(s: String): Long {
            // ASCII ≤4 字节内联进值字段："N" + 3×\0
            val b = s.toByteArray(Charsets.US_ASCII)
            var v = 0L
            for (i in 0 until 4) {
                val byte = if (i < b.size) b[i].toInt() and 0xFF else 0
                v = (v shl 8) or byte.toLong()
            }
            return v
        }
        entry(1, 2, 2, asciiInline(latRef))                       // GPSLatitudeRef
        val ratType = if (signed) 10 else 5
        entry(2, ratType, 3, 80L)                                 // GPSLatitude @80
        entry(3, 2, 2, asciiInline(lonRef))                       // GPSLongitudeRef
        entry(4, ratType, 3, 104L)                                // GPSLongitude @104
        tiff.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        // SRATIONAL/RATIONAL：8 字节一项（分子、分母各 4 字节大端）
        fun rational(n: Long, d: Long): ByteArray {
            fun i32(v: Long) = byteArrayOf(
                ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            return i32(n) + i32(d)
        }
        latDms.forEach { tiff.write(rational(it.first, it.second)) }   // @80，24 字节
        lonDms.forEach { tiff.write(rational(it.first, it.second)) }   // @104，24 字节

        val tiffBytes = tiff.toByteArray()
        // APP1 段：marker + 长度(2) + "Exif\0\0" + TIFF
        val payloadLen = 6 + tiffBytes.size + 2
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
        out.write(byteArrayOf(((payloadLen shr 8) and 0xFF).toByte(), (payloadLen and 0xFF).toByte()))
        out.write(byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00))    // "Exif\0\0"
        out.write(tiffBytes)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))          // EOI
        return out.toByteArray()
    }

    /** vivo 同款：SRATIONAL 度分秒（22°16′51.5″N, 108°56′50″E ≈ 灵山陆屋） */
    @Test
    fun parsesVivoStyleSignedRationalNorthEast() {
        val jpeg = jpegWithGps("N", listOf(22L to 1L, 16L to 1L, 103L to 2L),
            "E", listOf(108L to 1L, 56L to 1L, 50L to 1L))
        val (lat, lon) = JpegUtil.gpsCoordinates(jpeg)!!
        assertEquals(22.0 + 16.0 / 60 + 51.5 / 3600, lat, 1e-9)
        assertEquals(108.0 + 56.0 / 60 + 50.0 / 3600, lon, 1e-9)
    }

    /** 标准无符号 RATIONAL（type 5）同样支持 */
    @Test
    fun parsesUnsignedRational() {
        val jpeg = jpegWithGps("N", listOf(39L to 1L, 54L to 1L, 0L to 1L),
            "E", listOf(116L to 1L, 23L to 1L, 0L to 1L), signed = false)
        val (lat, lon) = JpegUtil.gpsCoordinates(jpeg)!!
        assertEquals(39.9, lat, 1e-9)
        assertEquals(116.383333333, lon, 1e-6)
    }

    /** 南纬/西经取负 */
    @Test
    fun southAndWestRefsNegate() {
        val jpeg = jpegWithGps("S", listOf(33L to 1L, 0L to 1L, 0L to 1L),
            "W", listOf(70L to 1L, 0L to 1L, 0L to 1L))
        val (lat, lon) = JpegUtil.gpsCoordinates(jpeg)!!
        assertEquals(-33.0, lat, 1e-9)
        assertEquals(-70.0, lon, 1e-9)
    }

    /** 无 EXIF / 无 GPS IFD → null（不允许抛异常） */
    @Test
    fun returnsNullWhenGpsAbsent() {
        val bare = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        assertNull(JpegUtil.gpsCoordinates(bare))
    }

    /** 全零坐标（相机无定位时写 0,0）不算有效位置，返回 null */
    @Test
    fun zeroedCoordinatesAreNotUsableLocation() {
        val jpeg = jpegWithGps("N", listOf(0L to 1L, 0L to 1L, 0L to 1L),
            "E", listOf(0L to 1L, 0L to 1L, 0L to 1L))
        assertNull(JpegUtil.gpsCoordinates(jpeg))
    }
}
