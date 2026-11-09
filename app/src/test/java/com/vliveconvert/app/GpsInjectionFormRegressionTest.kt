package com.vliveconvert.app

import androidx.exifinterface.media.ExifInterface
import com.vliveconvert.app.convert.GpsRecovery
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.FooterUtil
import com.vliveconvert.app.core.JpegUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * A/B/C 三形态的 GPS 注入常驻回归（小型合成夹具，不依赖真机大样本）。
 *
 * 三种生产形态（真机结构等价，只是规模缩小）：
 * - **A**：EXIF 存在、GPS IFD 非空，但只有 GPSVersionID + LatRef（解不出经纬度）
 *   —— v2.7.0「有位置却零星丢位置」的源形态
 * - **B**：EXIF 存在、IFD0 无 GPSInfo 指针（无 GPS IFD）
 * - **C**：完全没有 EXIF APP1（仅 SOI 之后直接是其它段）
 *
 * 每种形态都走真实 [GpsRecovery.injectGpsExif]，断言：
 * 1. 注入后 **EXIF APP1 之后** 的区域与输入逐字节相等（GainMap / streamdata / footer 无损）；
 * 2. 除 EXIF APP1 外的段（marker + payload）序列逐字节相等；
 * 3. [ExifInterface] 与测试内**独立 TIFF 解码器**都能读出目标坐标；
 * 4. GPS IFD 含必需项 GPSVersionID(0x0000)：新写入为 BYTE[4]=02 03 00 00，
 *    A 形态源里已有的 02 02 00 00 不得被覆盖。
 *
 * 升级 androidx.exifinterface 后，若它开始改写其它段、丢版本号或改变 GPS 编码，
 * 本用例会直接红——这是本用例存在的意义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsInjectionFormRegressionTest {

    private enum class Form { A, B, C }

    private val targetLat = 22.8217
    private val targetLon = 108.3529
    private val liveId = "0123456789abcdef0123456789ab"
    private val gainMapSize = 32768

    private val version2300 = byteArrayOf(0x02, 0x03, 0x00, 0x00)
    private val sourceVersionA = byteArrayOf(0x02, 0x02, 0x00, 0x00)

    // ------------------------------------------------ 用例

    @Test
    fun formA_junkGpsIfdGetsCoordinatesAndKeepsSourceVersion() {
        val source = buildSource(Form.A)
        // 前置事实：这就是「GPS 段非空但不可用」的形态
        assertTrue("前置：A 形态确有 GPS IFD", JpegUtil.hasGpsExif(source))
        assertNull("前置：但它解不出经纬度", JpegUtil.gpsCoordinates(source))

        assertCommon(source, inject(source), sourceVersionA)
    }

    @Test
    fun formB_exifWithoutGpsIfdGetsCoordinatesAndVersionId() {
        val source = buildSource(Form.B)
        assertFalse("前置：B 形态无 GPS IFD", JpegUtil.hasGpsExif(source))

        assertCommon(source, inject(source), version2300)
    }

    @Test
    fun formC_noExifGetsNewExifAndPreservesEverythingAfter() {
        val source = buildSource(Form.C)
        assertNull("前置：C 形态连 EXIF APP1 都没有", findExifApp1(source))

        assertCommon(source, inject(source), version2300)
    }

    // ------------------------------------------------ 公共断言

    private fun inject(source: ByteArray): ByteArray {
        return when (val result = GpsRecovery.injectGpsExif(source, targetLat to targetLon)) {
            is GpsRecovery.InjectResult.Ok -> result.bytes
            is GpsRecovery.InjectResult.Fail -> throw AssertionError("注入必须成功：${result.reason}")
        }
    }

    private fun assertCommon(source: ByteArray, output: ByteArray, expectedVersion: ByteArray) {
        // ① EXIF APP1 之后的区域与输入逐字节相等（尾部容器无损的第一道硬断言）
        val inAfter = exifApp1End(source)
        val outAfter = exifApp1End(output)
        val inRegion = source.copyOfRange(inAfter, source.size)
        val outRegion = output.copyOfRange(outAfter, output.size)
        assertEquals("APP1 之后区域长度", inRegion.size, outRegion.size)
        assertTrue(
            "APP1 之后必须逐字节相等（GainMap / streamdata / footer 不得被改写）",
            inRegion.contentEquals(outRegion))

        // ② 非 EXIF APP1 段的 payload 序列相等
        val inSegs = segmentsExceptExif(source)
        val outSegs = segmentsExceptExif(output)
        assertEquals("非 EXIF APP1 段数", inSegs.size, outSegs.size)
        for (i in inSegs.indices) {
            assertEquals("第 $i 段 marker", inSegs[i].first, outSegs[i].first)
            assertTrue(
                "第 $i 段 payload 必须逐字节相等",
                inSegs[i].second.contentEquals(outSegs[i].second))
        }

        // 尾部仍可被生产解析器读出（字节相等之外的语义复核）
        val footer = FooterUtil.parseFooter(output)
        assertNotNull("footer 应仍可解析", footer)
        assertEquals(liveId, footer!!.livephotoId)

        // ③ ExifInterface 能读出目标坐标
        val ef = ExifInterface(ByteArrayInputStream(output))
        val latLong = ef.latLong
        assertNotNull("ExifInterface 必须读出经纬度", latLong)
        assertEquals(targetLat, latLong!![0], 1e-4)
        assertEquals(targetLon, latLong[1], 1e-4)

        // ④ 独立解码（不经 androidx）读出同一坐标 + GPSVersionID 结构
        val parsed = parseGpsIndependently(output)
        assertNotNull("独立 TIFF 解码必须读出 GPS IFD", parsed)
        assertEquals("GPSVersionID 类型应为 BYTE(1)", 1, parsed!!.versionType)
        assertEquals("GPSVersionID 计数应为 4", 4, parsed.versionCount)
        assertArrayEquals("GPSVersionID 字节", expectedVersion, parsed.versionBytes)
        assertEquals(targetLat, parsed.lat, 1e-4)
        assertEquals(targetLon, parsed.lon, 1e-4)

        val efVersion = ef.getAttributeBytes(ExifInterface.TAG_GPS_VERSION_ID)
        assertNotNull("ExifInterface 必须读到 GPSVersionID", efVersion)
        assertArrayEquals("ExifInterface 读回的版本字节", expectedVersion, efVersion!!)
    }

    // ------------------------------------------------ JPEG 结构工具

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val seg = ByteArray(4 + payload.size)
        seg[0] = 0xFF.toByte()
        seg[1] = marker.toByte()
        BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
        System.arraycopy(payload, 0, seg, 4, payload.size)
        return seg
    }

    private fun exifApp1(tiff: ByteArray): ByteArray =
        segment(0xE1, JpegUtil.exifPrefix + tiff)

    private fun findExifApp1(data: ByteArray): Pair<Int, Int>? {
        for (seg in JpegUtil.iterateSegments(data)) {
            val m = seg.marker.toInt() and 0xFF
            if (m == 0xE1 && seg.payloadLen > JpegUtil.exifPrefix.size &&
                BinaryUtils.arrayEquals(data, seg.payloadStart, JpegUtil.exifPrefix)
            ) {
                return (seg.payloadStart + JpegUtil.exifPrefix.size) to
                    (seg.payloadStart + seg.payloadLen)
            }
            if (m == 0xDA) break
        }
        return null
    }

    /** 返回可以「从该处起与输入逐字节比较」的偏移：有 EXIF APP1 时为其段尾，否则为 SOI 之后。 */
    private fun exifApp1End(data: ByteArray): Int {
        for (seg in JpegUtil.iterateSegments(data)) {
            val m = seg.marker.toInt() and 0xFF
            if (m == 0xE1 && seg.payloadLen > JpegUtil.exifPrefix.size &&
                BinaryUtils.arrayEquals(data, seg.payloadStart, JpegUtil.exifPrefix)
            ) {
                return seg.segStart + seg.totalLen
            }
            if (m == 0xDA) break
        }
        return 2
    }

    private fun segmentsExceptExif(data: ByteArray): List<Pair<Int, ByteArray>> =
        JpegUtil.iterateSegments(data).mapNotNull { seg ->
            val m = seg.marker.toInt() and 0xFF
            val isExif = m == 0xE1 && seg.payloadLen > JpegUtil.exifPrefix.size &&
                BinaryUtils.arrayEquals(data, seg.payloadStart, JpegUtil.exifPrefix)
            if (isExif) null
            else m to data.copyOfRange(seg.payloadStart, seg.payloadStart + seg.payloadLen)
        }.toList()

    // ------------------------------------------------ 独立 TIFF/GPS 解码（不经 androidx）

    private class ParsedGps(
        val lat: Double,
        val lon: Double,
        val versionType: Int,
        val versionCount: Int,
        val versionBytes: ByteArray
    )

    private fun parseGpsIndependently(jpeg: ByteArray): ParsedGps? {
        val (tiffStart, tiffEnd) = findExifApp1(jpeg) ?: return null
        if (tiffStart + 8 > tiffEnd) return null

        val little = when {
            jpeg[tiffStart] == 0x49.toByte() && jpeg[tiffStart + 1] == 0x49.toByte() -> true
            jpeg[tiffStart] == 0x4D.toByte() && jpeg[tiffStart + 1] == 0x4D.toByte() -> false
            else -> return null
        }

        fun u16(off: Int): Int {
            if (off < 0 || off + 2 > tiffEnd) return -1
            return if (little)
                (jpeg[off].toInt() and 0xFF) or ((jpeg[off + 1].toInt() and 0xFF) shl 8)
            else
                ((jpeg[off].toInt() and 0xFF) shl 8) or (jpeg[off + 1].toInt() and 0xFF)
        }

        fun u32(off: Int): Long {
            if (off < 0 || off + 4 > tiffEnd) return -1L
            return if (little)
                (jpeg[off].toInt() and 0xFF).toLong() or
                    ((jpeg[off + 1].toInt() and 0xFF).toLong() shl 8) or
                    ((jpeg[off + 2].toInt() and 0xFF).toLong() shl 16) or
                    ((jpeg[off + 3].toInt() and 0xFF).toLong() shl 24)
            else
                ((jpeg[off].toInt() and 0xFF).toLong() shl 24) or
                    ((jpeg[off + 1].toInt() and 0xFF).toLong() shl 16) or
                    ((jpeg[off + 2].toInt() and 0xFF).toLong() shl 8) or
                    (jpeg[off + 3].toInt() and 0xFF).toLong()
        }

        fun rationals(entry: Int, count: Int): List<Double>? {
            val type = u16(entry + 2)
            if (type != 5 && type != 10) return null
            val signed = type == 10
            val base = if (count * 8 > 4) tiffStart + u32(entry + 8).toInt() else entry + 8
            val out = ArrayList<Double>(count)
            for (k in 0 until count) {
                val p = base + k * 8
                if (p + 8 > tiffEnd) return null
                val n = u32(p)
                val d = u32(p + 4)
                if (d == 0L) {
                    out.add(0.0)
                    continue
                }
                val v = if (signed && n > Int.MAX_VALUE.toLong())
                    (n - (1L shl 32)).toDouble() / d.toDouble()
                else n.toDouble() / d.toDouble()
                out.add(v)
            }
            return out
        }

        val ifd0 = tiffStart + u32(tiffStart + 4).toInt()
        val ifd0Count = u16(ifd0)
        if (ifd0Count <= 0) return null
        var gpsIfd = -1
        for (i in 0 until ifd0Count) {
            val e = ifd0 + 2 + i * 12
            if (e + 12 > tiffEnd) return null
            if (u16(e) == 0x8825) {
                gpsIfd = tiffStart + u32(e + 8).toInt()
                break
            }
        }
        if (gpsIfd < 0) return null

        val gpsCount = u16(gpsIfd)
        if (gpsCount <= 0) return null
        var versionType = -1
        var versionCount = -1
        var versionBytes: ByteArray? = null
        var latRef = 'N'
        var lonRef = 'E'
        var latDms: List<Double>? = null
        var lonDms: List<Double>? = null
        for (g in 0 until gpsCount) {
            val e = gpsIfd + 2 + g * 12
            if (e + 12 > tiffEnd) return null
            when (u16(e)) {
                0x0000 -> {
                    versionType = u16(e + 2)
                    versionCount = u32(e + 4).toInt()
                    // 本项目写入的版本是 BYTE[4]，总长 4 → 值内联在 entry+8
                    versionBytes = jpeg.copyOfRange(e + 8, e + 12)
                }
                0x0001 -> {
                    val c = jpeg[e + 8].toInt().toChar()
                    if (c == 'N' || c == 'S') latRef = c
                }
                0x0003 -> {
                    val c = jpeg[e + 8].toInt().toChar()
                    if (c == 'E' || c == 'W') lonRef = c
                }
                0x0002 -> latDms = rationals(e, u32(e + 4).toInt())
                0x0004 -> lonDms = rationals(e, u32(e + 4).toInt())
            }
        }
        val vBytes = versionBytes ?: return null
        val latD = latDms?.takeIf { it.size == 3 } ?: return null
        val lonD = lonDms?.takeIf { it.size == 3 } ?: return null
        var lat = latD[0] + latD[1] / 60.0 + latD[2] / 3600.0
        var lon = lonD[0] + lonD[1] / 60.0 + lonD[2] / 3600.0
        if (latRef == 'S') lat = -lat
        if (lonRef == 'W') lon = -lon
        return ParsedGps(lat, lon, versionType, versionCount, vBytes)
    }

    // ------------------------------------------------ 合成夹具（真机结构等价）

    private fun buildSource(form: Form): ByteArray {
        val pieces = ArrayList<ByteArray>()
        pieces.add(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI

        when (form) {
            Form.A -> pieces.add(exifApp1(tiffFormA()))
            Form.B -> pieces.add(exifApp1(tiffFormB()))
            Form.C -> Unit
        }

        // APP6（vivo 私有段之一）
        pieces.add(segment(0xE6,
            "vivo-app6".toByteArray(Charsets.ISO_8859_1) + pseudoRandom(64, 33)))

        // XMP APP1（含 GainMap 的 Item:Length，结构等价于真机）
        pieces.add(JpegUtil.buildXmpApp1(xmpText()))

        // MPF APP2：先占位，待主图长度确定后回填 offset（段长不变，位置不漂）
        val mpfIndex = pieces.size
        pieces.add(buildMpfSegment(0, 0, 0))

        // ICC APP2
        pieces.add(segment(0xE2,
            "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + pseudoRandom(128, 77)))

        // 9 个 65,406 B 的假 vivo 私有段
        repeat(9) { i ->
            pieces.add(segment(0xE2,
                "vivo-private-$i\u0000".toByteArray(Charsets.ISO_8859_1) +
                    pseudoRandom(65406, 100 + i)))
        }

        pieces.add(segment(0xDB, byteArrayOf(0) + pseudoRandom(64, 5)))       // DQT
        pieces.add(segment(0xC0, byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0))) // SOF0
        pieces.add(segment(0xC4, byteArrayOf(0) + pseudoRandom(16, 6)))       // DHT
        pieces.add(segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)))           // SOS
        pieces.add(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x12, 0x34, 0x56))
        pieces.add(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))                 // EOI

        val assembled = concat(pieces)
        val mpfHeaderAbs = pieces.subList(0, mpfIndex).sumOf { it.size } + 8
        val gainMap = gainMapJpeg()
        val gainMapStart = assembled.size
        pieces[mpfIndex] = buildMpfSegment(assembled.size, gainMapStart - mpfHeaderAbs, gainMap.size)
        val primary = concat(pieces)
        check(primary.size == assembled.size) { "MPF 段长度必须与占位一致" }

        val streamData = "streamdata".toByteArray(Charsets.US_ASCII) +
            "DEGS".toByteArray(Charsets.US_ASCII) + pseudoRandom(64, 11)
        val footer = FooterUtil.buildFooter(
            FooterUtil.buildFooterJson(linkedMapOf(
                "com.android.camera.imageTime" to 12L,
                "com.android.camera.livephoto" to liveId,
                "version" to 2107
            )), liveId, FooterUtil.vivoPrefix)

        return primary + gainMap + streamData + footer
    }

    /** A：GPS IFD 非空但只有 VersionID(02 02 00 00) + LatRef("N")，无经纬度三元组。 */
    private fun tiffFormA(): ByteArray {
        val tiff = ByteArray(56)
        tiff[0] = 0x4D; tiff[1] = 0x4D // MM
        BinaryUtils.writeU16BE(tiff, 2, 42)
        BinaryUtils.writeU32BE(tiff, 4, 8L)
        // IFD0 @8: 1 entry -> 0x8825 GPSInfo = @26
        BinaryUtils.writeU16BE(tiff, 8, 1)
        BinaryUtils.writeU16BE(tiff, 10, 0x8825)
        BinaryUtils.writeU16BE(tiff, 12, 4)   // LONG
        BinaryUtils.writeU32BE(tiff, 14, 1L)
        BinaryUtils.writeU32BE(tiff, 18, 26L)
        BinaryUtils.writeU32BE(tiff, 22, 0L)  // next IFD
        // GPS IFD @26: 2 entries
        BinaryUtils.writeU16BE(tiff, 26, 2)
        // 0x0000 VersionID: BYTE[4] = 02 02 00 00（有意的非默认值，验证不被覆盖）
        BinaryUtils.writeU16BE(tiff, 28, 0x0000)
        BinaryUtils.writeU16BE(tiff, 30, 1)   // BYTE
        BinaryUtils.writeU32BE(tiff, 32, 4L)
        BinaryUtils.writeU32BE(tiff, 36, 0x02020000L)
        // 0x0001 LatRef: ASCII[2] "N\0"（内联）
        BinaryUtils.writeU16BE(tiff, 40, 0x0001)
        BinaryUtils.writeU16BE(tiff, 42, 2)   // ASCII
        BinaryUtils.writeU32BE(tiff, 44, 2L)
        tiff[48] = 'N'.code.toByte()
        BinaryUtils.writeU32BE(tiff, 52, 0L)  // GPS next IFD
        return tiff
    }

    /** B：有 EXIF，但 IFD0 只有 Orientation，无 GPSInfo 指针。 */
    private fun tiffFormB(): ByteArray {
        val tiff = ByteArray(26)
        tiff[0] = 0x4D; tiff[1] = 0x4D
        BinaryUtils.writeU16BE(tiff, 2, 42)
        BinaryUtils.writeU32BE(tiff, 4, 8L)
        BinaryUtils.writeU16BE(tiff, 8, 1)
        BinaryUtils.writeU16BE(tiff, 10, 0x0112) // Orientation
        BinaryUtils.writeU16BE(tiff, 12, 3)      // SHORT
        BinaryUtils.writeU32BE(tiff, 14, 1L)
        BinaryUtils.writeU32BE(tiff, 18, 1L)     // 值 1（内联 4 字节）
        BinaryUtils.writeU32BE(tiff, 22, 0L)     // next IFD
        return tiff
    }

    private fun buildMpfSegment(image0Size: Int, image1Offset: Int, image1Size: Int): ByteArray {
        val tiff = ByteArray(82)
        tiff[0] = 0x4D; tiff[1] = 0x4D
        BinaryUtils.writeU16BE(tiff, 2, 42)
        BinaryUtils.writeU32BE(tiff, 4, 8L)
        BinaryUtils.writeU16BE(tiff, 8, 3) // IFD0: 3 entries
        // 0xB000 MPFVersion UNDEFINED[4] = "0100"
        BinaryUtils.writeU16BE(tiff, 10, 0xB000)
        BinaryUtils.writeU16BE(tiff, 12, 7)
        BinaryUtils.writeU32BE(tiff, 14, 4L)
        tiff[18] = 0x30; tiff[19] = 0x31; tiff[20] = 0x30; tiff[21] = 0x30
        // 0xB001 NumberOfImages LONG = 2
        BinaryUtils.writeU16BE(tiff, 22, 0xB001)
        BinaryUtils.writeU16BE(tiff, 24, 4)
        BinaryUtils.writeU32BE(tiff, 26, 1L)
        BinaryUtils.writeU32BE(tiff, 30, 2L)
        // 0xB002 MPEntry UNDEFINED count=32（字节数）→ 数组 @50
        BinaryUtils.writeU16BE(tiff, 34, 0xB002)
        BinaryUtils.writeU16BE(tiff, 36, 7)
        BinaryUtils.writeU32BE(tiff, 38, 32L)
        BinaryUtils.writeU32BE(tiff, 42, 50L)
        BinaryUtils.writeU32BE(tiff, 46, 0L)
        // image0：attrs / size / offset(0) / dep / dep
        BinaryUtils.writeU32BE(tiff, 50, 0x00030000L)
        BinaryUtils.writeU32BE(tiff, 54, image0Size.toLong())
        BinaryUtils.writeU32BE(tiff, 58, 0L)
        // image1（GainMap）：offset 相对 MP 头
        BinaryUtils.writeU32BE(tiff, 66, 0L)
        BinaryUtils.writeU32BE(tiff, 70, image1Size.toLong())
        BinaryUtils.writeU32BE(tiff, 74, image1Offset.toLong())
        return segment(0xE2, "MPF\u0000".toByteArray(Charsets.ISO_8859_1) + tiff)
    }

    private fun gainMapJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(segment(0xC0, byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0)))
        out.write(segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)))
        out.write(pseudoRandom(gainMapSize, 99))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun xmpText(): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
            """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
            """xmlns:Container="http://ns.google.com/photos/1.0/container/" """ +
            """xmlns:Item="http://ns.google.com/photos/1.0/container/item/" """ +
            """xmlns:GCamera="http://ns.google.com/photos/1.0/camera/">""" +
            """<rdf:Description GCamera:MotionPhoto="1">""" +
            """<Container:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" """ +
            """Item:Semantic="GainMap" Item:Length="$gainMapSize" Item:Padding="0"/>""" +
            """</rdf:li></rdf:Seq></Container:Directory>""" +
            """</rdf:Description></rdf:RDF></x:xmpmeta>"""

    private fun pseudoRandom(size: Int, seed: Int): ByteArray {
        val b = ByteArray(size)
        var s = seed.toLong()
        for (i in 0 until size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            b[i] = (s ushr 33).toByte()
        }
        return b
    }

    private fun concat(pieces: List<ByteArray>): ByteArray {
        val out = ByteArray(pieces.sumOf { it.size })
        var pos = 0
        for (p in pieces) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }
}
