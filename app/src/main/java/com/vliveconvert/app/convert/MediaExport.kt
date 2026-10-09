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

    /**
     * 重新转换的导出：优先**原地覆盖**上次导出的 MediaStore 记录
     * （本应用是文件所有者，可对已入库的自己文件直接重写内容），
     * 不产生「IMG_xxx(1).jpg」重名序号文件；旧记录已失效（被用户删除等）
     * 或覆盖失败时回退为全新导出。
     *
     * 覆盖同样做写后自检：自检不过则删除旧记录并全新导出兜底，
     * 保证「完成」的产物一定是校验通过的。
     */
    fun replaceOrExportAndVerify(
        context: Context,
        result: SingleWriteResult,
        timestamp: Long,
        relPath: String,
        useCameraDir: Boolean,
        oldUriString: String?
    ): Uri {
        val resolver = context.contentResolver
        val src = File(result.path)
        if (!src.exists() || src.length() == 0L) {
            throw IOException("转换产物缺失或为空")
        }
        if (oldUriString != null) {
            try {
                val old = Uri.parse(oldUriString)
                // 旧记录仍有效才覆盖（用户可能已手动删除产物）
                val valid = try {
                    resolver.query(old, arrayOf(MediaStore.MediaColumns._ID),
                        null, null, null)?.use { it.moveToFirst() } == true
                } catch (_: Exception) { false }
                if (valid) {
                    try {
                        resolver.openOutputStream(old, "w")?.use { out ->
                            src.inputStream().use { input -> input.copyTo(out) }
                        } ?: throw IOException("旧产物输出流不可用")
                        fixTimestamps(resolver, old, timestamp)
                        val verified = try {
                            resolver.openInputStream(old)?.use {
                                OutputVerifier.verify(it, result.segments)
                            } == true
                        } catch (_: Exception) { false }
                        if (verified) return old
                        // 覆盖后自检失败（极罕见）：删旧记录走全新导出
                        try { resolver.delete(old, null, null) } catch (_: Exception) {}
                    } catch (_: Exception) {
                        // 覆盖中途异常：旧记录可能已半损，删除后全新导出兜底
                        try { resolver.delete(old, null, null) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
                // URI 解析等异常 → 全新导出
            }
        }
        return exportAndVerify(context, result, timestamp, relPath, useCameraDir)
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

    /** DCIM/Camera 内的唯一名（与现有文件重名时追加 (n)；无冲突返回原名） */
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

    /**
     * 把已入库的导出文件移动到 DCIM/Camera（拍摄/修改时间不变）：
     * 应用是文件所有者，直接更新 RELATIVE_PATH + DISPLAY_NAME（MediaStore 原生移动，
     * 不复制数据）；失败回退：在 DCIM/Camera 插入新条目并流式复制内容，再删除原条目。
     *
     * @param preferredName 期望的文件名：无同名冲突时直接用（原名落地）；
     *        DCIM/Camera 已有同名文件（真实冲突）时自动追加序号兜底
     */
    fun moveUriToCamera(context: Context, uri: Uri, preferredName: String): Boolean {
        val resolver = context.contentResolver
        val newName = uniqueCameraName(resolver, preferredName)
        try {
            val rows = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                },
                null, null
            )
            if (rows > 0) return true
        } catch (_: Exception) {}
        var newUri: Uri? = null
        return try {
            // 目标名查询与移动之间无并发写入（应用内串行），此处仍以唯一名兜底
            newUri = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                }
            ) ?: return false
            resolver.openOutputStream(newUri, "w")?.use { out ->
                resolver.openInputStream(uri)?.use { input -> input.copyTo(out) }
            } ?: return false
            resolver.delete(uri, null, null)
            true
        } catch (_: Exception) {
            newUri?.let { u -> try { resolver.delete(u, null, null) } catch (_: Exception) {} }
            false
        }
    }
}
