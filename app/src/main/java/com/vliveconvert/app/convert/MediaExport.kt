package com.vliveconvert.app.convert

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SingleWriteResult
import com.vliveconvert.app.core.XmpTemplate
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
     * 命名策略（避免重复转换累积 `IMG_x(1).jpg` / `(2)` / `(3)`…）：
     * 1. [oldUriString] 显式指定旧产物（重转路径：队列记录了上次导出的 URI）→ 原地覆盖
     * 2. 目标目录中存在**同一源照片的既往产物** → 原地覆盖（自动识别，见 [findPreviousOutput]）
     * 3. 都没有 → 新建（重名时由 MediaStore 追加序号）
     *
     * 注意 2 不会误伤相机的原始双文件：判别依据是「同名 + XMP 含 MotionPhoto 标记」，
     * 而源双文件的 XMP 恰恰不含该标记（这正是本工具识别双文件的条件）。
     *
     * @param useCameraDir true 时写入 DCIM/Camera，false 时写入 [relPath]
     * @param oldUriString 显式指定的既有产物 URI（重转路径）
     */
    fun exportAndVerify(
        context: Context,
        result: SingleWriteResult,
        timestamp: Long,
        relPath: String,
        useCameraDir: Boolean,
        oldUriString: String? = null
    ): Uri {
        val resolver = context.contentResolver
        val src = File(result.path)
        if (!src.exists() || src.length() == 0L) {
            throw IOException("转换产物缺失或为空")
        }
        val targetRelPath = if (useCameraDir) "DCIM/Camera" else relPath
        val stem = src.name.substringBeforeLast('.')

        // 覆盖既有产物（显式指定优先；否则自动识别同源既往产物）
        val previous = oldUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: findPreviousOutput(resolver, targetRelPath, stem)
        if (previous != null) {
            overwriteExisting(resolver, previous, src, result, timestamp)?.let { return it }
            // 覆盖不可行（旧记录已失效 / 内容已不可信）→ 落到下面的新建路径
        }

        val displayName =
            if (useCameraDir) uniqueCameraName(resolver, src.name) else src.name
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
            fixPhysicalMtime(resolver, uri, timestamp)
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
     * 原地覆盖既有产物：成功且自检通过返回其 URI；不可行返回 null（调用方转新建路径）。
     *
     * 安全语义：一旦按写模式打开，旧内容即被截断，此后任何失败都必须删除该记录
     * （否则留下半损文件）；若连输出流都没打开成功，旧文件仍完好，必须保留。
     */
    private fun overwriteExisting(
        resolver: android.content.ContentResolver,
        old: Uri,
        src: File,
        result: SingleWriteResult,
        timestamp: Long
    ): Uri? {
        // 旧记录仍有效才覆盖（用户可能已手动删除产物）
        val valid = try {
            resolver.query(old, arrayOf(MediaStore.MediaColumns._ID),
                null, null, null)?.use { it.moveToFirst() } == true
        } catch (_: Exception) { false }
        if (!valid) return null

        var truncated = false
        return try {
            resolver.openOutputStream(old, "w")?.use { out ->
                truncated = true
                src.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("旧产物输出流不可用")
            fixTimestamps(resolver, old, timestamp)
            // 物理 mtime 与媒体库列同为拍摄时间（漏设会让时间漂移成写入时刻）
            fixPhysicalMtime(resolver, old, timestamp)
            val verified = try {
                resolver.openInputStream(old)?.use {
                    OutputVerifier.verify(it, result.segments)
                } == true
            } catch (_: Exception) { false }
            if (verified) return old
            // 覆盖后自检失败（极罕见）：内容已不可信，删记录后由调用方新建
            try { resolver.delete(old, null, null) } catch (_: Exception) {}
            null
        } catch (_: Exception) {
            // 仅在旧内容确实已被截断时才删除
            if (truncated) {
                try { resolver.delete(old, null, null) } catch (_: Exception) {}
            }
            null
        }
    }

    /** 判断既有产物是否为单文件实况（XMP 含 MotionPhoto/MicroVideo 标记）时读取的头部长度 */
    private const val XMP_SNIFF_LIMIT = 512 * 1024

    /**
     * 目标目录中「同一源照片的既往转换产物」：文件基名与源相同（含 MediaStore 自动追加的
     * `(n)` 形式）且本身是**单文件实况**。
     *
     * 为什么这样判：源双文件的 XMP 恰恰不含 MotionPhoto 标记（这正是本工具识别双文件的条件），
     * 而转换产物必然含——因此「同名 + 含 MotionPhoto」即可确定为既往产物，
     * 不会误伤相机的原始双文件（误判会导致覆盖源文件、造成数据丢失）。
     *
     * 找到后原地覆盖，重复转换不再累积 `IMG_x(1)/(2)/(3)…`。
     */
    private fun findPreviousOutput(
        resolver: android.content.ContentResolver,
        targetRelPath: String,
        stem: String
    ): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val ids = mutableListOf<Long>()
        // LIKE 模式里的 `_` / `%` 是通配符，而 vivo 文件名形如 IMG_20260831_134354 本身就含 `_`，
        // 不转义会匹配到无关文件（可能把别的照片误判为既往产物）。用 ESCAPE 精确匹配。
        val escapedStem = stem
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                // RELATIVE_PATH 按带/不带尾斜杠两种形态匹配（与 MainActivity.queryImagesIn 一致）：
                // 真实设备通常规范化带尾斜杠，个别实现不带
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?) AND (" +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\')",
                arrayOf("$targetRelPath/", targetRelPath, "$stem.jpg", "$escapedStem(%"),
                null
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
        } catch (_: Exception) {
            return null
        }
        for (id in ids) {
            val uri = ContentUris.withAppendedId(collection, id)
            if (isSingleFileLivePhoto(resolver, uri)) return uri
        }
        return null
    }

    /** 既有文件是否为单文件实况（XMP 含 MotionPhoto / MicroVideo 标记） */
    private fun isSingleFileLivePhoto(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Boolean = try {
        resolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(XMP_SNIFF_LIMIT)
            var n = 0
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n <= 0) false
            else XmpTemplate.parseMotionXmp(
                XmpTemplate.sniffXmpBytes(if (n < buf.size) buf.copyOf(n) else buf)
            ).isMotion
        } ?: false
    } catch (_: Exception) {
        false
    }

    /** 把入库文件对应的物理文件 mtime 固化为拍摄时间（系统「修改时间」取自 mtime） */
    private fun fixPhysicalMtime(
        resolver: android.content.ContentResolver,
        uri: Uri,
        timestamp: Long
    ) {
        try {
            val dataPath = resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (dataPath != null) {
                File(dataPath).setLastModified(timestamp)
            }
        } catch (_: Exception) {}
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
                // 按带/不带尾斜杠两种形态匹配（与 MainActivity.queryImagesIn 一致）：
                // 只匹配带尾斜杠的形态在个别实现上取不到既有文件名，序号会变得不确定
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?)",
                arrayOf("DCIM/Camera/", "DCIM/Camera"),
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
