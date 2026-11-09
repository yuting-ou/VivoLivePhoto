package com.vliveconvert.app.convert

import com.vliveconvert.app.core.BrokenVerdict
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.LivePhotoAsset
import com.vliveconvert.app.core.SingleWriteResult
import com.vliveconvert.app.core.VivoDual
import com.vliveconvert.app.core.VivoSingle
import com.vliveconvert.app.core.XmpTemplate
import java.io.File
import java.security.MessageDigest

/**
 * 转换管线：vivo 双文件实况（IMG_xxx.jpg + IMG_xxx.mp4）→ vivo 单文件实况（单个 .jpg）。
 * 全程字节级无损透传：JPEG 原样拆分重组、MP4 仅剥离尾部 uuid box 并插入 lpex，不重编码。
 *
 * 回退分发（议题 #2 破损修复）：正常双文件之外，「相机目录 + vivo EXIF + 无 footer +
 * MP4 moov 完整」的破损对（sourceFormat="vivo_broken"）走同一写出管线——不提升分辨率、
 * 不重编码；MP4 缺 moov（拍摄中断截断）明确报「无法修复：视频损坏」，不做半成品转换。
 *
 * v2.6.0-test27 位置保障（治本重构）：
 * - GPS 注入从「转换前改源副本」（test25，带 cache 副本/符号链接/双重读盘与多个静默
 *   失败点）改为**管线内内存注入**：读源 → 对内存中的主图判定/注入 → 再写出。
 *   无临时副本、无符号链接、无额外读盘，失败路径全部显式（见 SingleWriteResult 新字段）
 * - 源指纹改为对**注入前的原始主图**计算：重转时既往产物指纹一致，可原地覆盖，
 *   不再因注入被判「异源」而生成 IMG_x(1).jpg 副本
 */
internal class ConvertException(message: String) : Exception(message)

internal object Converter {

    private val jobSeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 把一个 vivo 双文件实况转换为单文件实况（含破损实况的修复合并）。
     *
     * @param path 源 JPG 路径（同目录须存在同名 .mp4 伴生视频）
     * @param outDir 输出目录
     * @param log 日志回调 (level, message, tag)
     * @return 写出结果（输出路径 + 分段摘要 + 位置链路明细，供导出后做写后自检）
     */
    fun convertToVivoSingle(
        path: String, outDir: String,
        log: (String, String, String) -> Unit
    ): SingleWriteResult {
        val src = File(path)
        if (!src.exists()) throw ConvertException("源文件不存在：$path")

        val asset = if (VivoDual.isVivoDualFile(path)) {
            log("info", "识别为 vivo 双文件实况照片", "转换")
            VivoDual.read(path, log)
        } else {
            when (VivoDual.brokenVerdict(path)) {
                BrokenVerdict.REPAIRABLE -> {
                    log("info", "识别为破损双文件实况（无 footer）：仅修复合并，画质无法还原", "转换")
                    VivoDual.read(path, log)
                }
                BrokenVerdict.VIDEO_CORRUPT ->
                    throw ConvertException(
                        "无法修复：视频损坏（拍摄中断导致视频截断，缺少关键数据），该文件对不做转换")
                BrokenVerdict.NOT_BROKEN ->
                    throw ConvertException(
                        "不是 vivo 双文件实况照片（缺少伴生 .mp4 视频或实况标记）")
            }
        }

        // 源指纹必须在任何改动之前计算：对**原始主图**（未注入 GPS 的字节）。
        // 写入产物 XMP 的 vlp:sourceFingerprint 据此判定「同源才覆盖」——
        // 注入后指纹不变，重转才能继续原地覆盖既往产物
        asset.extras["source_fingerprint"] = sha256Hex16(asset.primaryJpeg)
        ensureExifGps(asset, log)

        val stem = src.nameWithoutExtension
        // 每次转换独立暂存子目录：不同相册的同名 IMG_xxx.jpg 并发时互不覆盖
        val jobDir = File(outDir, "job_${System.nanoTime()}_${jobSeq.incrementAndGet()}")
            .apply { mkdirs() }
        val result = VivoSingle.write(asset, jobDir.absolutePath, stem, log)

        // 注意：这里**不能** setLastModified(源照片时间)。相册里的日期由 MediaExport
        // 显式写入 DATE_MODIFIED，暂存文件的 mtime 没有任何消费者；而 MainActivity 的
        // 残留清理按 mtime < 进程启动时间判旧——把暂存文件改成拍摄时间（必然在过去），
        // Activity 重建时就会把正在转换的产物当残留删掉（v1.7.19 修）
        return result
    }

