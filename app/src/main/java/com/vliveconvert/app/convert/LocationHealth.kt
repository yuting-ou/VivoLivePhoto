package com.vliveconvert.app.convert

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.XmpTemplate

/**
 * 位置体检与修复引擎（v2.6.0-test27 重做「找回位置」的核心）。
 *
 * 判定量具设计（不依赖媒体库坐标列——test24~26 已证该列在 vivo 上读不回、
 * 不能当任何成败判据）：
 *
 * | 量具 | 读什么 | 置信度 | 耗时 | 局限 |
 * |---|---|---|---|---|
 * | 产物文件 EXIF GPS | 文件头部字节（JpegUtil.gpsCoordinates） | **最高**：vivo 媒体扫描按文件 EXIF 重算坐标列，这是唯一能扛住扫描的形态 | 头 256KB，毫秒级 | 只证「文件带坐标」 |
 * | 产物 XMP GPS | 文件头部 XMP APP1 | 高（辅助）：部分机型把位置写进 XMP | 同上 | 相册不一定认 |
 * | 视频段 ©xyz | EOI 之后的嵌入视频（ISO 6709，范围校验） | 中（兜底）：vivo 相机始终写入 | 需读全量 | 字符串形态，需校验防误命中 |
 * | 媒体库**只读**列（名称/时间/大小） | MediaStore query | 元数据参考 | 快 | **坐标列一律不读**（读不回，不作判据） |
 *
 * 坐标列只在「写入」时使用（插入带坐标是真机验证过的显示保证），绝不读回验证。
 */
internal object LocationHealth {

    /** 判定结论：坐标的最可靠载体在哪一层 */
    enum class Verdict {
        /** 产物文件 EXIF 带 GPS（能扛住媒体扫描，无需修复） */
        HAS_EXIF,
        /** EXIF 无、XMP 有（行坐标靠不住，需写回 EXIF） */
        XMP_ONLY,
        /** EXIF/XMP 都无、嵌入视频段有（同上，需写回 EXIF） */
        VIDEO_ONLY,
        /** 三处都没有（源本身无位置，无可修） */
        NONE,
        /** 读文件失败，无法判定 */
        UNREADABLE
    }

    /** 一张照片的位置探针（三个来源的解析值与判定） */
    data class Probe(
        val exif: Pair<Double, Double>?,
        val xmp: Pair<Double, Double>?,
        val video: Pair<Double, Double>?,
        /** 读文件失败：判定结论是「无法判定」，不是「无坐标」（无坐标是读完后的结论） */
        val unreadable: Boolean = false
    ) {
        val verdict: Verdict
            get() = when {
                unreadable -> Verdict.UNREADABLE
                exif != null -> Verdict.HAS_EXIF
                xmp != null -> Verdict.XMP_ONLY
                video != null -> Verdict.VIDEO_ONLY
                else -> Verdict.NONE
            }

        /** 最可信坐标（EXIF > XMP > 视频段） */
        val best: Pair<Double, Double>? get() = exif ?: xmp ?: video

        /** 判定依据（报告与 UI 展示用）：每个来源解析到了什么 */
        fun evidence(): String {
            val parts = mutableListOf<String>()
            parts.add(exif?.let { "EXIF=${fmt(it)}" } ?: "EXIF=无")
            parts.add(xmp?.let { "XMP=${fmt(it)}" } ?: "XMP=无")
            parts.add(video?.let { "视频段=${fmt(it)}" } ?: "视频段=无")
            return parts.joinToString("，")
        }

        val verdictLabel: String
            get() = when (verdict) {
                Verdict.HAS_EXIF -> "有位置"
                Verdict.XMP_ONLY -> "EXIF 缺失"
                Verdict.VIDEO_ONLY -> "EXIF 缺失"
                Verdict.NONE -> "无坐标"
                Verdict.UNREADABLE -> "读失败"
            }

        private fun fmt(c: Pair<Double, Double>): String =
            "%.5f,%.5f".format(c.first, c.second)
    }

    /** 头部快查（EXIF/XMP 都在文件头部）：扫描热路径用，不读全量 */
    fun probeHead(head: ByteArray): Probe {
        val exif = try { JpegUtil.gpsCoordinates(head) } catch (_: Exception) { null }
        val xmp = if (exif == null) try {
            XmpTemplate.gpsCoordsFromXmp(JpegUtil.findXmpSegment(head)?.xmpText)
        } catch (_: Exception) {
            null
        } else {
            null
        }
        return Probe(exif, xmp, null)
    }

