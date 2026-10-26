package com.vliveconvert.app.convert

import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SingleWriteResult
import java.io.File

/**
 * 原地合并（实验特性）：把验证过的产物**原子替换**源 .jpg，保留原 MediaStore 行——
 * 行不动，`date_added`（加入相册时间/微信分组的依据）、`datetaken`、坐标列、相册归属
 * 全部天然保留，消灭「转换后全挤到今天」的副作用。
 *
 * 安全顺序（不可调整）：
 * 1. 产物拷进同目录 `.merging` 临时件 + fsync（同目录 rename 才是原子的）；
 * 2. **rename 之前**对临时件做完整自检——此后才允许碰源文件；
 * 3. rename 临时件覆盖源文件；
 * 4. 物理 mtime 对齐拍摄时间。
 * 任何一步失败：删临时件、返回 false、源文件字节保持不变，调用方回退标准导出。
 */
internal object InPlaceMerge {

    fun replaceSource(result: SingleWriteResult, sourceJpgPath: String, timestamp: Long): Boolean {
        val source = File(sourceJpgPath)
        if (!source.exists()) return false
        val staged = File(result.path)
        if (!staged.exists() || staged.length() == 0L) return false

        val temp = File(source.parentFile, source.name + ".merging")
        return try {
            staged.inputStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            // 自检必须在 rename 之前：rename 之后源文件已被替换，任何失败都无法回退
            val verified = temp.inputStream().use {
                OutputVerifier.verify(it, result.segments)
            }
            if (!verified) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(source)) {
                temp.delete()
                return false
            }
            source.setLastModified(timestamp)
            true
        } catch (_: Exception) {
            try { temp.delete() } catch (_: Exception) {}
            false
        }
    }
}
