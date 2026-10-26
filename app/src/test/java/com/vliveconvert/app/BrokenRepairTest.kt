package com.vliveconvert.app

import com.vliveconvert.app.convert.Converter
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.BrokenVerdict
import com.vliveconvert.app.core.FooterUtil
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.Mp4Util
import com.vliveconvert.app.core.VivoDual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * 破损 vivo 实况修复（议题 #2）的 JVM 回归测试。
 *
 * 背景：vivo X200 系列相机「前台算法优化被中断」（拍完立刻息屏/退后台）会留下
 * 破损实况——糊的 JPG（无 cameralbum! footer，相册里是普通糊照片）+ 相册不可见的
 * 伴生 MP4。修复 = 把 MP4 合并回 JPG（不提升分辨率、不重编码）。
 *
 * 误报硬防线（缺一不可，全部在此钉死）：
 * 1. 仅收录相机目录（DCIM/Camera）
 * 2. EXIF 机型校验（Make/Model 含 vivo）
 * 3. MP4 moov 必须存在；缺 moov 明确报「无法修复：视频损坏」，不做半成品转换
 */
class BrokenRepairTest {

    private fun log(level: String, msg: String, tag: String) {
        println("[$level][$tag] $msg")
    }

    // ------------------------------------------------ 合成样本构造

