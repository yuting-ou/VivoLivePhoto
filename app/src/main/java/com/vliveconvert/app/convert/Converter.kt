package com.vliveconvert.app.convert

import com.vliveconvert.app.core.SingleWriteResult
import com.vliveconvert.app.core.VivoDual
import com.vliveconvert.app.core.VivoSingle
import java.io.File

/**
 * 转换管线：vivo 双文件实况（IMG_xxx.jpg + IMG_xxx.mp4）→ vivo 单文件实况（单个 .jpg）。
 * 全程字节级无损透传：JPEG 原样拆分重组、MP4 仅剥离尾部 uuid box 并插入 lpex，不重编码。
 */
internal class ConvertException(message: String) : Exception(message)

internal object Converter {

    /**
     * 把一个 vivo 双文件实况转换为单文件实况。
     *
     * @param path 源 JPG 路径（同目录须存在同名 .mp4 伴生视频）
     * @param outDir 输出目录
     * @param log 日志回调 (level, message, tag)
     * @return 写出结果（输出路径 + 分段摘要，供导出后做写后自检）
     */
    fun convertToVivoSingle(
        path: String, outDir: String,
        log: (String, String, String) -> Unit
    ): SingleWriteResult {
        val src = File(path)
        if (!src.exists()) throw ConvertException("源文件不存在：$path")

        if (!VivoDual.isVivoDualFile(path)) {
            throw ConvertException(
                "不是 vivo 双文件实况照片（缺少伴生 .mp4 视频或实况标记）")
        }
        log("info", "识别为 vivo 双文件实况照片", "转换")

        val asset = VivoDual.read(path, log)

        val stem = src.nameWithoutExtension
        val result = VivoSingle.write(asset, outDir, stem, log)

        // 注意：这里**不能** setLastModified(源照片时间)。相册里的日期由 MediaExport
        // 显式写入 DATE_MODIFIED，暂存文件的 mtime 没有任何消费者；而 MainActivity 的
        // 残留清理按 mtime < 进程启动时间判旧——把暂存文件改成拍摄时间（必然在过去），
        // Activity 重建时就会把正在转换的产物当残留删掉（v1.7.19 修）
        return result
    }
}
