package com.vliveconvert.app.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SingleWriteResult
import java.io.File
import java.io.IOException

/**
 * MediaStore 导出 + 写后自检：
 * 导出完成后把最终入库的文件完整回读一遍，按转换时记录的分段摘要逐段比对 MD5——
 * 校验不通过即删除入库记录并抛异常（该项标记失败），
 * 确保标记「完成」/进入删除原图流程的文件一定是完好无损的。
 */
internal object MediaExport {

    /**
     * 导出转换产物到系统相册并做写后自检，返回入库 URI。
     *
     * @param useCameraDir true 时直接写入 DCIM/Camera（与相机拍摄照片同目录，重名追加序号）
     * @param relPath useCameraDir 为 false 时的自定义输出目录（MediaStore 相对路径）
     */
    fun exportAndVerify(
        context: Context,
        result: SingleWriteResult,
        timestamp: Long,
        relPath: String,
        useCameraDir: Boolean
    ): Uri {
        val resolver = context.contentResolver
        val src = File(result.path)
        if (!src.exists() || src.length() == 0L) {
            throw IOException("转换产物缺失或为空")
        }
        val targetRelPath = if (useCameraDir) "DCIM/Camera" else relPath
        val displayName = if (useCameraDir) uniqueCameraName(resolver, src.name) else src.name
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_MODIFIED, timestamp / 1000)
            put(MediaStore.MediaColumns.DATE_TAKEN, timestamp)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert 返回 null")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                src.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("MediaStore 输出流不可用")

            // 部分设备会在写入完成后用真实写入时间覆盖拍摄时间，固化一次
            fixTimestamps(resolver, uri, timestamp)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )

            // 物理文件的 mtime 也固化为拍摄时间：系统显示的「修改时间」来自文件 mtime，
            // 只改 MediaStore 列的话会在媒体扫描时被文件 mtime 覆盖回写入时刻
            try {
                val dataPath = resolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (dataPath != null) {
                    File(dataPath).setLastModified(timestamp)
                }
            } catch (_: Exception) {}
            fixTimestamps(resolver, uri, timestamp)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw IOException("写入相册失败：${e.message}")
        }

        // 写后自检：完整回读入库文件，分段 MD5 与转换时摘要逐一比对（流式，O(1) 内存）
        val verified = try {
            resolver.openInputStream(uri)?.use { OutputVerifier.verify(it, result.segments) } == true
        } catch (_: Exception) {
            false
        }
        if (!verified) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw IOException("输出自检失败（导出文件与转换结果不一致，已回滚本次导出）")
        }
        return uri
    }

    private fun fixTimestamps(resolver: android.content.ContentResolver, uri: Uri, timestamp: Long) {
        try {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_MODIFIED, timestamp / 1000)
                    put(MediaStore.MediaColumns.DATE_TAKEN, timestamp)
                },
                null, null
            )
        } catch (_: Exception) {}
    }

    /** DCIM/Camera 内的唯一名（与现有文件重名时追加 (n)） */
    fun uniqueCameraName(
        resolver: android.content.ContentResolver,
        displayName: String
    ): String {
        val taken = HashSet<String>()
        try {
            resolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("DCIM/Camera/"),
                null
            )?.use { c ->
                val i = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) taken.add(c.getString(i) ?: "")
            }
        } catch (_: Exception) {}
        if (displayName !in taken) return displayName
        val stem = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val cand = if (ext.isEmpty()) "$stem($i)" else "$stem($i).$ext"
            if (cand !in taken) return cand
            i++
        }
    }
}
