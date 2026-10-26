package com.vliveconvert.app.convert

import com.vliveconvert.app.core.BrokenVerdict
import com.vliveconvert.app.core.SingleWriteResult
import com.vliveconvert.app.core.VivoDual
import com.vliveconvert.app.core.VivoSingle
import java.io.File

/**
 * 转换管线：vivo 双文件实况（IMG_xxx.jpg + IMG_xxx.mp4）→ vivo 单文件实况（单个 .jpg）。
 * 全程字节级无损透传：JPEG 原样拆分重组、MP4 仅剥离尾部 uuid box 并插入 lpex，不重编码。
 *
 * 回退分发（议题 #2 破损修复）：正常双文件之外，「相机目录 + vivo EXIF + 无 footer +
 * MP4 moov 完整」的破损对（sourceFormat="vivo_broken"）走同一写出管线——不提升分辨率、
 * 不重编码；MP4 缺 moov（拍摄中断截断）明确报「无法修复：视频损坏」，不做半成品转换。
 */
internal class ConvertException(message: String) : Exception(message)

internal object Converter {

    /**
     * 把一个 vivo 双文件实况转换为单文件实况（含破损实况的修复合并）。
     *
     * @param path 源 JPG 路径（同目录须存在同名 .mp4 伴生视频）
     * @param outDir 输出目录
     * @param log 日志回调 (level, message, tag)
     * @return 写出结果（输出路径 + 分段摘要 + sourceFormat，供导出后做写后自检）
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

        val stem = src.nameWithoutExtension
        val result = VivoSingle.write(asset, outDir, stem, log)

        // 注意：这里**不能** setLastModified(源照片时间)。相册里的日期由 MediaExport
        // 显式写入 DATE_MODIFIED，暂存文件的 mtime 没有任何消费者；而 MainActivity 的
        // 残留清理按 mtime < 进程启动时间判旧——把暂存文件改成拍摄时间（必然在过去），
        // Activity 重建时就会把正在转换的产物当残留删掉（v1.7.19 修）
        return result
    }
}
