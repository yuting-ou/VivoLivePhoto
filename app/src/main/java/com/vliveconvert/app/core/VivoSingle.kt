package com.vliveconvert.app.core

import java.io.File

/**
 * vivo 单文件实况照片写出。
 * 逻辑取自 ZLivePhoto 项目的 VivoSinglePlugin.write + OppoPlugin.buildLpexPayload，
 * 经真机实测 + 二进制逆向确认可被 vivo 相册识别。
 *
 * vivo 相册「关闭实况」时会把双文件（IMG_xxx.jpg + IMG_xxx.mp4）合并为一个 jpg：
 * 结构 = JPG 主体（Primary + GainMap）+ MP4（保留 vivoMediaEStream 实况标识、剥 vivoMediaExtInfo
 * 源 footer 包装）+ lpex box + convert footer，
 * XMP 使用 Google Container（含 MotionPhoto 视频项）并附带 VCamera 私有字段。
 *
 * 识别关键（真机实测确认）：vivo 相册同时依赖 vivoMediaEStream uuid box、
 * lpex box 与 convert footer 三者；缺 lpex 或保留 vivoMediaExtInfo 均会导致不被识别。
 */
internal object VivoSingle {

    /**
     * 写出 vivo 单文件实况（MotionPhoto="1"），返回写出结果（路径 + 分段摘要）。
     */
    fun write(
        asset: LivePhotoAsset, outDir: String, stem: String,
        log: (String, String, String) -> Unit
    ): SingleWriteResult {
        // ── 1. 处理源视频：剥 vivoMediaExtInfo（源 footer 包装），保留 vivoMediaEStream ──
        // vivo 双文件 mp4 尾部布局：
        //   [ftyp…mdat][vivoMediaEStream uuid 138B][vivoMediaExtInfo uuid 2691B(内嵌源 cameralbum footer)]
        // 两个 uuid box 性质完全不同：
        //   - vivoMediaEStream：vivo 相册识别「实况视频」的关键标识 → 必须保留
        //   - vivoMediaExtInfo：其内容即源 cameralbum footer（双文件 mp4 自带的旧 footer）→ 必须剥掉，
        //     否则视频段会内嵌一个 cameralbum footer，与尾部 convert footer 重复，破坏识别。
        var video = Mp4Util.stripVivoUuid(asset.videoMp4)

        // ── 2. 插入 lpex (LivePhotoExtension) box 到 moov ──
        // 实测「可被 vivo 相册识别」的输出视频流里带 lpex box；缺 lpex → 不被识别。
        if (video.size >= 8) {
            val searchEnd = minOf(65536, video.size)
            val lpexMarker = byteArrayOf(0x6C, 0x70, 0x65, 0x78) // "lpex"
            if (BinaryUtils.indexOf(video.copyOfRange(0, searchEnd), lpexMarker) < 0) {
                try {
                    video = Mp4Util.insertBoxIntoMoov(video, "lpex", buildLpexPayload(asset))
                    log("info", "已合成 lpex box（LivePhotoExtension）插入 moov", "vivo")
                } catch (ex: Exception) {
                    log("warning", "lpex 合成失败，跳过（不影响播放）：${ex.message}", "vivo")
                }
            }
        }

        // ── 3. vivo 相册专属 convert footer（基础字段逐字对齐「可被识别」的输出） ──
        // 并合并源双文件 footer 的附加字段（人像: moduleid/portrait、joint.refocus、
        // refocusAlgoSource 等），相册据此保留人像徽标与光圈/虚化后编辑能力。
        val imageTime = asset.effectiveImageTime()
        val footerFields = linkedMapOf<String, Any?>(
            "com.vivo.gallery.livePhoto.otherPhone.MotionRotationOffset" to 0,
            "com.android.camera.imageTime" to imageTime,
            "com.vivo.gallery.file.convert" to 10004,
            "com.vivo.gallery.livePhoto.otherPhone.MotionRotationCheck" to 1,
            "com.android.camera.livephoto" to FooterUtil.oppoFixedId,
            "version" to 2200
        )
        for (src in listOf(asset.extras["vivo_jpg_footer"], asset.extras["vivo_mp4_footer"])) {
            (src as? Map<*, *>)?.forEach { (k, v) ->
                val key = k as? String ?: return@forEach
                // 仅跳过被单文件格式固定覆盖的键；其余（含嵌套 faceInfo 等）全部透传
                if (key !in footerFields && v != null) footerFields[key] = v
            }
        }
        val footerJson = FooterUtil.buildFooterJson(footerFields)
        val footer = FooterUtil.buildFooter(footerJson, FooterUtil.oppoFixedId, FooterUtil.extPrefix)

        // ── 4. XMP：视频项 Item:Length = video + footer（已被验证可识别） ──
        // 模板会整体替换源 XMP——若源 XMP 带 GPS（部分机型把位置写进 XMP 而非 EXIF），
        // 先提取再合并进输出模板，避免位置信息随模板替换丢失
        val pts = asset.effectivePtsUs()
        val sourceXmpGps = XmpTemplate.extractGpsAttributes(
            JpegUtil.findXmpSegment(asset.primaryJpeg)?.xmpText)
        if (sourceXmpGps.isNotEmpty()) {
            log("info", "源 XMP 含 GPS 字段（${sourceXmpGps.size} 项），已合并保留位置信息", "vivo")
        }
        val xmp = XmpTemplate.buildVivoSingleXmp(
            pts, asset.gainmapLength, video.size + footer.size, sourceXmpGps)
        val primary = asset.primaryJpeg
        // 主图 XMP 段位置：命中时按 [前段][新 XMP][后段] 流式写出，
        // 不再调用 replaceOrInsertXmp 生成一份与主图等长的新数组（内存优化）
        val newXmpSeg = JpegUtil.buildXmpApp1(xmp)
        val oldXmpSeg = JpegUtil.findXmpSegment(primary)
        val primaryOutLen = if (oldXmpSeg != null)
            primary.size - oldXmpSeg.totalLen + newXmpSeg.size
        else primary.size + newXmpSeg.size

        // ── 5. 拼装输出：[JPEG+XMP][GainMap][streamdata][video(含 lpex)][convert footer] ──
        // streamdata 附加块紧跟图像数据（与双文件中的位置一致）：普通实况为流信息，
        // 人像实况为深度/虚化数据流，相册靠它保留人像徽标与光圈/虚化后编辑。
        // 顺序流式写出（不拼装全量 output 数组），降低大文件的内存峰值；
        // 同时记录每段的偏移与 MD5（导出后据此做写后自检，防静默损坏）
        val streamData = asset.extras["vivo_streamdata"] as? ByteArray ?: ByteArray(0)
        val outPath = File(outDir, "$stem.jpg").path
        File(outDir).mkdirs()
        val segments = mutableListOf<SegmentDigest>()
        var pos = 0
        File(outPath).outputStream().use { out ->
            // 只写切片、不做数组复制（out.write 带 offset/len 直接落盘，摘要也按切片算）
            fun writeSegment(name: String, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
                out.write(bytes, offset, length)
                segments.add(SegmentDigest(
                    name, pos, length, OutputVerifier.md5Of(bytes, offset, length)))
                pos += length
            }
            if (oldXmpSeg != null) {
                // 已有 XMP：按 [主图前段][新 XMP][主图后段] 三段写出
                writeSegment("primary-pre", primary, 0, oldXmpSeg.segStart)
                writeSegment("xmp", newXmpSeg)
                val after = oldXmpSeg.segStart + oldXmpSeg.totalLen
                writeSegment("primary-post", primary, after, primary.size - after)
            } else {
                // 源无 XMP（vivo 双文件极罕见）：插入会改变整体布局，回退整数组路径
                writeSegment("primary", JpegUtil.replaceOrInsertXmp(primary, xmp))
            }
            asset.gainmapJpeg?.let { writeSegment("gainmap", it) }
            if (streamData.isNotEmpty()) writeSegment("streamdata", streamData)
            writeSegment("video", video)
            writeSegment("footer", footer)
        }
        log("info", "写出 vivo 单文件实况：${File(outPath).name}" +
            "（图像 ${primaryOutLen}B + 视频 ${video.size}B（含 lpex）" +
            (if (streamData.isNotEmpty()) " + streamdata ${streamData.size}B" else "") +
            " + footer ${footer.size}B）", "vivo")
        return SingleWriteResult(
            outPath, segments,
            sourceHasGps = asset.extras["source_has_gps"] == true)
    }

