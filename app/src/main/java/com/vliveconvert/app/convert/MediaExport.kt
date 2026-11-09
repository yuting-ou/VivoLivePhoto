package com.vliveconvert.app.convert

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vliveconvert.app.core.JpegUtil
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
     * MediaStore 的拍摄坐标列。
     *
     * **这两列自 API 29（Android 10）起已废弃**：位置不再由媒体库承载，改由应用按需读取
     * 文件 EXIF（`ACCESS_MEDIA_LOCATION` 权限就是为这条读取路径而设）。因此：
     *
     * - 相册/图库的位置显示**不依赖**这两列，真正的命门是**产物文件 EXIF 里的可用经纬度**
     *   （见 `Converter.ensureExifGps` 的强判据）；
     * - 写入这里**只对少数仍在读列的老 ROM 有意义**，API 29+ 的 AOSP Provider 会忽略甚至
     *   清理它们（模拟器实测：全库 4716 行坐标列无一非空）；
     * - **不要因为「列写进去了」就认为位置保住了**，也不要把「列为空」当成位置丢失的证据。
     */
    private const val GPS_LATITUDE_COLUMN = "latitude"
    private const val GPS_LONGITUDE_COLUMN = "longitude"

    /**
     * 把坐标写进**已废弃**的媒体库列（仅老 ROM 兼容路径）。
     * 写入是否成功与位置交付无关：交付判定看文件 EXIF（见上方列定义注释）。
     */
    private fun putGpsColumns(values: ContentValues, coords: Pair<Double, Double>) {
        values.put(GPS_LATITUDE_COLUMN, coords.first)
        values.put(GPS_LONGITUDE_COLUMN, coords.second)
    }

    /**
     * 坐标一致性（EXIF 解析值 vs 媒体库列值）：EXIF 有理数与十进制度互转存在
     * 低位精度差，1e-6（约 0.1 米）内视为相同，避免重建被数值毛刺反复触发。
     */
    internal fun coordsMatch(
        a: Pair<Double, Double>?, b: Pair<Double, Double>?
    ): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> kotlin.math.abs(a.first - b.first) < 1e-6 &&
            kotlin.math.abs(a.second - b.second) < 1e-6
    }

    /** 坐标三级兜底链的解析结果（含来源溯源，供批次摘要与复用方区分） */
    internal class CoordResolution(
        val coords: Pair<Double, Double>?,
        val fromExif: Boolean,
        val fromXmp: Boolean,
        val fromVideo: Boolean
    )

    /**
     * 从**文件字节**解析坐标（EXIF → XMP → 嵌入视频段）。
     * 这是「位置体检/移动回退/导出」共用的唯一取证入口：**全部基于文件内容**，
     * 不依赖媒体库坐标列（该列自 API 29 已废弃，vivo 上读不回，不能当判据）。
     */
    internal fun resolveCoords(
        bytes: ByteArray,
        xmpText: String? = try {
            JpegUtil.findXmpSegment(bytes)?.xmpText
        } catch (_: Exception) {
            null
        }
    ): CoordResolution {
        var coords = try {
            JpegUtil.gpsCoordinates(bytes)
        } catch (_: Exception) {
            null
        }
        if (coords != null) return CoordResolution(coords, fromExif = true, fromXmp = false, fromVideo = false)
        coords = try { XmpTemplate.gpsCoordsFromXmp(xmpText) } catch (_: Exception) { null }
        if (coords != null) return CoordResolution(coords, fromExif = false, fromXmp = true, fromVideo = false)
        coords = try {
            GpsRecovery.recoverFromVideoSegment(bytes)
        } catch (_: Exception) {
            null
        }
        if (coords != null) return CoordResolution(coords, fromExif = false, fromXmp = false, fromVideo = true)
        return CoordResolution(null, fromExif = false, fromXmp = false, fromVideo = false)
    }

    /**
     * 行重建（覆盖路径专用）：vivo 对既有行的坐标列 UPDATE 静默忽略，坐标要刷新只能
     * 「删行重插」让新行出生带坐标。
     *
     * v2.6.0-test27 安全化（消除「删完插入失败导致产物消失」与「重建丢收藏」）：
     * 1. 先读旧行**全部可搬列**（含 IS_FAVORITE——重转不再静默取消收藏）
     * 2. 以**临时名 + IS_PENDING** 插入新行 → 写入全部字节 → **读回逐字节校验**
     * 3. 新行落地**之后**才删旧行（旧文件进 vivo 回收站）→ 新行改回原名
     * 任何一步失败：删除临时新行，旧行原样保留——旧产物绝不在「删除成功、
     * 新建失败」的窗口里消失。时间戳逐项保留（DATE_ADDED 依旧尽量沿用；
     * 该列在 vivo 上被系统强制改写属平台限制，见 README 已知说明）。
     */
    internal fun rebuildRowWithCoords(
        resolver: android.content.ContentResolver,
        old: Uri,
        content: ByteArray,
        timestamp: Long,
        coords: Pair<Double, Double>?,
        stats: LocationStats? = null
    ): Uri? {
        var name: String
        var relPath: String
        var dateTaken: Long
        var dateModified: Long
        var dateAdded: Long
        var favorite = 0
        try {
            resolver.query(old, arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.IS_FAVORITE
            ), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                name = c.getString(0) ?: return null
                relPath = c.getString(1) ?: return null
                dateTaken = if (c.isNull(2)) timestamp else c.getLong(2)
                dateModified = if (c.isNull(3)) timestamp / 1000 else c.getLong(3)
                dateAdded = if (c.isNull(4)) 0L else c.getLong(4)
                favorite = if (c.isNull(5)) 0 else c.getInt(5)
            } ?: return null
        } catch (_: Exception) {
            return null
        }
        if (dateTaken <= 0L) dateTaken = timestamp
        if (dateModified <= 0L) dateModified = timestamp / 1000
        if (dateAdded <= 0L) dateAdded = dateTaken / 1000

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 临时名：与旧行同目录但不撞名（旧行还占着原名）；IS_PENDING 挡住媒体扫描，
        // 避免「空文件被扫描」的竞态（此前直接 0 放行再写，扫描能看到半截文件）
        val tempName = "${name.substringBeforeLast('.')}.vlc_rebuild.jpg"
        var newUri: Uri? = null
        val newReady = try {
            newUri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, tempName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
                put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
                if (favorite != 0) put(MediaStore.MediaColumns.IS_FAVORITE, favorite)
                if (coords != null) {
                    put(GPS_LATITUDE_COLUMN, coords.first)
                    put(GPS_LONGITUDE_COLUMN, coords.second)
                }
            }) ?: return null
            resolver.openOutputStream(newUri, "w")?.use { it.write(content) } ?: return null
            // 读回逐字节校验：新行内容必须与本地一致（防写半截/写串行）
            val verified = resolver.openInputStream(newUri)?.use {
                it.readBytes().contentEquals(content)
            } == true
            if (!verified) return null
            resolver.update(newUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.SIZE, content.size.toLong())
            }, null, null)
            true
        } catch (_: Throwable) {
            // Throwable 而非 Exception：OOM 落在 insert 之后时也必须走下面的清理，
            // 否则带 IS_PENDING 的新行会随 Error 逃出（重试后再插一行 → 重复导出/孤儿行）
            false
        }
        if (!newReady) {
            // 新行没落地：删掉临时半成品，旧行原样（旧产物不丢）
            newUri?.let { u -> try { resolver.delete(u, null, null) } catch (_: Exception) {} }
            return null
        }
        val finalUri = newUri ?: return null

        // 新行已落地：删旧行（旧文件进 vivo「第三方删除拦截」回收站，可恢复），
        // 再把新行改回原名；改名失败只影响显示名，产物本身已安全
        try { resolver.delete(old, null, null) } catch (_: Exception) {}
        try {
            resolver.update(finalUri, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            }, null, null)
        } catch (_: Exception) {}
        // 时间列固化用**旧行保留值**（不是参数时间戳——那只是 dateTaken 缺失时的兜底；
        // 用错会把保留的拍摄时间覆盖成重建当下，recreateRow 回归用例正是抓这个）
        try {
            resolver.update(finalUri, ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
                put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
            }, null, null)
        } catch (_: Exception) {}
        fixPhysicalMtime(resolver, finalUri, dateTaken)
        if (stats != null) synchronized(stats) { stats.rowRebuilt++ }
        return finalUri
    }

    /** 单张导出的位置溯源统计（批次摘要据此输出，供真机研究「哪批照片坐标从哪来」） */
    internal class LocationStats {
        var exif = 0       // 坐标来自产物 EXIF
        var xmp = 0        // 坐标来自产物 XMP（部分机型把位置写进 XMP 而非 EXIF）
        var video = 0      // 坐标来自嵌入视频段（mp4 udta/©xyz）
        var none = 0       // 三处都没有（源本身无位置）
        var injected = 0   // 管线内注入 EXIF 的张数（源 EXIF 无**可用** GPS 但 XMP/视频有坐标）
        var injectedXmp = 0   // 其中坐标来自源 XMP 的张数（逐张溯源用）
        var injectedVideo = 0 // 其中坐标来自伴生视频 ©xyz 的张数
        var exifUnusable = 0 // 源 EXIF 有 GPS IFD 但**解不出可用经纬度**的张数（v2.7.0 静默丢位置形态）
        var injectFailed = 0 // 注入失败的张数（**显式失败**，绝不算进「源无 GPS」）
        var unsaved = 0    // 源有位置但产物**没有可用 EXIF GPS** 的张数（可见、可重试）
        var rowRebuilt = 0 // 覆盖路径行重建的张数（出生带坐标的确定性修复）
        val totalCoords: Int get() = exif + xmp + video
    }

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
     * @param requireGps 位置交付校验：源字节含 GPS（读取未脱敏）时，产物**文件**必须能
     *   解析出任何来源的可用坐标——「成功」的定义包含位置不失守。看的是文件 EXIF，
     *   不是已废弃的行坐标列。破损修复（糊图）不开启
     * @param stats 位置溯源统计（批次摘要输出，研究「坐标从哪来、哪张被自愈」）
     */
    fun exportAndVerify(
        context: Context,
        result: SingleWriteResult,
        timestamp: Long,
        relPath: String,
        useCameraDir: Boolean,
        oldUriString: String? = null,
        requireGps: Boolean = false,
        stats: LocationStats? = null
    ): Uri {
        val resolver = context.contentResolver
        val src = File(result.path)
        if (!src.exists() || src.length() == 0L) {
            throw IOException("转换产物缺失或为空")
        }
        // 交付前自检：暂存文件必须与转换时的分段摘要一致。覆盖路径的读回比对只能证明
        // 「拷贝忠实」——若暂存被串写，自证一致会把损坏内容交付；新建/覆盖都在此拦截。
        val stagedVerified = try {
            src.inputStream().use { OutputVerifier.verify(it, result.segments) }
        } catch (_: Exception) { false }
        if (!stagedVerified) {
            throw IOException("暂存产物自检失败（内容与转换结果不一致，已放弃本张，原图未动）")
        }
        val targetRelPath = if (useCameraDir) "DCIM/Camera" else relPath
        val stem = src.name.substringBeforeLast('.')
        val outBytes = src.readBytes()

        // 拍摄坐标三级兜底链（解析**产物文件字节**；解析结果顺手写入已废弃的媒体库
        // 坐标列——只有老 ROM 还读列，API 29+ 的位置显示由应用按需读文件 EXIF）：
        // ① EXIF（常规：源 EXIF 的 GPS 随主图字节进入产物）
        // ② XMP（部分机型把位置写进 XMP 而非 EXIF，管线已合并进产物 XMP）
        // ③ 嵌入视频段（vivo 相机在 mp4 udta/©xyz 写 ISO 6709 串，EXIF/XMP 都没有时仍在）
        val xmpText = try {
            JpegUtil.findXmpSegment(outBytes)?.xmpText
        } catch (_: Exception) {
            null
        }
        val expectedFingerprint = try {
            XmpTemplate.extractSourceFingerprint(xmpText)
        } catch (_: Exception) {
            null
        }
        val resolved = resolveCoords(outBytes, xmpText)
        val coords = resolved.coords
        // 溯源计数必须在**全部可能失败的写入成功之后**：OOM 等资源性失败会整张重试，
        // 先计会被重试再计一次（统计口径必须一次成功交付恰好计一次）
        fun countCoords() {
            if (stats != null) synchronized(stats) {
                when {
                    resolved.fromExif -> stats.exif++
                    resolved.fromXmp -> stats.xmp++
                    resolved.fromVideo -> stats.video++
                    else -> stats.none++
                }
            }
        }
        // 位置交付校验：源有位置而产物没有任何坐标来源 = 管线丢了 EXIF，绝不能当
        // 「成功」交付（原图删除等后续动作以导出成功为前提，抛出即整张按失败处理，
        // 原图不动）
        if (requireGps && coords == null) {
            throw IOException("位置校验失败：源照片有位置信息，转换产物却丢失了（已放弃本张，原图未删）")
        }

        // 覆盖既有产物（显式指定优先；否则自动识别同源既往产物）
        val previous = oldUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: findPreviousOutput(resolver, targetRelPath, stem, src.absolutePath, expectedFingerprint)
        if (previous != null) {
            // 覆盖失败（旧记录失效/重建未成）→ 落到下面的新建路径兜底
            overwriteExisting(resolver, previous, outBytes, timestamp, coords, stats)?.let {
                countCoords()
                return it
            }
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
            if (coords != null) putGpsColumns(this, coords)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
                ?: throw IOException("MediaStore insert 返回 null")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (coords == null) throw e
            // 个别老 ROM 拒绝已废弃的坐标列：去掉坐标重试一次。
            // 这两列自 API 29 废弃，位置显示不依赖它们（API 29+ 由应用按需读文件 EXIF）；
            // 真正的位置在下面写入的产物文件里，丢列不影响交付，绝不能为此丢整张照片。
            values.remove(GPS_LATITUDE_COLUMN)
            values.remove(GPS_LONGITUDE_COLUMN)
            resolver.insert(collection, values)
                ?: throw IOException("MediaStore insert 返回 null")
        }
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
        } catch (e: Throwable) {
            // Throwable：OOM 也必须先删已插行再逃出，否则孤儿行 + 重试重复导出；
            // OOM 保持原样重抛（convertOne 按 OOM 收进重试名单），其余包装成 IOException
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            if (e is OutOfMemoryError) throw e
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
        // 溯源计数：一次成功交付恰好计一次（新建路径终点；覆盖路径在 return 前已计）
        countCoords()
        return uri
    }

    /**
     * 覆盖既有产物 = **一律行重建**（删旧行重插、新行出生带坐标带内容）。
     *
     * v2.6.0-test27 简化（顺带消掉一个隐患）：
     * - 旧实现先把新内容写进旧行、再删旧行重建——同一份字节写两遍，且「写开旧行
     *   即截断」让旧产物在重建失败时已毁。现在**直接重建**：新行先落地（含读回校验），
     *   旧行后删，任何失败都留下完好的旧产物
     * - 不依赖坐标列 UPDATE（vivo 静默忽略）与坐标列查询（读不回）：坐标列自 API 29
     *   已废弃，位置的事实只在文件 EXIF 里，行为确定
     *
     * 返回新行 URI；旧行失效或重建未成返回 null（调用方落到新建路径兜底）。
     */
    private fun overwriteExisting(
        resolver: android.content.ContentResolver,
        old: Uri,
        content: ByteArray,
        timestamp: Long,
        coords: Pair<Double, Double>?,
        stats: LocationStats?
    ): Uri? {
        // 旧记录仍有效才覆盖（用户可能已手动删除产物）
        val valid = try {
            resolver.query(old, arrayOf(MediaStore.MediaColumns._ID),
                null, null, null)?.use { it.moveToFirst() } == true
        } catch (_: Exception) { false }
        if (!valid) return null
        return rebuildRowWithCoords(resolver, old, content, timestamp, coords, stats)
    }

    /** 判断既有产物是否为单文件实况（XMP 含 MotionPhoto/MicroVideo 标记）时读取的头部长度 */
    private const val XMP_SNIFF_LIMIT = 512 * 1024

    /**
     * 目标目录中「同一源照片的既往转换产物」：文件名基名与源相同（原名或 MediaStore 追加的
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
        stem: String,
        sourcePath: String,
        expectedFingerprint: String?
    ): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // LIKE 模式里的 `_` / `%` 是通配符，而 vivo 文件名形如 IMG_20260831_134354 本身就含 `_`，
        // 不转义会匹配到无关文件
        val escapedStem = stem
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        // LIKE 无法表达「括号内必须是数字」，故查询先用宽模式缩小范围、再在代码里精确校验。
        // 必要性：stem=`IMG_x` 时宽模式会匹配到 `IMG_x(1)(1).jpg`——那是另一个源
        // （`IMG_x(1).jpg`）的产物，覆盖它会毁掉别的照片的转换结果。
        val seqPattern = Regex(
            "^" + Regex.escape(stem) + "\\((\\d+)\\)\\.jpg$", RegexOption.IGNORE_CASE
        )
        val exactName = "$stem.jpg"
        val candidates = mutableListOf<Pair<Long, String>>() // id to _data
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                // RELATIVE_PATH 大小写不敏感匹配（LIKE）：本机各行的目录大小写不统一
                // （相机行 `DCIM/camera/`、应用行可能 `DCIM/Camera/`），IN 区分大小写会漏
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND (" +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\')",
                arrayOf("$targetRelPath", "$targetRelPath/%", exactName, "$escapedStem(%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    candidates.add(c.getLong(0) to (c.getString(1) ?: ""))
                }
            }
        } catch (_: Exception) {
            return null
        }
        for ((id, data) in candidates) {
            // 源文件本身永远不是「既往产物」——同名时它占用着原名。
            // 用路径直接排除，省掉对源文件（通常最大）的一次内容读取
            if (data.isNotEmpty() && data == sourcePath) continue
            if (!isSameSourceOutput(
                    resolver, ContentUris.withAppendedId(collection, id), exactName, seqPattern,
                    expectedFingerprint
                )
            ) {
                continue
            }
            return ContentUris.withAppendedId(collection, id)
        }
        return null
    }

    /**
     * 既有文件是否为「本工具产出的、属于同一源」的单文件实况：
     * 文件名必须是原名或 `基名(数字).jpg`（精确校验，排除属于其它源的嵌套括号名），
     * 且 XMP 含 MotionPhoto / MicroVideo 标记。
     */
    private fun isSameSourceOutput(
        resolver: android.content.ContentResolver,
        uri: Uri,
        exactName: String,
        seqPattern: Regex,
        expectedFingerprint: String?
    ): Boolean {
        // 先取文件名做精确校验（避免为不匹配的候选白读内容）
        val name = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null } ?: return false
        if (!name.equals(exactName, ignoreCase = true) && !seqPattern.matches(name)) return false

        return try {
            resolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(XMP_SNIFF_LIMIT)
                var n = 0
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) return false
                val xmpText = XmpTemplate.sniffXmpBytes(
                    if (n < buf.size) buf.copyOf(n) else buf
                )
                if (!XmpTemplate.parseMotionXmp(xmpText).isMotion) return false
                // 指纹比对（v2.0）：候选产物带指纹且与当前源不同 → 异源，绝不能覆盖
                // （旧版产物无指纹字段 → 保持既有行为，视作同源）
                val candidateFp = XmpTemplate.extractSourceFingerprint(xmpText)
                if (expectedFingerprint != null && candidateFp != null &&
                    candidateFp != expectedFingerprint
                ) {
                    return false
                }
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
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
                // 大小写不敏感匹配相机目录：vivo 相机行是 `DCIM/camera/`、应用行可能是
                // `DCIM/Camera/`——SQLite 的 IN 比较区分大小写，查重漏掉相机行会让插入
                // 撞上同名源文件，vivo Provider 会把冲突行整行删掉（文件成孤儿）。
                // SQLite 的 LIKE 对 ASCII 天然不区分大小写，正好覆盖两种写法
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/Camera", "DCIM/Camera/%"),
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
            // 回退复制会新建行：坐标从**文件字节**直接解析（EXIF→XMP→视频段）。
            // 不查媒体库坐标列——该列自 API 29 已废弃（vivo 上读不回，曾因此
            // carried=null，新行丢坐标，相册不显示位置）
            val carried = try {
                resolver.openInputStream(uri)?.use { ins ->
                    resolveCoords(ins.readBytes()).coords
                }
            } catch (_: Exception) { null }
            // 目标名查询与移动之间无并发写入（应用内串行），此处仍以唯一名兜底
            // 时序保护：行必须以 IS_PENDING=1 落库（对外不可见），字节写完、回读校验长度后
            // 才发布。原实现先插「可见行」再写文件——扫描/相册可能在空文件上建索引，
            // 得到一条无坐标/无尺寸的行（正是「行插入与文件落盘时序」的缺陷窗口）。
            newUri = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (carried != null) putGpsColumns(this, carried)
                }
            ) ?: return false
            resolver.openOutputStream(newUri, "w")?.use { out ->
                resolver.openInputStream(uri)?.use { input -> input.copyTo(out) }
            } ?: return false
            // 发布前从 MediaStore 回读确认字节已真正落盘（0 字节行一旦发布就是坏照片）
            val length = resolver.openInputStream(newUri)?.use { ins ->
                var n = 0L
                val buf = ByteArray(8192)
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    n += r
                }
                n
            } ?: -1L
            if (length <= 0L) throw IOException("移动回退：新行写入 0 字节")
            resolver.update(
                newUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.SIZE, length)
                },
                null, null
            )
            resolver.delete(uri, null, null)
            true
        } catch (_: Exception) {
            newUri?.let { u -> try { resolver.delete(u, null, null) } catch (_: Exception) {} }
            false
        }
    }
}
