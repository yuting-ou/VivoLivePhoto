package com.vliveconvert.app

import com.vliveconvert.app.convert.Converter
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.FooterUtil
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.VivoDual
import com.vliveconvert.app.core.XmpTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 转换管线的 JVM 回归测试：
 * 用字节级合成的 vivo 双文件样本（IMG_xxx.jpg + IMG_xxx.mp4）跑完整
 * detect → read → write 流程，校验单文件输出的关键结构。
 */
class ConverterPipelineTest {

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

    /** 最小合法 JPEG：SOI + SOF0(16x16) + SOS + 熵数据（无 FF）+ EOI */
    private fun minimalJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        // SOF0：len=11, 精度8, 高16, 宽16, 分量数1
        run {
            val payload = byteArrayOf(
                8, 0, 16, 0, 16, 1,
                1, 0x11, 0
            )
            val seg = ByteArray(2 + 2 + payload.size) // [FF C0][len][payload]
            seg[0] = 0xFF.toByte()
            seg[1] = 0xC0.toByte()
            BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
            System.arraycopy(payload, 0, seg, 4, payload.size)
            out.write(seg)
        }
        // SOS：len=8
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0))
        // 熵编码数据（不含 FF）
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x12, 0x34, 0x56))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    /** 伪随机（可复现）字节块，用于模拟 mdat 视频数据 */
    private fun pseudoRandom(size: Int, seed: Int): ByteArray {
        val b = ByteArray(size)
        var s = seed.toLong()
        for (i in 0 until size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            b[i] = (s ushr 33).toByte()
        }
        // 保证不含连续合法 box 结构造成遍历歧义——不需要，mdat 是不透明载荷
        return b
    }

    /** 合成 vivo 双文件 MP4：ftyp + mdat + moov + vivoMediaEStream uuid + vivoMediaExtInfo uuid */
    private fun vivoDualMp4(): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() +
            byteArrayOf(0, 0, 2, 0) + "isom".toByteArray() + "mp41".toByteArray())
        val mdatPayload = pseudoRandom(2048, 42)
        val mdat = box("mdat", mdatPayload)
        val moov = box("moov", ByteArray(0))
        val eStream = box("uuid", "vivoMediaEStream".toByteArray(Charsets.US_ASCII) +
            pseudoRandom(122, 7)) // 共 138B，对齐真机布局
        val extInfoJson = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to TEST_LIVE_ID,
            "version" to 2107
        ))
        val extInfoFooter = FooterUtil.buildFooter(extInfoJson, TEST_LIVE_ID, FooterUtil.vivoPrefix)
        val extInfo = box("uuid", "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII) + extInfoFooter)
        return ftyp + mdat + moov + eStream + extInfo
    }

    /** 合成 streamdata 附加块（vivo "streamdata" 魔数 + 类型 + 伪随机载荷） */
    private fun fakeStreamData(type: String, size: Int, seed: Int): ByteArray =
        "streamdata".toByteArray(Charsets.US_ASCII) +
            type.toByteArray(Charsets.US_ASCII) +
            pseudoRandom(size, seed)

    /** 合成 vivo 双文件 JPG：最小 JPEG + streamdata 附加块 + "vivo" 前缀 + footer JSON + cameralbum footer */
    private fun vivoDualJpeg(imageTime: Long = 12): ByteArray {
        val json = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to imageTime,
            "com.android.camera.livephoto" to TEST_LIVE_ID,
            "version" to 2107
        ))
        val footer = FooterUtil.buildFooter(json, TEST_LIVE_ID, FooterUtil.vivoPrefix)
        return minimalJpeg() + fakeStreamData("DEGS", 64, 11) + FooterUtil.vivoPrefix + footer
    }

    /**
     * 构造变长 ID 字段的 footer（镜像 vivo X200 Ultra 人像实况实测字节）：
     * ID 字段 = 11 个 0x00 + '/' + 28 字符 ID（共 40B），tail = 40+4+11 = 55B。
     */
    private fun buildPortraitFooter(jsonBytes: ByteArray, id: String, prefix: ByteArray): ByteArray {
        val idField = ByteArray(40)
        idField[11] = '/'.code.toByte()
        val idBytes = id.toByteArray(Charsets.US_ASCII)
        System.arraycopy(idBytes, 0, idField, 12, 28)
        val tail = ByteArray(40 + 4 + FooterUtil.magic.size)
        System.arraycopy(idField, 0, tail, 0, 40)
        tail[40] = 0xFF.toByte(); tail[41] = 0xFF.toByte(); tail[42] = 0xFF.toByte(); tail[43] = 0xFF.toByte()
        System.arraycopy(FooterUtil.magic, 0, tail, 44, FooterUtil.magic.size)

        val out = java.io.ByteArrayOutputStream()
        out.write(prefix)
        out.write(jsonBytes)
        val lenBuf = ByteArray(4)
        BinaryUtils.writeU32BE(lenBuf, 0, jsonBytes.size.toLong())
        out.write(lenBuf)
        out.write(FooterUtil.marker)
        BinaryUtils.writeU32BE(lenBuf, 0, (tail.size + 4).toLong())
        out.write(lenBuf)
        out.write(tail)
        return out.toByteArray()
    }

    /** 合成 vivo 人像实况双文件（version 2202 + refocus 字段、JPG JSON 无 imageTime、55B 尾部） */
    private fun makePortraitPair(dir: File, stem: String = "IMG_5001"): Pair<File, File> {
        val PORTRAIT_ID = "1788155072103343df5100000000"
        val jpgJson = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.takenmodel" to "vivo X200 Ultra",
            "com.android.camera.camerafacing" to "0",
            "com.android.camera.joint.refocusAlgoSource" to 2,
            "com.android.camera.livephoto" to PORTRAIT_ID,
            "version" to 2202,
            "com.android.camera.joint.refocus" to 2204
        ))
        val jpg = File(dir, "$stem.jpg").apply {
            writeBytes(minimalJpeg() + fakeStreamData("IAC", 512, 13) + FooterUtil.vivoPrefix +
                buildPortraitFooter(jpgJson, PORTRAIT_ID, FooterUtil.vivoPrefix))
        }
        val mp4Json = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.takenmodel" to "vivo X200 Ultra",
            "com.android.camera.camerafacing" to "0",
            "com.android.camera.imageTime" to 31L,
            "com.android.camera.moduleid" to "portrait",
            "com.android.camera.livephoto" to PORTRAIT_ID,
            "version" to 2200
        ))
        val extInfo = box("uuid", "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII) +
            buildPortraitFooter(mp4Json, PORTRAIT_ID, FooterUtil.extPrefix))
        val mp4 = File(dir, "$stem.mp4").apply {
            writeBytes(box("ftyp", "isom".toByteArray() +
                byteArrayOf(0, 0, 2, 0) + "isom".toByteArray() + "mp41".toByteArray()) +
                box("mdat", pseudoRandom(2048, 99)) +
                box("moov", ByteArray(0)) + extInfo)
        }
        return jpg to mp4
    }

    private fun makePair(dir: File, stem: String = "IMG_1001"): Pair<File, File> {
        val jpg = File(dir, "$stem.jpg").apply { writeBytes(vivoDualJpeg()) }
        val mp4 = File(dir, "$stem.mp4").apply { writeBytes(vivoDualMp4()) }
        return jpg to mp4
    }

    private fun countOf(data: ByteArray, target: ByteArray): Int {
        var n = 0
        var i = BinaryUtils.indexOf(data, target)
        while (i >= 0) {
            n++
            i = BinaryUtils.indexOf(data, target, i + 1)
        }
        return n
    }

    // ------------------------------------------------ 测试

    @Test
    fun dualFileDetected() {
        val dir = Files.createTempDirectory("vlc_dual").toFile()
        val (jpg, _) = makePair(dir)
        assertTrue("成对的 vivo 双文件应被识别", VivoDual.isVivoDualFile(jpg.absolutePath))
    }

    @Test
    fun jpgWithoutSiblingMp4Rejected() {
        val dir = Files.createTempDirectory("vlc_nosib").toFile()
        val jpg = File(dir, "IMG_2001.jpg").apply { writeBytes(vivoDualJpeg()) }
        assertFalse("缺少伴生 .mp4 不应识别", VivoDual.isVivoDualFile(jpg.absolutePath))
    }

    @Test
    fun plainJpgWithMp4Rejected() {
        val dir = Files.createTempDirectory("vlc_plain").toFile()
        val jpg = File(dir, "IMG_2002.jpg").apply { writeBytes(minimalJpeg()) }
        val mp4 = File(dir, "IMG_2002.mp4").apply { writeBytes(vivoDualMp4()) }
        assertFalse("无 vivo footer 的普通 JPG+MP4 不应识别",
            VivoDual.isVivoDualFile(jpg.absolutePath))
    }

    @Test
    fun embeddedMotionJpgRejected() {
        // Google 内嵌式动态照片（XMP 含 MotionPhoto="1"）即使带同名 mp4 也应排除
        val dir = Files.createTempDirectory("vlc_embed").toFile()
        val xmpJpeg = JpegUtil.replaceOrInsertXmp(
            minimalJpeg(),
            """<x:xmpmeta><rdf:RDF GCamera:MotionPhoto="1"/></x:xmpmeta>""")
        val jpg = File(dir, "IMG_2003.jpg").apply { writeBytes(xmpJpeg) }
        File(dir, "IMG_2003.mp4").apply { writeBytes(vivoDualMp4()) }
        assertFalse("内嵌式动态照片不应识别为双文件", VivoDual.isVivoDualFile(jpg.absolutePath))
    }

    @Test
    fun convertDualToSingleProducesValidStructure() {
        val dir = Files.createTempDirectory("vlc_conv").toFile()
        val (jpg, _) = makePair(dir, "IMG_3001")
        val outDir = Files.createTempDirectory("vlc_out").toFile()

        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        val outPath = result.path
        val out = File(outPath)
        assertTrue("输出文件应存在", out.exists())
        assertEquals("输出名应与源同名（vivo 相册合并产物不带后缀）",
            "IMG_3001.jpg", out.name)

        val data = out.readBytes()

        // 1. JPEG 主体合法且 XMP 为 vivo 单文件实况标记
        val (jpegs, consumed) = JpegUtil.splitJpegs(data)
        assertEquals("输出应含 1 个完整 JPEG 主体", 1, jpegs.size)
        val xmp = JpegUtil.findXmpSegment(jpegs[0])
        assertNotNull("输出应含 XMP APP1 段", xmp)
        xmp!!.xmpText.let {
            assertTrue(it.contains("""GCamera:MotionPhoto="1""""))
            assertTrue(it.contains("ns.vivo.com/photos"))
            assertTrue(it.contains("VCamera:VMediaKitVersion"))
        }

        // 2. 尾部 convert footer：固定 ID + 源 imageTime 透传
        val footer = FooterUtil.parseFooter(data)
        assertNotNull("输出尾部应含 cameralbum! footer", footer)
        assertEquals(FooterUtil.oppoFixedId, footer!!.livephotoId)
        assertEquals("源 footer 的 imageTime 应透传到 convert footer",
            12L, footer.imageTime)

        // 3. vivoMediaEStream 保留（vivo 相册识别实况的关键）且仅 1 处
        val eStreamTag = "vivoMediaEStream".toByteArray(Charsets.US_ASCII)
        assertEquals("应保留 1 个 vivoMediaEStream uuid box", 1, countOf(data, eStreamTag))

        // 4. 源 MP4 的 vivoMediaExtInfo uuid box 已剥离（只剩 convert footer 的 extPrefix）
        val extInfoTag = "vivoMediaExtInfovivo".toByteArray(Charsets.US_ASCII)
        assertEquals("vivoMediaExtInfo 包装应被剥离", 1, countOf(data, extInfoTag))

        // 5. lpex box 已插入 moov
        assertTrue("应含 lpex box（LivePhotoExtension 载荷）",
            countOf(data, "LivePhotoExtension".toByteArray(Charsets.US_ASCII)) == 1)

        // 6. 视频数据无损透传（mdat 载荷逐字节保留）
        val mdatSlice = pseudoRandom(2048, 42).copyOfRange(100, 164)
        assertTrue("mdat 视频载荷应无损保留", BinaryUtils.indexOf(data, mdatSlice) >= 0)

        // 8. streamdata 附加块原样透传（紧跟图像数据之后）
        assertTrue("streamdata 附加块应透传",
            BinaryUtils.indexOf(data, fakeStreamData("DEGS", 64, 11)) >= 0)

        // 7. 修改时间保留
        assertEquals("输出修改时间应继承源文件",
            jpg.lastModified() / 1000, out.lastModified() / 1000)
    }

    @Test
    fun portraitDualDetectedAndConverted() {
        // vivo X200 Ultra 人像实况：footer tail 55B（ID 字段 40B，含二进制前缀）
        val dir = Files.createTempDirectory("vlc_portrait").toFile()
        val (jpg, _) = makePortraitPair(dir)
        assertTrue("人像实况应被识别为双文件", VivoDual.isVivoDualFile(jpg.absolutePath))

        val outDir = Files.createTempDirectory("vlc_portrait_out").toFile()
        val outPath = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log).path
        val data = File(outPath).readBytes()

        val footer = FooterUtil.parseFooter(data)
        assertNotNull("输出尾部应含 convert footer", footer)
        // JPG JSON 无 imageTime → 应从伴生 MP4 footer 透传（=31）
        assertEquals("imageTime 应取自 MP4 footer", 31L, footer!!.imageTime)
        assertEquals(FooterUtil.oppoFixedId, footer.livephotoId)

        // 人像标记透传：moduleid（来自 MP4 footer）+ refocus 字段（来自 JPG footer）
        assertEquals("moduleid=portrait 应合并进 convert footer",
            "portrait", footer.json?.get("com.android.camera.moduleid"))
        assertEquals("joint.refocus 应合并进 convert footer",
            2204, footer.json?.get("com.android.camera.joint.refocus"))
        assertEquals("refocusAlgoSource 应合并进 convert footer",
            2, footer.json?.get("com.android.camera.joint.refocusAlgoSource"))

        // 人像深度/虚化数据流（streamdata 附加块）原样透传
        assertTrue("人像 streamdata 深度块应透传",
            BinaryUtils.indexOf(data, fakeStreamData("IAC", 512, 13)) >= 0)
    }

    @Test
    fun convertRejectsNonDualFile() {
        val dir = Files.createTempDirectory("vlc_rej").toFile()
        val jpg = File(dir, "IMG_4001.jpg").apply { writeBytes(minimalJpeg()) }
        try {
            Converter.convertToVivoSingle(jpg.absolutePath, dir.absolutePath, ::log)
            throw AssertionError("非双文件应抛出异常")
        } catch (e: Exception) {
            assertTrue("异常应为「不是 vivo 双文件实况照片」: ${e.message}",
                (e.message ?: "").contains("不是 vivo 双文件实况照片"))
        }
    }

    @Test
    fun footerJsonRoundTripWithNestedValues() {
        // footer 字段合并的完整性：嵌套对象/数组/null 均应无损往返
        // （vivo 人像 footer 中的 faceInfo 即嵌套对象）
        val json = """{"a":"x","b":1,"c":2.5,"d":true,"e":null,"f":{"n":1,"s":"y"},"g":[1,2,3],"h":["z"]}"""
        val parsed = com.vliveconvert.app.core.JsonMin.parse(json.toByteArray(Charsets.UTF_8))
        assertNotNull(parsed)
        val rebuilt = FooterUtil.buildFooterJson(parsed!!)
        val reparsed = com.vliveconvert.app.core.JsonMin.parse(rebuilt)
        assertEquals("嵌套 JSON 往返应无损", parsed, reparsed)
    }

    // ------------------------------------------------ 写后自检（分段 MD5） ----------

    @Test
    fun outputVerifierPassesOnFreshConversion() {
        val dir = Files.createTempDirectory("vlc_verify").toFile()
        val (jpg, _) = makePair(dir, "IMG_6001")
        val outDir = Files.createTempDirectory("vlc_verify_out").toFile()

        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        File(result.path).inputStream().use { ins ->
            assertTrue("完好输出的分段校验应通过",
                OutputVerifier.verify(ins, result.segments))
        }
    }

    @Test
    fun outputVerifierDetectsCorruption() {
        val dir = Files.createTempDirectory("vlc_corrupt").toFile()
        val (jpg, _) = makePair(dir, "IMG_6002")
        val outDir = Files.createTempDirectory("vlc_corrupt_out").toFile()

        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        val out = File(result.path)

        // ① 篡改视频段中部一个字节 → 校验必须失败
        val videoSeg = result.segments.first { it.name == "video" }
        val corrupted = out.readBytes()
        corrupted[videoSeg.offset + videoSeg.length / 2] =
            (corrupted[videoSeg.offset + videoSeg.length / 2] + 1).toByte()
        File(out.path + ".bad1").writeBytes(corrupted)
        File(out.path + ".bad1").inputStream().use { ins ->
            assertFalse("视频段损坏应被自检发现", OutputVerifier.verify(ins, result.segments))
        }

        // ② 截断（去掉最后 10 字节）→ 校验必须失败
        val truncated = out.readBytes().copyOfRange(0, out.length().toInt() - 10)
        File(out.path + ".bad2").writeBytes(truncated)
        File(out.path + ".bad2").inputStream().use { ins ->
            assertFalse("文件截断应被自检发现", OutputVerifier.verify(ins, result.segments))
        }

        // ③ 尾部追加多余字节 → 校验必须失败
        val padded = out.readBytes() + ByteArray(8) { 0x55 }
        File(out.path + ".bad3").writeBytes(padded)
        File(out.path + ".bad3").inputStream().use { ins ->
            assertFalse("尾部多余字节应被自检发现", OutputVerifier.verify(ins, result.segments))
        }
    }

    // ------------------------------------------------ sniffXmp 回归（off-by-idx 修复） ----------

    @Test
    fun sniffXmpDetectsEmbeddedMotionOnTinyFile() {
        // 构造「SOI + XMP APP1 + EOI」的极小文件：XMP 起始偏移 idx=4，
        // 结束标记后仅剩 2 字节 EOI（< idx）。旧实现的终点 = idx+end+12+idx，
        // 会越界 2 字节 → IndexOutOfBoundsException 被 catch 吞掉 → 返回空串
        // → 内嵌动态照片标记漏检。修复后应正确检出 MotionPhoto="1"
        val dir = Files.createTempDirectory("vlc_sniff").toFile()
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"/></rdf:RDF></x:xmpmeta>"""
        val tiny = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(xmp) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val f = File(dir, "IMG_7001.jpg").apply { writeBytes(tiny) }

        val sniffed = XmpTemplate.sniffXmp(f.absolutePath)
        assertTrue("极小文件的 XMP 应被完整截取（含结束标记）",
            sniffed.contains("</x:xmpmeta>"))
        assertTrue("内嵌 MotionPhoto 标记应被检出",
            XmpTemplate.parseMotionXmp(sniffed).isMotion)
    }

    // ------------------------------------------------ GPS EXIF 检测与保留 ----------

    /** 构造 EXIF APP1 段（"Exif\0\0" + 自定义 TIFF 载荷） */
    private fun exifApp1(tiff: ByteArray): ByteArray {
        val payload = JpegUtil.exifPrefix + tiff
        val seg = ByteArray(4 + payload.size)
        seg[0] = 0xFF.toByte()
        seg[1] = 0xE1.toByte()
        BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
        System.arraycopy(payload, 0, seg, 4, payload.size)
        return seg
    }

    /** 在最小 JPEG 的 SOI 后插入 EXIF APP1 段 */
    private fun jpegWithExif(exifApp1Seg: ByteArray): ByteArray {
        val base = minimalJpeg()
        return base.copyOfRange(0, 2) + exifApp1Seg + base.copyOfRange(2, base.size)
    }

    /** 小端 TIFF：IFD0 含 1 个 Orientation(0x0112) 条目（有效 EXIF、无 GPS） */
    private val tiffWithoutGps: ByteArray = byteArrayOf(
        0x49, 0x49, 0x2A, 0x00,             // "II" + magic 0x002A
        0x08, 0x00, 0x00, 0x00,             // IFD0 offset = 8
        0x01, 0x00,                         // IFD0: 1 entry
        0x12, 0x01, 0x03, 0x00,             // tag 0x0112 Orientation, type SHORT
        0x01, 0x00, 0x00, 0x00,             // count 1
        0x01, 0x00, 0x00, 0x00,             // value 1（内联）
        0x00, 0x00, 0x00, 0x00              // next IFD = 0
    )

    /** 小端 TIFF：IFD0 的 0x8825 指向含 1 个条目的 GPS IFD（有效 GPS） */
    private val tiffWithGps: ByteArray = byteArrayOf(
        0x49, 0x49, 0x2A, 0x00,             // "II" + magic
        0x08, 0x00, 0x00, 0x00,             // IFD0 offset = 8
        0x01, 0x00,                         // IFD0: 1 entry
        0x25, 0x88.toByte(), 0x04, 0x00,    // tag 0x8825 GPSInfo 指针, type LONG
        0x01, 0x00, 0x00, 0x00,             // count 1
        0x1A, 0x00, 0x00, 0x00,             // value → GPS IFD offset 26
        0x00, 0x00, 0x00, 0x00,             // next IFD = 0
        0x01, 0x00,                         // GPS IFD: 1 entry
        0x01, 0x00, 0x02, 0x00,             // tag 0x0001 GPSLatitudeRef, type ASCII
        0x02, 0x00, 0x00, 0x00,             // count 2
        0x4E, 0x00, 0x00, 0x00,             // value "N\0"（内联）
        0x00, 0x00, 0x00, 0x00              // next IFD = 0
    )

    /** 大端 TIFF（"MM"）：IFD0 的 0x8825 指向含 1 个条目的 GPS IFD */
    private val tiffWithGpsBE: ByteArray = byteArrayOf(
        0x4D, 0x4D, 0x00, 0x2A,             // "MM" + magic 0x002A（大端）
        0x00, 0x00, 0x00, 0x08,             // IFD0 offset = 8
        0x00, 0x01,                         // IFD0: 1 entry
        0x88.toByte(), 0x25, 0x00, 0x04,    // tag 0x8825, type LONG
        0x00, 0x00, 0x00, 0x01,             // count 1
        0x00, 0x00, 0x00, 0x1A,             // → GPS IFD offset 26
        0x00, 0x00, 0x00, 0x00,             // next IFD = 0
        0x00, 0x01,                         // GPS IFD: 1 entry
        0x00, 0x01, 0x00, 0x02,             // tag GPSLatitudeRef, type ASCII
        0x00, 0x00, 0x00, 0x02,             // count 2
        0x4E, 0x00, 0x00, 0x00,             // value "N\0"（内联）
        0x00, 0x00, 0x00, 0x00              // next IFD = 0
    )

    /** 小端 TIFF：0x8825 指向空 GPS IFD（0 条目）——脱敏后的常见残留形态 */
    private val tiffWithEmptyGpsIfd: ByteArray = byteArrayOf(
        0x49, 0x49, 0x2A, 0x00,
        0x08, 0x00, 0x00, 0x00,
        0x01, 0x00,
        0x25, 0x88.toByte(), 0x04, 0x00,
        0x01, 0x00, 0x00, 0x00,
        0x1A, 0x00, 0x00, 0x00,             // → GPS IFD offset 26
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00,                         // GPS IFD: 0 entries（空）
        0x00, 0x00, 0x00, 0x00
    )

    @Test
    fun gpsExifDetection() {
        // 无 EXIF → false
        assertFalse("无 EXIF 的 JPEG 应判定为无 GPS", JpegUtil.hasGpsExif(minimalJpeg()))
        // 有 EXIF 但无 GPS → false
        assertFalse("有 EXIF 无 GPS IFD 应判定为无 GPS",
            JpegUtil.hasGpsExif(jpegWithExif(exifApp1(tiffWithoutGps))))
        // GPS IFD 存在且非空 → true
        assertTrue("含非空 GPS IFD 应判定为有 GPS",
            JpegUtil.hasGpsExif(jpegWithExif(exifApp1(tiffWithGps))))
        // 大端（MM）EXIF 同样正确解析
        assertTrue("大端 EXIF 的非空 GPS IFD 应判定为有 GPS",
            JpegUtil.hasGpsExif(jpegWithExif(exifApp1(tiffWithGpsBE))))
        // GPS 指针指向空 IFD（脱敏残留形态）→ false
        assertFalse("空 GPS IFD 应判定为无 GPS",
            JpegUtil.hasGpsExif(jpegWithExif(exifApp1(tiffWithEmptyGpsIfd))))
        // 仅 XMP（无 EXIF APP1）→ false，不应误判
        val xmpOnly = JpegUtil.replaceOrInsertXmp(
            minimalJpeg(), """<x:xmpmeta><rdf:RDF/></x:xmpmeta>""")
        assertFalse("仅 XMP 的 JPEG 应判定为无 GPS", JpegUtil.hasGpsExif(xmpOnly))
    }

    @Test
    fun convertPreservesGpsExifEndToEnd() {
        // 源 JPEG 带 GPS EXIF → 转换结果 sourceHasGps=true，且输出主体仍含 GPS（无损透传）
        val dir = Files.createTempDirectory("vlc_gps").toFile()
        val jpgFooterJson = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to TEST_LIVE_ID,
            "version" to 2107
        ))
        val jpgFooter = FooterUtil.buildFooter(jpgFooterJson, TEST_LIVE_ID, FooterUtil.vivoPrefix)
        val jpg = File(dir, "IMG_8001.jpg").apply {
            writeBytes(jpegWithExif(exifApp1(tiffWithGps)) +
                fakeStreamData("DEGS", 64, 11) + jpgFooter)
        }
        File(dir, "IMG_8001.mp4").apply { writeBytes(vivoDualMp4()) }
        assertTrue(JpegUtil.hasGpsExif(jpg.readBytes()))

        val outDir = Files.createTempDirectory("vlc_gps_out").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        assertTrue("源含 GPS 时 sourceHasGps 应为 true", result.sourceHasGps)

        val (jpegs, _) = JpegUtil.splitJpegs(File(result.path).readBytes())
        assertTrue("GPS EXIF 应无损保留到输出主图", JpegUtil.hasGpsExif(jpegs[0]))
    }

    @Test
    fun convertReportsMissingGpsWhenSourceHasNone() {
        // 源 JPEG 无 GPS（或读取时已被系统脱敏）→ sourceHasGps=false，转换正常完成
        val dir = Files.createTempDirectory("vlc_nogps").toFile()
        val (jpg, _) = makePair(dir, "IMG_8002")
        val outDir = Files.createTempDirectory("vlc_nogps_out").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        assertFalse("源无 GPS 时 sourceHasGps 应为 false", result.sourceHasGps)
        assertTrue("无 GPS 不应阻塞转换", File(result.path).exists())
    }

    // ------------------------------------------------ XMP GPS 合并保留 ----------

    @Test
    fun extractGpsAttributesFromSourceXmp() {
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" " +
            "xmlns:exif=\"http://ns.adobe.com/exif/1.0/\">" +
            "<rdf:Description rdf:about=\"\" exif:GPSLatitude=\"39,54.6\" " +
            "exif:GPSLongitude=\"116,23.5\" exif:GPSAltitude=\"43.2\"/></rdf:RDF></x:xmpmeta>"
        val attrs = XmpTemplate.extractGpsAttributes(xmp)
        assertEquals("应提取出 3 个 GPS 属性", 3, attrs.size)
        assertTrue(attrs.any { it.first == "GPSLatitude" && it.second == "39,54.6" })
        assertTrue(attrs.any { it.first == "GPSLongitude" && it.second == "116,23.5" })
        assertTrue(attrs.any { it.first == "GPSAltitude" && it.second == "43.2" })
        // 无输入 / 无 GPS → 空
        assertTrue(XmpTemplate.extractGpsAttributes(null).isEmpty())
        assertTrue(XmpTemplate.extractGpsAttributes("<x:xmpmeta><rdf:RDF/></x:xmpmeta>").isEmpty())
        // 非标准前缀（stEXIF: 等）也应按属性名识别
        val prefixed = "<rdf:Description stEXIF:GPSLatitude=\"1,2.3\"/>"
        val got = XmpTemplate.extractGpsAttributes(prefixed)
        assertEquals(1, got.size)
        assertEquals("GPSLatitude", got[0].first)
    }

    @Test
    fun buildVivoSingleXmpMergesGpsWithNamespace() {
        val gps = listOf("GPSLatitude" to "39,54.6", "GPSLongitude" to "116,23.5")
        val out = XmpTemplate.buildVivoSingleXmp(1000L, null, 100, gps)
        assertTrue("应声明 exif 命名空间", out.contains("xmlns:exif=\"http://ns.adobe.com/exif/1.0/\""))
        assertTrue("应包含纬度属性", out.contains("exif:GPSLatitude=\"39,54.6\""))
        assertTrue("应包含经度属性", out.contains("exif:GPSLongitude=\"116,23.5\""))
        // 模板既有识别字段不受影响
        assertTrue(out.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(out.contains("ns.vivo.com/photos"))
        // 值中的 XML 特殊字符应转义
        val escaped = XmpTemplate.buildVivoSingleXmp(1000L, null, 100,
            listOf("GPSLatitude" to "a&b<c\"d"))
        assertTrue(escaped.contains("exif:GPSLatitude=\"a&amp;b&lt;c&quot;d\""))
        // 无 GPS 时模板与旧结构一致（无 exif 注入）
        val plain = XmpTemplate.buildVivoSingleXmp(1000L, null, 100)
        assertFalse(plain.contains("xmlns:exif"))
        assertFalse(plain.contains("exif:GPS"))
    }

    @Test
    fun convertPreservesXmpGpsEndToEnd() {
        // 源 XMP 带 GPS（部分机型把位置写进 XMP 而非 EXIF）：
        // 模板替换不得丢掉 GPS；源 XMP 无 MotionPhoto 标记，仍应识别为双文件
        val dir = Files.createTempDirectory("vlc_xmpgps").toFile()
        val sourceXmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" " +
            "xmlns:exif=\"http://ns.adobe.com/exif/1.0/\">" +
            "<rdf:Description rdf:about=\"\" exif:GPSLatitude=\"39,54.6\" " +
            "exif:GPSLongitude=\"116,23.5\"/></rdf:RDF></x:xmpmeta>"
        val jpgFooterJson = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to TEST_LIVE_ID,
            "version" to 2107
        ))
        val jpgFooter = FooterUtil.buildFooter(jpgFooterJson, TEST_LIVE_ID, FooterUtil.vivoPrefix)
        val jpg = File(dir, "IMG_8101.jpg").apply {
            writeBytes(JpegUtil.replaceOrInsertXmp(minimalJpeg(), sourceXmp) +
                fakeStreamData("DEGS", 64, 11) + jpgFooter)
        }
        File(dir, "IMG_8101.mp4").apply { writeBytes(vivoDualMp4()) }
        assertTrue("带 GPS XMP 但无 MotionPhoto 标记的源应识别为双文件",
            VivoDual.isVivoDualFile(jpg.absolutePath))

        val outDir = Files.createTempDirectory("vlc_xmpgps_out").toFile()
        val result = Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        val (jpegs, _) = JpegUtil.splitJpegs(File(result.path).readBytes())
        val outXmp = JpegUtil.findXmpSegment(jpegs[0])!!
        assertTrue("输出 XMP 应保留源 GPS 纬度", outXmp.xmpText.contains("exif:GPSLatitude=\"39,54.6\""))
        assertTrue("输出 XMP 应保留源 GPS 经度", outXmp.xmpText.contains("exif:GPSLongitude=\"116,23.5\""))
        assertTrue("输出 XMP 应声明 exif 命名空间", outXmp.xmpText.contains("xmlns:exif="))
        // vivo 实况识别字段完整保留
        assertTrue(outXmp.xmpText.contains("GCamera:MotionPhoto=\"1\""))
    }

    // ------------------------------------------------ 内存优化的等价性回归 ----------

    @Test
    fun splitJpegsRangeEqualsLegacyWholeArrayParsing() {
        // 内存优化把「先 copyOfRange 出 JPG 正文再解析」改为「整数组 + 区间解析」。
        // 必须证明两条路径结果完全一致，否则会静默改变解析边界（数据安全事故）。
        val data = vivoDualJpeg()
        val footer = FooterUtil.parseFooter(data)!!
        val bodyEnd = footer.footerStart

        val legacyBody = data.copyOfRange(0, bodyEnd)
        val legacy = JpegUtil.splitJpegs(legacyBody)
        val ranged = JpegUtil.splitJpegs(data, 0, bodyEnd)

        assertEquals("JPEG 数量应一致", legacy.first.size, ranged.first.size)
        for (i in legacy.first.indices) {
            assertTrue("第 $i 个 JPEG 应与旧路径逐字节一致",
                legacy.first[i].contentEquals(ranged.first[i]))
        }
        assertEquals("consumed 应为绝对偏移且与旧路径一致", legacy.second, ranged.second)

        // 尾部附加数据块（streamdata）取法必须一致
        val legacyStream = legacyBody.copyOfRange(legacy.second, bodyEnd)
        val newStream = data.copyOfRange(ranged.second, bodyEnd)
        assertTrue("streamdata 附加块应一致", legacyStream.contentEquals(newStream))
        assertTrue("streamdata 附加块应非空", newStream.isNotEmpty())
    }

    @Test
    fun splitJpegsRangeStopsAtRegionEndNotArrayEnd() {
        // 区间上界必须真正生效：把 EOI 之后塞入「看起来像 JPEG 头」的字节，
        // 若上界失效就会越界解析尾部数据。
        val base = minimalJpeg()
        val trailing = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(32) { 0x41 }
        val data = base + trailing
        val (jpegs, consumed) = JpegUtil.splitJpegs(data, 0, base.size)
        assertEquals("只应解析出 1 个 JPEG", 1, jpegs.size)
        assertTrue("第一个 JPEG 应与原文一致", jpegs[0].contentEquals(base))
        assertEquals("consumed 应停在区间终点", base.size, consumed)
    }

    @Test
    fun siblingMp4MatchesUppercaseExtension() {
        // JPG 侧扩展名判定用 ignoreCase，MP4 侧原先只硬拼小写——
        // 相机若写出 .MP4，该照片会被判为「非双文件」在选择器里静默消失
        val dir = Files.createTempDirectory("vlc_case").toFile()
        val jpg = File(dir, "IMG_9201.jpg").apply { writeBytes(vivoDualJpeg()) }
        File(dir, "IMG_9201.MP4").writeBytes(vivoDualMp4())

        assertEquals("大写扩展名的伴生视频应能解析到",
            "IMG_9201.MP4", File(VivoDual.siblingMp4(jpg.absolutePath)!!).name)
        assertTrue("大写扩展名也应识别为 vivo 双文件",
            VivoDual.isVivoDualFile(jpg.absolutePath))
    }

    @Test
    fun streamingXmpWriteKeepsPrimaryBytesOutsideXmpIntact() {
        // XMP 改为 [前段][新 XMP][后段] 流式写出（不再生成整图副本）。
        // 关键不变量：主图除 XMP 段以外必须逐字节原样，否则图像数据被破坏。
        // 注意样本必须**自带 XMP 段**——否则走的是「源无 XMP」的回退路径，覆盖不到本次改动
        val dir = Files.createTempDirectory("vlc_stream").toFile()
        val sourceXmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>"
        val srcPrimary = JpegUtil.replaceOrInsertXmp(minimalJpeg(), sourceXmp)
        val jpgFooterJson = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to TEST_LIVE_ID,
            "version" to 2107
        ))
        val jpgFooter = FooterUtil.buildFooter(jpgFooterJson, TEST_LIVE_ID, FooterUtil.vivoPrefix)
        val jpg = File(dir, "IMG_9301.jpg").apply {
            writeBytes(srcPrimary + fakeStreamData("DEGS", 64, 11) + jpgFooter)
        }
        File(dir, "IMG_9301.mp4").apply { writeBytes(vivoDualMp4()) }

        // 前置断言：样本确实带 XMP 且能被识别（否则测试无意义）
        assertNotNull("样本主图应带 XMP 段", JpegUtil.findXmpSegment(srcPrimary))
        assertTrue("样本应被识别为双文件", VivoDual.isVivoDualFile(jpg.absolutePath))

        val outDir = Files.createTempDirectory("vlc_stream_out").toFile()
        val outPath = Converter.convertToVivoSingle(
            jpg.absolutePath, outDir.absolutePath, ::log).path
        val out = File(outPath).readBytes()

        val srcXmp = JpegUtil.findXmpSegment(srcPrimary)!!
        val outXmp = JpegUtil.findXmpSegment(out)!!

        // ① XMP 之前逐字节一致
        assertTrue("主图 XMP 前段应逐字节保留",
            srcPrimary.copyOfRange(0, srcXmp.segStart)
                .contentEquals(out.copyOfRange(0, outXmp.segStart)))
        // ② XMP 之后（至源主图结尾）逐字节一致
        val srcAfter = srcPrimary.copyOfRange(srcXmp.segStart + srcXmp.totalLen, srcPrimary.size)
        val outAfterStart = outXmp.segStart + outXmp.totalLen
        assertTrue("主图 XMP 后段应逐字节保留",
            srcAfter.contentEquals(
                out.copyOfRange(outAfterStart, outAfterStart + srcAfter.size)))
        // ③ 新 XMP 确实是 vivo 单文件实况标记
        assertTrue(outXmp.xmpText.contains("""GCamera:MotionPhoto="1""""))
        assertTrue(outXmp.xmpText.contains("ns.vivo.com/photos"))
    }

    /** 源无 XMP 时的回退路径同样必须产出合法结果（插入而非替换） */
    @Test
    fun sourceWithoutXmpStillConvertsViaInsertPath() {
        val dir = Files.createTempDirectory("vlc_noxmp").toFile()
        val (jpg, _) = makePair(dir, "IMG_9302")
        // 前置断言：样本主图确实没有 XMP
        val src = jpg.readBytes()
        val srcFooter = FooterUtil.parseFooter(src)!!
        val (srcJpegs, _) = JpegUtil.splitJpegs(src, 0, srcFooter.footerStart)
        assertNull("该样本主图应无 XMP 段", JpegUtil.findXmpSegment(srcJpegs[0]))

        val outDir = Files.createTempDirectory("vlc_noxmp_out").toFile()
        val outPath = Converter.convertToVivoSingle(
            jpg.absolutePath, outDir.absolutePath, ::log).path
        val outXmp = JpegUtil.findXmpSegment(File(outPath).readBytes())
        assertNotNull("回退路径也应插入 vivo 单文件实况 XMP", outXmp)
        assertTrue(outXmp!!.xmpText.contains("""GCamera:MotionPhoto="1""""))
    }

    companion object {
        /** 28 字符 livephoto ID（'-<数字>' + '0' 填充） */
        private val TEST_LIVE_ID: String = "-1234567890".padEnd(28, '0')
    }
}