    /**
     * 合成 lpex (LivePhotoExtension) box 载荷（vivo/OPPO 共用；字段逐字对齐可被相册识别的输出）。
     */
    fun buildLpexPayload(asset: LivePhotoAsset): ByteArray {
        val vi = asset.videoInfo
        val vw = (vi["width"] as? Int) ?: 0
        val vh = (vi["height"] as? Int) ?: 0
        val (iw, ih) = JpegUtil.getDimensions(asset.primaryJpeg)

        val payload = linkedMapOf<String, Any?>(
            "coverFramePts" to asset.effectivePtsUs(),
            "cropRect" to intArrayOf(0, 0, vw, vh),
            "desc" to "OppoMotionVideoExt",
            "matrixCount" to 0,
            "originPhotoSize" to intArrayOf(iw, ih),
            "photoCropFactor" to 1.0,
            "photoCropMatrix" to doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            "photoCropRect" to intArrayOf(0, 0, iw, ih),
            "photoEisCropFactor" to doubleArrayOf(1.0, 1.0),
            "photoEisMatrix" to doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            "subVideoScaleFactor" to 0.5,
            "version" to 1,
            "videoOrientation" to ((vi["rotation"] as? Int) ?: 0),
            "videoSize" to intArrayOf(vw, vh)
        )
        val jsonBytes = FooterUtil.buildFooterJson(payload)
        val prefix = "LivePhotoExtension".toByteArray(Charsets.US_ASCII)
        return prefix + jsonBytes
    }
}