    /**
     * 位置保障（管线内、内存级、显式失败）：
     * 源主图无 EXIF GPS 但 XMP / 视频 ©xyz 有坐标时，先把坐标注入主图 EXIF 再写出——
     * vivo 的媒体扫描按**文件 EXIF** 重算行的坐标列，产物带 EXIF GPS 是唯一
     * 经真机验证能扛住扫描的形态（「找回位置」行重建 200+ 次存活的反证）。
     *
     * 判定对**完整主图**（不是前 1MB 头部快照）：杜绝段跨界误判。
     * 坐标来源与注入结果写入 asset.extras（SingleWriteResult 透传给上层摘要）：
     * - gps_coords_source: "xmp" / "video" / "none"
     * - gps_injected: true 注入成功
     * - gps_inject_failed: 失败原因（显式，绝不算作「源无 GPS」）
     * - source_has_location: 源在 EXIF/XMP/视频任一处有位置（交付校验的依据）
     */
    private fun ensureExifGps(asset: LivePhotoAsset, log: (String, String, String) -> Unit) {
        val primary = asset.primaryJpeg
        val exifCoords = try {
            JpegUtil.gpsCoordinates(primary)
        } catch (_: Exception) {
            null
        }
        if (exifCoords != null) {
            asset.extras["gps_coords_source"] = "exif"
            asset.extras["source_has_location"] = true
            return
        }
        // 「GPS IFD 非空」≠「有可用经纬度」：只有 GPSLatitudeRef/GPSVersionID、
        // 三元组不完整、全 0,0、类型不对都会让 hasGpsExif=true 而 gpsCoordinates=null。
        // 这类文件媒体扫描写不进行坐标（相册不显示位置），必须继续从 XMP/视频恢复
        // 并**覆盖**那段无效 EXIF——v2.7.0 在这里提前 return 是本轮零星丢位置的根因。
        val hadUnusableGpsIfd = try {
            JpegUtil.hasGpsExif(primary)
        } catch (_: Exception) {
            false
        }
        if (hadUnusableGpsIfd) asset.extras["gps_exif_unusable"] = true
        val sourceXmp = try {
            JpegUtil.findXmpSegment(primary)?.xmpText
        } catch (_: Exception) {
            null
        }
        val xmpCoords = try {
            XmpTemplate.gpsCoordsFromXmp(sourceXmp)
        } catch (_: Exception) {
            null
        }
        val videoCoords = if (xmpCoords == null) {
            try { GpsRecovery.recoverFromMp4Bytes(asset.videoMp4, asset.videoByteLength) } catch (_: Exception) { null }
        } else {
            null
        }
        val coords = xmpCoords ?: videoCoords
        asset.extras["gps_coords_source"] = when {
            xmpCoords != null -> "xmp"
            videoCoords != null -> "video"
            else -> "none"
        }
        // 「XMP 有 GPS 字段但解析不出坐标」与「源无坐标」必须分开——前者是
        // **位置未保住**（可见缺陷、可重试），后者才是「源无 GPS」。
        // 把原始字段写进日志：哪种机型的哪种形态没覆盖到，日志/诊断报告一看便知
        if (coords == null) {
            val rawGps = try {
                XmpTemplate.extractGpsAttributes(sourceXmp)
            } catch (_: Exception) {
                emptyList()
            }
            if (rawGps.isNotEmpty()) {
                asset.extras["gps_xmp_unparsed"] = true
                asset.extras["source_has_location"] = true
                log("warning",
                    "源 XMP 含 GPS 字段但解析失败（${rawGps.joinToString { "${it.first}=${it.second}" }}）" +
                        "——按「位置未保住」处理；请用「找回位置」导出诊断报告回传该形态",
                    "转换")
                return
            }
        }
        asset.extras["source_has_location"] = coords != null
        if (coords == null) return // 三处都没有：源确实无位置（摘要据实报「源无 GPS」）
        when (val r = GpsRecovery.injectGpsExif(primary, coords)) {
            is GpsRecovery.InjectResult.Ok -> {
                asset.primaryJpeg = r.bytes
                asset.extras["gps_injected"] = true
                asset.extras["source_has_gps"] = true
                val src = if (xmpCoords != null) "源 XMP" else "伴生视频 ©xyz"
                if (hadUnusableGpsIfd) {
                    asset.extras["gps_exif_replaced"] = true
                    log("info",
                        "源 EXIF 的 GPS 段非空但解不出经纬度：已用${src}的坐标覆盖，" +
                            "产物 EXIF 现在可被媒体扫描写入行坐标",
                        "转换")
                } else {
                    log("info", "源 EXIF 无 GPS：已从${src}恢复坐标并注入主图 EXIF（产物天生带位置）", "转换")
                }
            }
            is GpsRecovery.InjectResult.Fail -> {
                // 显式失败：上层必须把本张标记为「位置未保住」（可见、可重试），
                // 绝不允许计入「源本身不含 GPS」蒙混过去
                asset.extras["gps_inject_failed"] = r.reason
                log("warning", "GPS 注入 EXIF 失败：${r.reason}——本张将标记「位置未保住」，可重试", "转换")
            }
        }
    }

    /** SHA-256 前 16 位 hex（源指纹：异源同名不覆盖的依据） */
    private fun sha256Hex16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)
}
