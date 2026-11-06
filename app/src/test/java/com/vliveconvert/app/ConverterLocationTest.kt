package com.vliveconvert.app

import com.vliveconvert.app.convert.Converter
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.FooterUtil
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.XmpTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 转换管线的位置保障（v2.6.0-test27 治本重构）：
 *
 * - 源 EXIF 无 GPS 但 XMP / 伴生视频 ©xyz 有坐标 → **管线内内存注入**主图 EXIF
 *   （不再有 test25 的 cache 副本/符号链接静默失败点），产物天生带 EXIF GPS
 * - **源指纹对注入前的原始主图计算**：重转时既往产物指纹一致、可原地覆盖，
 *   不会生成 IMG_x(1).jpg 副本（test25 把指纹算在注入后字节上，是本类钉住的回归）
 * - 显式明细（gpsInjected / gpsInjectFailed / sourceHasLocation / outputHasExifGps）
 *   全部来自管线事实；Motion Photo 三支柱（XMP Container / lpex / footer）不被破坏
 *
 * 注：注入原语经 androidx ExifInterface 写 EXIF（依赖 android 框架），
 * 故本类跑在 Robolectric 下；纯 JVM 路径由 ConverterPipelineTest 覆盖
 * （库不可用时降级为显式失败，转换不中断）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConverterLocationTest {

    private val LIVE_ID = "0123456789abcdef0123456789ab" // 28 字符

    private fun log(level: String, msg: String, tag: String) = println("[$level][$tag] $msg")

    // ------------------------------------------------ 合成样本构造（与 ConverterPipelineTest 同源）

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArray(8 + payload.size)
        BinaryUtils.writeU32BE(b, 0, (payload.size + 8).toLong())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(t, 0, b, 4, 4)
        System.arraycopy(payload, 0, b, 8, payload.size)
        return b
    }

    /** 最小合法 JPEG 的「SOI 之后」部分：SOF0(16x16) + SOS + 熵数据 + EOI */
    private fun jpegBodyAfterSoi(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        run {
            val payload = byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0)
            val seg = ByteArray(2 + 2 + payload.size)
            seg[0] = 0xFF.toByte(); seg[1] = 0xC0.toByte()
            BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
            System.arraycopy(payload, 0, seg, 4, payload.size)
            out.write(seg)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0))
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x12, 0x34, 0x56))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    /** 带 exif GPS 属性的 XMP APP1（十进制 + Ref 形态） */
    private fun gpsXmpApp1(): ByteArray = JpegUtil.buildXmpApp1(
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
            """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
            """xmlns:exif="http://ns.adobe.com/exif/1.0/" """ +
            """exif:GPSLatitude="22.8389" exif:GPSLatitudeRef="N" """ +
            """exif:GPSLongitude="108.1780" exif:GPSLongitudeRef="E">""" +
            """</rdf:RDF></x:xmpmeta>""")

    /** EXIF GPS APP1（22°N, 108°E）：从共享夹具里取出 APP1 段 */
    private fun exifGpsApp1(): ByteArray {
        val jpeg = ExportLocationColumnsTest().jpegWithGps(22, 108)
        return jpeg.copyOfRange(2, jpeg.size - 2) // 去 SOI/EOI，剩 APP1
    }

    /** 主图：SOI + [EXIF?] + [XMP?] + SOF0 + SOS + 熵数据 + EOI */
    private fun primary(withExifGps: Boolean, xmpApp1: ByteArray?): ByteArray = primaryWithExif(
        if (withExifGps) exifGpsApp1() else null, xmpApp1)

    private fun primaryWithExif(exifApp1: ByteArray?, xmpApp1: ByteArray?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        exifApp1?.let { out.write(it) }
        xmpApp1?.let { out.write(it) }
        out.write(jpegBodyAfterSoi())
        return out.toByteArray()
    }

    /** GPS IFD **非空但没有经纬度三元组**（只有 GPSLatitudeRef="N"）——
     *  真机里「拍摄时有 GPS 段、却解不出坐标」的形态（refs-only / 全 0,0 / 字段残缺）。
     *  与 ConverterPipelineTest 的 tiffWithGpsRefOnly 同源：hasGpsExif=true、gpsCoordinates=null。 */
    private fun junkGpsExifApp1(): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0, 42, 0, 0, 0, 8,                    // big-endian MM / 42 / IFD0@8
            0, 1,                                              // 1 entry
            0x88.toByte(), 0x25, 0, 4, 0, 0, 0, 26,             // GPSInfoIFD -> @26
            0, 0, 0, 0,                                        // next IFD = 0
            0, 1,                                              // GPS IFD: 1 entry
            0, 1, 0, 2, 0, 0, 0, 2, 'N'.code.toByte(), 0,      // GPSLatitudeRef="N"
            0, 0, 0, 0                                         // next IFD = 0
        )
        val payload = "Exif\u0000\u0000".toByteArray() + tiff
        val seg = ByteArray(4 + payload.size)
        seg[0] = 0xFF.toByte(); seg[1] = 0xE1.toByte()
        BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
        System.arraycopy(payload, 0, seg, 4, payload.size)
        return seg
    }

    private fun pseudoRandom(size: Int, seed: Int): ByteArray {
        val b = ByteArray(size)
        var s = seed.toLong()
        for (i in 0 until size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            b[i] = (s ushr 33).toByte()
        }
        return b
    }

    private fun fakeStreamData(type: String, size: Int, seed: Int): ByteArray =
        "streamdata".toByteArray(Charsets.US_ASCII) +
            type.toByteArray(Charsets.US_ASCII) + pseudoRandom(size, seed)

    private fun vivoFooter(): ByteArray {
        val json = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to LIVE_ID,
            "version" to 2107
        ))
        return FooterUtil.buildFooter(json, LIVE_ID, FooterUtil.vivoPrefix)
    }

    private fun vivoDualJpeg(primaryBytes: ByteArray): ByteArray =
        primaryBytes + fakeStreamData("DEGS", 64, 11) + FooterUtil.vivoPrefix + vivoFooter()

    /** 合成伴生 MP4；[xyz] 非空时在 moov 里放 udta/©xyz 坐标盒 */
    private fun vivoDualMp4(xyz: String?): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() +
            byteArrayOf(0, 0, 2, 0) + "isom".toByteArray() + "mp41".toByteArray())
        val mdat = box("mdat", pseudoRandom(2048, 42))
        val xyzBox = xyz?.let {
            box("udta", box("©xyz", byteArrayOf(0, 0, 0, 0) + it.toByteArray()))
        } ?: ByteArray(0)
        val moov = box("moov", xyzBox)
        val eStream = box("uuid", "vivoMediaEStream".toByteArray(Charsets.US_ASCII) +
            pseudoRandom(122, 7))
        val extInfoJson = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to LIVE_ID,
            "version" to 2107
        ))
        val extInfo = box("uuid", "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII) +
            FooterUtil.buildFooter(extInfoJson, LIVE_ID, FooterUtil.vivoPrefix))
        return ftyp + mdat + moov + eStream + extInfo
    }

    private fun makePair(dir: File, stem: String, jpg: ByteArray, mp4: ByteArray): Pair<File, File> {
        val j = File(dir, "$stem.jpg").apply { writeBytes(jpg) }
        val m = File(dir, "$stem.mp4").apply { writeBytes(mp4) }
        return j to m
    }

    private fun sha16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)

    // ------------------------------------------------ 用例

    /** 核心场景：XMP 带坐标、EXIF 无 GPS 的源 → 管线注入，产物天生带 EXIF GPS，
     * 且指纹与「原始源」一致（重转能原地覆盖，不产生副本） */
    @Test
    fun xmpOnlySourceGetsExifInjectionAndStableFingerprint() {
        val dir = Files.createTempDirectory("vlc_loc_xmp").toFile()
        val primary = primary(withExifGps = false, xmpApp1 = gpsXmpApp1())
        val (jpg, _) = makePair(dir, "IMG_XMP",
            vivoDualJpeg(primary), vivoDualMp4(xyz = null))

        val outDir = Files.createTempDirectory("vlc_loc_out").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)

        assertTrue("源在 XMP 有坐标：sourceHasLocation", result.sourceHasLocation)
        assertTrue("应发生管线内注入", result.gpsInjected)
        assertNull("注入应成功（无失败原因）", result.gpsInjectFailed)
        assertTrue("产物必须带 EXIF GPS", result.outputHasExifGps)

        val out = File(result.path).readBytes()
        val coords = JpegUtil.gpsCoordinates(out)
        assertNotNull("产物 EXIF 应解析出坐标", coords)
        assertEquals(22.8389, coords!!.first, 1e-3)
        assertEquals(108.1780, coords.second, 1e-3)

        // 指纹 = 对**注入前原始主图**的 SHA-256 前 16 位（test25 算在注入后字节上，
        // 会让重转把既往产物判为异源而生 IMG_x(1).jpg——本断言钉住口径）
        val expectedFp = sha16(primary)
        assertEquals(expectedFp, result.sourceFingerprint)
        val outXmp = JpegUtil.findXmpSegment(out)!!.xmpText
        assertEquals(expectedFp, XmpTemplate.extractSourceFingerprint(outXmp))

        // Motion Photo 三支柱完好：XMP 标记 / lpex box / convert footer
        assertTrue(outXmp.contains("""GCamera:MotionPhoto="1""""))
        assertTrue("lpex 应插入", BinaryUtils.indexOf(out, "LivePhotoExtension".toByteArray()) >= 0)
        assertNotNull(FooterUtil.parseFooter(out))
    }

    /** 坐标只在伴生视频 ©xyz：从视频恢复并注入（第二来源） */
    @Test
    fun videoOnlySourceGetsInjectionFromXyz() {
        val dir = Files.createTempDirectory("vlc_loc_vid").toFile()
        val primary = primary(withExifGps = false, xmpApp1 = null)
        val (jpg, _) = makePair(dir, "IMG_VID",
            vivoDualJpeg(primary), vivoDualMp4(xyz = "+22.8389+108.1780/"))

        val outDir = Files.createTempDirectory("vlc_loc_out2").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)

        assertTrue(result.sourceHasLocation)
        assertTrue(result.gpsInjected)
        assertTrue(result.outputHasExifGps)
        val coords = JpegUtil.gpsCoordinates(File(result.path).readBytes())
        assertEquals(22.8389, coords!!.first, 1e-3)
        assertEquals(108.1780, coords.second, 1e-3)
    }

    /** 源 EXIF 已带 GPS：不再注入（无损透传），指纹仍是原始源 */
    @Test
    fun exifSourceIsNotInjected() {
        val dir = Files.createTempDirectory("vlc_loc_exif").toFile()
        val primary = primary(withExifGps = true, xmpApp1 = null)
        val (jpg, _) = makePair(dir, "IMG_EXIF",
            vivoDualJpeg(primary), vivoDualMp4(xyz = null))

        val outDir = Files.createTempDirectory("vlc_loc_out3").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)

        assertTrue(result.sourceHasLocation)
        assertFalse("源 EXIF 已带 GPS：不得再注入", result.gpsInjected)
        assertNull(result.gpsInjectFailed)
        assertTrue(result.outputHasExifGps)
        assertEquals(sha16(primary), result.sourceFingerprint)
        val coords = JpegUtil.gpsCoordinates(File(result.path).readBytes())
        assertEquals(22.0, coords!!.first, 1e-3)
        assertEquals(108.0, coords.second, 1e-3)
    }

    /** 三处都无坐标：源确实无位置——不注入、不谎报、转换照常（无损语义不变） */
    @Test
    fun noCoordsSourceConvertsCleanlyWithoutInjection() {
        val dir = Files.createTempDirectory("vlc_loc_none").toFile()
        val primary = primary(withExifGps = false, xmpApp1 = null)
        val (jpg, _) = makePair(dir, "IMG_NONE",
            vivoDualJpeg(primary), vivoDualMp4(xyz = null))

        val outDir = Files.createTempDirectory("vlc_loc_out4").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)

        assertFalse(result.sourceHasLocation)
        assertFalse(result.gpsInjected)
        assertNull("没有注入动作，就不该有注入失败", result.gpsInjectFailed)
        assertFalse(result.outputHasExifGps)
        assertTrue("转换产物必须照常写出", File(result.path).length() > 0)
    }

    /**
     * 最小复现（v2.7.0 零星丢位置的根因）：
     * 源 EXIF 的 GPS IFD **非空但解不出经纬度**（只有 GPSLatitudeRef），XMP 有真实坐标。
     *
     * v2.7.0 行为：`ensureExifGps` 用 `hasGpsExif` 判定「源已有位置」→ 直接 return 不注入；
     * `VivoSingle` 又用 `hasGpsExif` 判产物 → outputHasExifGps=true；
     * 批次层因此判 unsavedLocation=false。三层全静默。但 vivo 媒体扫描按文件 EXIF
     * 重算行坐标，这段不可解析的 GPS 段让扫描写不出坐标 → 相册不显示位置。
     *
     * 修复后：必须识别「GPS 段不可用」，用 XMP 坐标**覆盖**注入，产物 EXIF 可解析。
     */
    @Test
    fun junkGpsIfdSourceMustGetUsableExifGps() {
        val dir = Files.createTempDirectory("vlc_loc_junk").toFile()
        val primary = primaryWithExif(exifApp1 = junkGpsExifApp1(), xmpApp1 = gpsXmpApp1())
        // 前置事实：这就是「非空但不可用」的形态，两个判据结论相反
        assertTrue("前置：源 EXIF 确有 GPS IFD", JpegUtil.hasGpsExif(primary))
        assertNull("前置：但它解不出经纬度", JpegUtil.gpsCoordinates(primary))

        val (jpg, _) = makePair(dir, "IMG_JUNK",
            vivoDualJpeg(primary), vivoDualMp4(xyz = null))
        val outDir = Files.createTempDirectory("vlc_loc_out5").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)

        assertTrue("XMP 有坐标：源有位置", result.sourceHasLocation)
        assertTrue("必须识别为不可用并注入", result.gpsInjected)
        assertTrue("应标记源 EXIF 段不可解析", result.exifGpsUnusable)
        assertNull(result.gpsInjectFailed)
        assertTrue("产物 EXIF 必须带可用经纬度", result.outputHasExifGps)

        val coords = JpegUtil.gpsCoordinates(File(result.path).readBytes())
        assertNotNull("相册靠这段 EXIF 才能显示位置", coords)
        assertEquals(22.8389, coords!!.first, 1e-3)
        assertEquals(108.1780, coords.second, 1e-3)
        // 指纹仍以注入前原始主图计算（重转可原地覆盖）
        assertEquals(sha16(primary), result.sourceFingerprint)
    }

    /** GPS 段非空但不可用、且 XMP/视频也没有坐标：如实判「源无可交付位置」，
     *  不能谎报成功，也不能凭空注入 */
    @Test
    fun junkGpsIfdWithoutFallbackCoordsIsReportedAsNoLocation() {
        val dir = Files.createTempDirectory("vlc_loc_junk2").toFile()
        val primary = primaryWithExif(exifApp1 = junkGpsExifApp1(), xmpApp1 = null)
        val (jpg, _) = makePair(dir, "IMG_JUNK2",
            vivoDualJpeg(primary), vivoDualMp4(xyz = null))

        val outDir = Files.createTempDirectory("vlc_loc_out6").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)

        assertFalse("没有可恢复坐标：sourceHasLocation 必须为 false", result.sourceHasLocation)
        assertTrue("但要显式记录 EXIF 段不可解析", result.exifGpsUnusable)
        assertFalse(result.gpsInjected)
        assertFalse("产物不可能凭空多出可用坐标", result.outputHasExifGps)
        assertTrue(File(result.path).length() > 0)
    }
}