    /** box = 4B size + 4B type + payload */
    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArray(8 + payload.size)
        BinaryUtils.writeU32BE(b, 0, (payload.size + 8).toLong())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(t, 0, b, 4, 4)
        System.arraycopy(payload, 0, b, 8, payload.size)
        return b
    }

    private fun le16(v: Int) =
        byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun le32(v: Int) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** 最小合法 JPEG：SOI + SOF0(16x16) + SOS + 熵数据（无 FF）+ EOI */
    private fun minimalJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        run {
            val payload = byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0)
            val seg = ByteArray(2 + 2 + payload.size)
            seg[0] = 0xFF.toByte()
            seg[1] = 0xC0.toByte()
            BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
            System.arraycopy(payload, 0, seg, 4, payload.size)
            out.write(seg)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0))
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x12, 0x34, 0x56))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    /** 伪随机（可复现）字节块，模拟 mdat 视频数据 */
    private fun pseudoRandom(size: Int, seed: Int): ByteArray {
        val b = ByteArray(size)
        var s = seed.toLong()
        for (i in 0 until size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            b[i] = (s ushr 33).toByte()
        }
        return b
    }

    /**
     * 小端 TIFF：IFD0 含 1 个 Make(0x010F) ASCII 条目。
     * 值 ≤4 字节内联（0 填充），否则存于 IFD 之后的偏移 26。
     */
    private fun tiffWithMake(make: String): ByteArray {
        val v = make.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))    // "II" + magic
        out.write(le32(8))                                 // IFD0 offset = 8
        out.write(le16(1))                                 // 1 entry
        out.write(le16(0x010F))                            // tag Make
        out.write(le16(2))                                 // type ASCII
        out.write(le32(v.size))                            // count
        if (v.size <= 4) {
            out.write(v.copyOf(4))                         // 内联（0 填充）
        } else {
            out.write(le32(26))                            // value offset = 26
        }
        out.write(le32(0))                                 // next IFD = 0
        if (v.size > 4) out.write(v)                       // 8+2+12+4 = 26
        return out.toByteArray()
    }

    /** EXIF APP1 段（"Exif\0\0" + TIFF 载荷） */
    private fun exifApp1(tiff: ByteArray): ByteArray {
        val payload = JpegUtil.exifPrefix + tiff
        val seg = ByteArray(4 + payload.size)
        seg[0] = 0xFF.toByte()
        seg[1] = 0xE1.toByte()
        BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
        System.arraycopy(payload, 0, seg, 4, payload.size)
        return seg
    }

    private fun jpegWithExif(exifApp1Seg: ByteArray): ByteArray {
        val base = minimalJpeg()
        return base.copyOfRange(0, 2) + exifApp1Seg + base.copyOfRange(2, base.size)
    }

    /** 合成 streamdata 附加块（"streamdata" 魔数 + 类型 + 伪随机载荷） */
    private fun fakeStreamData(type: String, size: Int, seed: Int): ByteArray =
        "streamdata".toByteArray(Charsets.US_ASCII) +
            type.toByteArray(Charsets.US_ASCII) +
            pseudoRandom(size, seed)

    /**
     * 破损实况 JPG：JPEG 主体（含 vivo EXIF）+ streamdata 附加块，
     * **没有** cameralbum! footer —— 即「前台优化被中断」留下的糊图形态。
     */
    private fun brokenJpeg(withVivoExif: Boolean = true): ByteArray {
        val base = if (withVivoExif) jpegWithExif(exifApp1(tiffWithMake("vivo"))) else minimalJpeg()
        return base + fakeStreamData("DEGS", 64, 11)
    }

    /** 完整实况 MP4：ftyp + mdat + moov（X300s 形态：无 vivo uuid box；stripVivoUuid 对其安全空操作） */
    private fun liveMp4(): ByteArray =
        box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) +
            "isom".toByteArray() + "mp41".toByteArray()) +
            box("mdat", pseudoRandom(2048, 42)) +
            box("moov", ByteArray(0))

    /** 中断截断的 MP4：mdat 只写了一半就被打断 —— 无 moov，不可修 */
    private fun truncatedMp4(): ByteArray =
        box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) +
            "isom".toByteArray() + "mp41".toByteArray()) +
            box("mdat", pseudoRandom(2048, 42)).copyOfRange(0, 8 + 1024)

    /** 正常双文件 JPG（有 footer），回归对照组 */
    private fun vivoDualJpeg(imageTime: Long = 12): ByteArray {
        val json = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to imageTime,
            "com.android.camera.livephoto" to TEST_LIVE_ID,
            "version" to 2107
        ))
        val footer = FooterUtil.buildFooter(json, TEST_LIVE_ID, FooterUtil.vivoPrefix)
        return minimalJpeg() + fakeStreamData("DEGS", 64, 11) + FooterUtil.vivoPrefix + footer
    }

    /** 在「相机目录」里落一对文件：tmpdir/DCIM/Camera/$stem.{jpg,mp4}（路径含 /DCIM/Camera） */
    private fun writeCameraPair(
        root: File, stem: String, jpgBytes: ByteArray, mp4Bytes: ByteArray
    ): File {
        val cam = File(root, "DCIM/Camera").apply { mkdirs() }
        cam.resolve("$stem.mp4").writeBytes(mp4Bytes)
        return cam.resolve("$stem.jpg").apply { writeBytes(jpgBytes) }
    }

    // ------------------------------------------------ 破损判定

    @Test
    fun brokenPairInCameraDirIsDetectedAsRepairable() {
        val root = Files.createTempDirectory("vlc_bk").toFile()
        val jpg = writeCameraPair(root, "IMG_1101", brokenJpeg(), liveMp4())

        assertEquals(BrokenVerdict.REPAIRABLE, VivoDual.brokenVerdict(jpg.absolutePath))
        assertTrue("破损对应进修复判定", VivoDual.isBrokenDualFile(jpg.absolutePath))
        // 正常双文件判定必须仍是 false（不误入选择器）
        assertFalse("破损对不是正常双文件", VivoDual.isVivoDualFile(jpg.absolutePath))
    }

    @Test
    fun normalDualFileNeverEntersBrokenVerdict() {
        // 有 footer 的正常双文件即使放在相机目录也不进破损判定
        val root = Files.createTempDirectory("vlc_bk_norm").toFile()
        val jpg = writeCameraPair(root, "IMG_1102", vivoDualJpeg(), liveMp4())
        assertTrue("前置：应识别为正常双文件", VivoDual.isVivoDualFile(jpg.absolutePath))
        assertEquals(BrokenVerdict.NOT_BROKEN, VivoDual.brokenVerdict(jpg.absolutePath))
        assertFalse(VivoDual.isBrokenDualFile(jpg.absolutePath))
    }

    @Test
    fun plainPhotoWithSiblingMp4IsNotBroken() {
        // 普通 JPG（无 vivo EXIF、无 footer）+ 同名视频：最典型的误报对
        val root = Files.createTempDirectory("vlc_bk_plain").toFile()
        val jpg = writeCameraPair(root, "IMG_1103", brokenJpeg(withVivoExif = false), liveMp4())
        assertEquals(BrokenVerdict.NOT_BROKEN, VivoDual.brokenVerdict(jpg.absolutePath))
        assertFalse(VivoDual.isBrokenDualFile(jpg.absolutePath))
    }

    @Test
    fun nonCameraPathIsRejected() {
        // 同样的破损文件对，放在非相机目录（如 Downloads）→ 不收录（误报硬防线）
        val root = Files.createTempDirectory("vlc_bk_outside").toFile()
        root.resolve("IMG_1104.mp4").writeBytes(liveMp4())
        val jpg = root.resolve("IMG_1104.jpg").apply { writeBytes(brokenJpeg()) }
        assertEquals(BrokenVerdict.NOT_BROKEN, VivoDual.brokenVerdict(jpg.absolutePath))
        assertFalse(VivoDual.isBrokenDualFile(jpg.absolutePath))
    }

    @Test
    fun nonVivoExifIsRejected() {
        // EXIF 机型校验：Make 不是 vivo（如其它品牌手机）→ 不收录
        val root = Files.createTempDirectory("vlc_bk_exif").toFile()
        val jpg = writeCameraPair(root, "IMG_1105",
            jpegWithExif(exifApp1(tiffWithMake("Apple"))) + fakeStreamData("DEGS", 64, 11),
            liveMp4())
        assertEquals(BrokenVerdict.NOT_BROKEN, VivoDual.brokenVerdict(jpg.absolutePath))

        // 完全无 EXIF 同样拒绝
        val root2 = Files.createTempDirectory("vlc_bk_noexif").toFile()
        val jpg2 = writeCameraPair(root2, "IMG_1106", minimalJpeg(), liveMp4())
        assertEquals(BrokenVerdict.NOT_BROKEN, VivoDual.brokenVerdict(jpg2.absolutePath))
    }

    @Test
    fun missingMoovIsReportedAsVideoCorrupt() {
        // 拍摄中断把 MP4 截断（缺 moov）→ 形态仍是破损对，但结论是「视频损坏」：
        // 不进可修判定（isBrokenDualFile=false），由修复页明确标注、转换入口明确拒绝
        val root = Files.createTempDirectory("vlc_bk_trunc").toFile()
        val jpg = writeCameraPair(root, "IMG_1107", brokenJpeg(), truncatedMp4())
        val mp4 = VivoDual.siblingMp4(jpg.absolutePath)!!
        assertFalse("前置：截断 MP4 应无 moov", Mp4Util.hasMoov(mp4))

        assertEquals(BrokenVerdict.VIDEO_CORRUPT, VivoDual.brokenVerdict(jpg.absolutePath))
        assertFalse("缺 moov 不得进可修判定", VivoDual.isBrokenDualFile(jpg.absolutePath))
    }

    @Test
    fun brokenVerdictIsExceptionSafe() {
        assertEquals(BrokenVerdict.NOT_BROKEN, VivoDual.brokenVerdict(""))
        assertEquals(BrokenVerdict.NOT_BROKEN,
            VivoDual.brokenVerdict("/nonexistent/path/IMG_1.jpg"))
    }

    // ------------------------------------------------ 解析（footer 可选化）

    @Test
    fun readWithoutFooterFallsBackAndMarksBrokenSource() {
        val root = Files.createTempDirectory("vlc_bk_read").toFile()
        val jpg = writeCameraPair(root, "IMG_1201", brokenJpeg(), liveMp4())

        val asset = VivoDual.read(jpg.absolutePath, ::log)
        assertEquals("无 footer 应标记 sourceFormat=vivo_broken", "vivo_broken", asset.sourceFormat)
        assertEquals("无 footer 时 livephotoId 应为空", null, asset.livephotoId)
        assertEquals("无 footer 时 imageTime 应为空（由 effective 系列兜底）", null, asset.imageTime)
        // 图像主体无损拆出
        assertTrue("主图应完整拆出", asset.primaryJpeg.contentEquals(
            brokenJpeg().let { b ->
                // 主图 = streamdata 之前的部分
                b.copyOfRange(0, BinaryUtils.indexOf(b, "streamdata".toByteArray(Charsets.US_ASCII)))
            }))
        // streamdata 附加块原样保留
        assertTrue("streamdata 应透传",
            BinaryUtils.indexOf(asset.extras["vivo_streamdata"] as ByteArray,
                fakeStreamData("DEGS", 64, 11)) >= 0)
        // 视频轨信息可解析（moov 存在）
        assertNotNull(asset.videoInfo)
        // 封面兜底不抛异常（effectivePtsUs / effectiveImageTime）
        assertTrue("封面时间戳兜底不抛异常", asset.effectiveImageTime() >= 0L)
    }

    @Test
    fun readWithFooterKeepsExistingBehavior() {
        // 回归护栏：footer 可选化后，正常双文件解析行为不变
        val root = Files.createTempDirectory("vlc_bk_read_norm").toFile()
        val jpg = writeCameraPair(root, "IMG_1202", vivoDualJpeg(), liveMp4())

        val asset = VivoDual.read(jpg.absolutePath, ::log)
        assertEquals("vivo_dual", asset.sourceFormat)
        assertEquals(TEST_LIVE_ID, asset.livephotoId)
        assertEquals(12L, asset.imageTime)
    }

    // ------------------------------------------------ 转换（回退分发 + 全链路）

    @Test
    fun brokenPairConvertsEndToEnd() {
        val root = Files.createTempDirectory("vlc_bk_conv").toFile()
        val jpg = writeCameraPair(root, "IMG_1301", brokenJpeg(), liveMp4())
        val outDir = Files.createTempDirectory("vlc_bk_conv_out").toFile()

        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        assertEquals("转换结果应标记来源为破损对", "vivo_broken", result.sourceFormat)

        val data = File(result.path).readBytes()

        // XMP 为 vivo 单文件实况标记（最坏情况 vivo 相册不认，第三方相册可播放）
        val (jpegs, _) = JpegUtil.splitJpegs(data)
        assertEquals(1, jpegs.size)
        val xmp = JpegUtil.findXmpSegment(jpegs[0])
        assertNotNull("输出应含 XMP APP1 段", xmp)
        assertTrue(xmp!!.xmpText.contains("""GCamera:MotionPhoto="1""""))
        assertTrue(xmp.xmpText.contains("ns.vivo.com/photos"))

        // 尾部 convert footer（imageTime 无源时取 effective 兜底，不崩溃）
        val footer = FooterUtil.parseFooter(data)
        assertNotNull("输出尾部应含 cameralbum! footer", footer)
        assertEquals(FooterUtil.oppoFixedId, footer!!.livephotoId)

        // lpex 已插入 moov；视频载荷无损透传
        assertTrue("应含 lpex box",
            BinaryUtils.indexOf(data, "LivePhotoExtension".toByteArray(Charsets.US_ASCII)) >= 0)
        val mdatSlice = pseudoRandom(2048, 42).copyOfRange(100, 164)
        assertTrue("mdat 视频载荷应无损保留", BinaryUtils.indexOf(data, mdatSlice) >= 0)

        // streamdata 附加块透传（紧跟图像数据之后）
        assertTrue("streamdata 附加块应透传",
            BinaryUtils.indexOf(data, fakeStreamData("DEGS", 64, 11)) >= 0)
    }

    @Test
    fun convertRejectsCorruptVideoPairExplicitly() {
        // 缺 moov（截断）：明确报「无法修复：视频损坏」，不做半成品转换
        val root = Files.createTempDirectory("vlc_bk_rej").toFile()
        val jpg = writeCameraPair(root, "IMG_1302", brokenJpeg(), truncatedMp4())
        try {
            Converter.convertToVivoSingle(jpg.absolutePath, root.absolutePath, ::log)
            fail("缺 moov 必须抛异常，不允许半成品输出")
        } catch (e: Exception) {
            assertTrue(
                "异常应明确说「无法修复」: ${e.message}",
                (e.message ?: "").contains("无法修复"))
            assertTrue(
                "异常应说明是视频损坏: ${e.message}",
                (e.message ?: "").contains("视频损坏"))
        }
    }

    @Test
    fun convertStillRejectsPlainNonDual() {
        // 普通照片（无 footer 且不是破损形态）维持既有错误文案（选择器口径不变）
        val root = Files.createTempDirectory("vlc_bk_plainrej").toFile()
        val jpg = writeCameraPair(root, "IMG_1303", minimalJpeg(), liveMp4())
        try {
            Converter.convertToVivoSingle(jpg.absolutePath, root.absolutePath, ::log)
            fail("非双文件应抛出异常")
        } catch (e: Exception) {
            assertTrue("异常应为「不是 vivo 双文件实况照片」: ${e.message}",
                (e.message ?: "").contains("不是 vivo 双文件实况照片"))
        }
    }

    @Test
    fun exifMakeModelReaderCoversInlineAndOffsetValues() {
        // EXIF Make 解析：内联值（≤4B）与偏移值（>4B）两种布局都要读对
        val inline = jpegWithExif(exifApp1(tiffWithMake("vivo")))
        assertEquals("vivo", JpegUtil.exifMakeModel(inline).first)

        val offset = jpegWithExif(exifApp1(tiffWithMake("vivo X200")))
        assertEquals("vivo X200", JpegUtil.exifMakeModel(offset).first)

        // 无 EXIF → (null, null)
        assertEquals(null, JpegUtil.exifMakeModel(minimalJpeg()).first)
    }

    @Test
    fun hasMoovStreamingWalkMatchesInMemoryIteration() {
        // 流式 box 遍历（检测用，不整读大 MP4）与内存版结论一致
        val root = Files.createTempDirectory("vlc_bk_moov").toFile()
        val full = root.resolve("full.mp4").apply { writeBytes(liveMp4()) }
        assertTrue("完整 MP4 应有 moov", Mp4Util.hasMoov(full.absolutePath))

        val trunc = root.resolve("trunc.mp4").apply { writeBytes(truncatedMp4()) }
        assertFalse("截断 MP4 应无 moov", Mp4Util.hasMoov(trunc.absolutePath))

        assertFalse("不存在的文件不得抛异常", Mp4Util.hasMoov(root.resolve("nope.mp4").absolutePath))
    }

    companion object {
        /** 28 字符 livephoto ID（与 ConverterPipelineTest 一致） */
        private val TEST_LIVE_ID: String = "-1234567890".padEnd(28, '0')
    }
}