    /** 深度探针（含嵌入视频段）：需要文件全量字节 */
    fun probeFull(bytes: ByteArray): Probe {
        val head = probeHead(bytes)
        val video = if (head.exif == null && head.xmp == null) try {
            GpsRecovery.recoverFromVideoSegment(bytes)
        } catch (_: Exception) {
            null
        } else {
            null
        }
        return Probe(head.exif, head.xmp, video)
    }

    /** 修复结果（每条都必须可解释：走了哪条路径、读回验证过没有、败在哪） */
    enum class RepairResult {
        /** 修复成功且**读回验证通过**（EXIF GPS 已在产物里） */
        OK,
        /** 三处都无坐标，无可恢复（不动记录，不算失败） */
        NO_COORDS,
        /** 失败（读文件/注入/重建/验证任一环节），error 带原因 */
        FAILED
    }

    data class RepairOutcome(
        val result: RepairResult,
        /** 实际走的修复路径（如「仅行重建」「注入EXIF+行重建」） */
        val action: String,
        /** 修复后读回验证（文件事实，非坐标列查询） */
        val verified: Boolean,
        val coords: Pair<Double, Double>?,
        val error: String?
    )

    /**
     * 修复单个产物（让 vivo 相册能显示位置）：
     * 1. 读全量字节 → 深度探针（EXIF/XMP/视频段）
     * 2. 有坐标但 EXIF 缺 → 把坐标**写回文件 EXIF**（[GpsRecovery.injectGpsExif]，
     *    视频段逐字节保留）——媒体扫描重算坐标列的唯一长效依据
     * 3. 行重建（[MediaExport.rebuildRowWithCoords]）：临时名先建新行、读回校验、
     *    旧行后删，IS_FAVORITE 等可搬列随迁
     * 4. **读回验证**：重新读产物文件，必须能解析出 EXIF GPS——验证不过按失败记，
     *    绝不宣告成功
     */
    fun repairProduct(context: Context, uri: Uri): RepairOutcome {
        val resolver = context.contentResolver
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return RepairOutcome(RepairResult.FAILED, "读取产物", false, null, "无法读取产物文件")

        val probe = probeFull(bytes)
        val coords = probe.best
            ?: return RepairOutcome(RepairResult.NO_COORDS, "深度探针", false, null, null)

        var outBytes = bytes
        var action = "仅行重建"
        if (probe.exif == null) {
            when (val r = GpsRecovery.injectGpsExif(bytes, coords)) {
                is GpsRecovery.InjectResult.Ok -> {
                    outBytes = r.bytes
                    action = "注入EXIF+行重建"
                }
                is GpsRecovery.InjectResult.Fail ->
                    return RepairOutcome(RepairResult.FAILED, "注入EXIF", false, coords, r.reason)
            }
        }

        val rebuilt = try {
            MediaExport.rebuildRowWithCoords(
                resolver, uri, outBytes, System.currentTimeMillis(), coords, null)
        } catch (_: Exception) {
            null
        } ?: return RepairOutcome(RepairResult.FAILED, action, false, coords, "行重建失败")

        // 读回验证（文件事实）：修复后的产物必须解析得出 EXIF GPS
        val verified = try {
            resolver.openInputStream(rebuilt)?.use { ins ->
                JpegUtil.gpsCoordinates(ins.readBytes()) != null
            } == true
        } catch (_: Exception) {
            false
        }
        return if (verified) {
            RepairOutcome(RepairResult.OK, action, true, coords, null)
        } else {
            RepairOutcome(RepairResult.FAILED, action, false, coords,
                "修复后读回验证失败（产物仍解析不出 EXIF GPS）")
        }
    }

    /** 扫描/修复一行的报告记录（导出诊断报告用） */
    data class ReportLine(
        val name: String,
        val verdict: String,
        val evidence: String,
        val repair: String? = null
    ) {
        fun render(): String = buildString {
            append("[").append(name).append("] 判定=").append(verdict)
            append("；").append(evidence)
            repair?.let { append(" → ").append(it) }
        }
    }

    /** 生成可回传的诊断报告（每张产物的判定依据 + 修复路径与结果） */
    fun buildReport(
        appVersion: String,
        device: String,
        rows: List<ReportLine>,
        summary: String
    ): String = buildString {
        appendLine("影中影 位置诊断报告")
        appendLine("版本：$appVersion")
        appendLine("设备：$device")
        appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("说明：判定全部基于产物文件字节（EXIF/XMP/视频段），不依赖媒体库坐标列")
        appendLine("————")
        rows.forEach { appendLine(it.render()) }
        appendLine("————")
        appendLine(summary)
    }
}
