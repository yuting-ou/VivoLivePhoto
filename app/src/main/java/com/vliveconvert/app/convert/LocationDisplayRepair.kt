package com.vliveconvert.app.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.vliveconvert.app.core.JpegUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「重建已有照片的位置显示」：让历史转换产物在 vivo 相册里显示拍摄位置。
 *
 * 机制（2026-10 真机结论）：
 * - vivo 相册的位置显示只认「出生自带坐标」的媒体库记录（相机拍照时由系统写入）；
 *   对已存在的记录事后 UPDATE 坐标列会被系统静默忽略（返回 0 行、无异常）。
 * - 实测新插入带坐标的行能正常显示（v2.0.1 前向修复）。所以存量修复 = **行重建**：
 *   读出文件全部字节与元数据 → 删除旧记录（文件进 vivo「第三方删除拦截」回收站，
 *   可恢复）→ 以同名/同目录/同时间戳 + 坐标重新插入 → 字节原样写回。
 * - 若文件字节无 EXIF GPS（老版本转换时被系统读取层剥掉）：从产物**自身嵌入的
 *   视频段**恢复坐标（vivo 相机在 mp4 的 udta/©xyz 写 ISO 6709 串），写回 EXIF
 *   后再重建（切出 JPEG 单独处理，尾部视频逐字节保留）。
 *
 * 分批与空间守卫：重建会在回收站留下一份副本（约等于产物总体积），本工具在
 * 可用空间低于 [MIN_FREE_BYTES] 时自动暂停，提示用户清空回收站后继续；
 * 已处理文件名持久化，可中断续跑。
 */
internal object LocationDisplayRepair {

    /** 应用产物的 XMP 签名（vivo 模板独有；相机原始双文件不含） */
    private val APP_OUTPUT_MARKER: ByteArray = "VCamera:VMediaKitVersion".toByteArray()

    /** 头部嗅探长度：EXIF 与 XMP 都在文件头部，512KB 足够 */
    private const val SNIFF_LIMIT = 512 * 1024

    /** 可用空间低于该值自动暂停（回收站副本持续增长，给系统留足余量） */
    private const val MIN_FREE_BYTES = 10L * 1024 * 1024 * 1024

    /** 进度持久化文件名：每行一个「已处理」的显示名（含非产物，避免每批重嗅探） */
    private const val PROGRESS_FILE = "location_rebuild_done.txt"

    data class RebuildStats(
        /** 本轮扫描到的图片数 */
        val scanned: Int,
        /** 本轮成功重建的产物数 */
        val rebuilt: Int,
        /** 重建失败（IO/校验失败）的产物数 */
        val failed: Int,
        /** 无坐标且视频段也恢复不出的产物数（跳过，不删记录） */
        val noCoords: Int,
        /** 因可用空间不足而暂停 */
        val pausedForSpace: Boolean,
        /** 仍未处理的图片数（含非产物） */
        val remaining: Int
    )

    /** 已处理名单（跨批/跨会话续跑）；不存在的文件返回空集 */
    fun loadProcessed(context: Context): MutableSet<String> {
        val f = File(context.filesDir, PROGRESS_FILE)
        return try {
            if (f.exists()) f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.toMutableSet()
            else mutableSetOf()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    fun saveProcessed(context: Context, processed: Set<String>) {
        try {
            File(context.filesDir, PROGRESS_FILE).writeText(
                processed.joinToString("\n"), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun clearProcessed(context: Context) {
        try { File(context.filesDir, PROGRESS_FILE).delete() } catch (_: Exception) {}
    }

    /** 扫描结果：一条「应用产物、媒体库无坐标」的记录（vivo 相册不显示位置的那批） */
    data class MissingRow(val uri: Uri, val name: String, val dateTaken: Long)

    /**
     * 扫描嗅探长度：XMP 产物标记在头部 APP1 段（EXIF 缩略图之后、SOS 之前），
     * 128KB 足够命中；比 [SNIFF_LIMIT] 小 4 倍——相机相册动辄上万张，
     * 全量 512KB 嗅探既慢又产生大量短命大对象。
     */
    private const val SCAN_SNIFF_LIMIT = 128 * 1024

    /** 嗅探并行度：IO 密集，4 路已能打满 FUSE 读取，再高只会抢主线程 CPU */
    private const val SCAN_PARALLELISM = 4

    /**
     * 扫描相机相册里「应用产物、媒体库无坐标」的记录。
     *
     * 与 [rebuildAll] 的全量扫不同：只把**真正缺坐标**的产物报给调用方——
     * 用户看得到、选得出，重建只动选中的那几张，不再全库跑批。
     *
     * 需要位置权限：未授予时系统把坐标列抹成 null，会把全部产物误报为缺坐标
     * （调用方负责先确认权限再扫描）。
     *
     * 性能纪律（19000 张实机教训）：嗅探限 [SCAN_SNIFF_LIMIT] 且 4 路并行；
     * [loadProcessed] 名单里的名字直接跳过——已重建的（现在有坐标，查询就排除了）、
     * 已标记无坐标的（重建也无用）、已确认非产物的，都不该再耗一次嗅探。
     * 结果按拍摄时间倒序（新的在前，与相册时间线一致）。
     */
    suspend fun scanMissingLocation(
        context: Context,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<MissingRow> {
        val resolver = context.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val processed = loadProcessed(context)

        data class Row(val uri: Uri, val name: String, val dateTaken: Long)
        val rows = ArrayList<Row>()
        try {
            resolver.query(
                images,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    "latitude",
                    "longitude"
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/Camera", "DCIM/Camera/%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    // 有任一坐标列就视为相册能显示位置，跳过（重建只治「不显示」的那批）
                    if (!c.isNull(3) || !c.isNull(4)) continue
                    val name = c.getString(1) ?: continue
                    if (name in processed) continue
                    val dateTaken = if (c.isNull(2)) 0L else c.getLong(2)
                    rows.add(Row(ContentUrisWithId(images, c.getLong(0)), name, dateTaken))
                }
            }
        } catch (_: Exception) {}

        // 嗅探是逐文件开流读头部（IO 密集）：并行 4 路把上万张的扫描压到原来的 ~1/4；
        // 进度用原子计数，结果收进并发队列后统一排序
        val missing = Collections.synchronizedList(ArrayList<MissingRow>())
        val sniffed = AtomicInteger(0)
        val sem = Semaphore(SCAN_PARALLELISM)
        coroutineScope {
            for (row in rows) {
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            val header = readHeader(resolver, row.uri, SCAN_SNIFF_LIMIT) ?: return@withPermit
                            // 非本工具产物（相机原始双文件等）不在修复范围，嗅探后跳过
                            if (containsBytes(header, APP_OUTPUT_MARKER)) {
                                missing.add(MissingRow(row.uri, row.name, row.dateTaken))
                            }
                        } catch (_: Exception) {
                        } finally {
                            onProgress(sniffed.incrementAndGet(), rows.size)
                        }
                    }
                }
            }
        }
        onProgress(rows.size, rows.size)
        return missing.sortedWith(
            compareByDescending<MissingRow> { it.dateTaken }.thenByDescending { it.name })
    }

    /**
     * 只重建传入的记录（选中式修复）：逐张沿用 [recreateRow]，
     * 已处理的记入 [processed]（与全量扫共享续跑名单，后续运行不会再碰）。
     * 空间不足即停（重建会在回收站留副本）：返回 pausedForSpace=true，
     * 未处理的条目仍在扫描结果里，清理空间后重新扫描即可续跑。
     */
    fun rebuildSelected(
        context: Context,
        rows: List<MissingRow>,
        processed: MutableSet<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        freeSpaceBytes: () -> Long = { defaultFreeSpaceBytes() },
        minFreeBytes: Long = MIN_FREE_BYTES
    ): RebuildStats {
        var rebuilt = 0
        var failed = 0
        var noCoords = 0
        var paused = false
        for ((index, row) in rows.withIndex()) {
            onProgress(index, rows.size)
            if (row.name in processed) continue
            if (freeSpaceBytes() < minFreeBytes) {
                paused = true
                break
            }
            try {
                when (recreateRow(context, row.uri)) {
                    RecreateResult.OK -> {
                        rebuilt++
                        processed.add(row.name)
                    }
                    RecreateResult.NO_COORDS -> {
                        // 无坐标也无视频段坐标：不删记录，标记已处理避免反复重试
                        noCoords++
                        processed.add(row.name)
                    }
                    RecreateResult.FAILED -> failed++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        onProgress(rows.size, rows.size)
        return RebuildStats(
            scanned = rows.size,
            rebuilt = rebuilt,
            failed = failed,
            noCoords = noCoords,
            pausedForSpace = paused,
            remaining = rows.count { it.name !in processed }
        )
    }

    /**
     * 分批重建。[processed] 为已处理名单（就地更新，调用方负责落盘）。
     * [onProgress] (已扫描, 总数)。[freeSpaceBytes] 可注入用于测试。
     */
    fun rebuildAll(
        context: Context,
        processed: MutableSet<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        freeSpaceBytes: () -> Long = { defaultFreeSpaceBytes() },
        minFreeBytes: Long = MIN_FREE_BYTES
    ): RebuildStats {
        val resolver = context.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        data class Row(val uri: Uri, val name: String)
        val rows = ArrayList<Row>()
        try {
            resolver.query(
                images,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/Camera", "DCIM/Camera/%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    rows.add(Row(ContentUrisWithId(images, c.getLong(0)), name))
                }
            }
        } catch (_: Exception) {}

        var scanned = 0
        var rebuilt = 0
        var failed = 0
        var noCoords = 0
        var paused = false
        val total = rows.size
        for ((index, row) in rows.withIndex()) {
            scanned++
            onProgress(index, total)
            if (row.name in processed) continue
            if (freeSpaceBytes() < minFreeBytes) {
                paused = true
                break
            }
            try {
                val header = readHeader(resolver, row.uri) ?: continue
                // 非应用产物（相机原始文件、用户其它照片）：标记已处理，后续批次跳过嗅探
                if (!containsBytes(header, APP_OUTPUT_MARKER)) {
                    processed.add(row.name)
                    continue
                }
                val result = recreateRow(context, row.uri)
                when (result) {
                    RecreateResult.OK -> {
                        rebuilt++
                        processed.add(row.name)
                    }
                    RecreateResult.NO_COORDS -> {
                        // 无坐标也无视频段坐标：不删记录，标记已处理避免反复重试
                        noCoords++
                        processed.add(row.name)
                    }
                    RecreateResult.FAILED -> failed++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        onProgress(total, total)
        val remaining = rows.count { it.name !in processed }
        return RebuildStats(scanned, rebuilt, failed, noCoords, paused, remaining)
    }

    enum class RecreateResult { OK, NO_COORDS, FAILED }

    /**
     * 行重建（单张）：字节与元数据读出 → 删除旧记录（文件进回收站）→
     * 同名/同目录/同时间戳 + 坐标重新插入 → 字节原样写回。
     * 坐标来源：文件 EXIF；缺失时从嵌入视频段恢复（并写回 EXIF）。
     */
    fun recreateRow(context: Context, uri: Uri): RecreateResult {
        val resolver = context.contentResolver
        var bytes: ByteArray
        var name: String
        var relPath: String
        var dateTaken: Long
        var dateModified: Long
        var dateAdded: Long
        var coords: Pair<Double, Double>
        try {
            resolver.query(uri, arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_ADDED
            ), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return RecreateResult.FAILED
                name = c.getString(0) ?: return RecreateResult.FAILED
                relPath = c.getString(1) ?: "DCIM/Camera"
                dateTaken = if (c.isNull(2)) 0L else c.getLong(2)
                dateModified = if (c.isNull(3)) 0L else c.getLong(3)
                dateAdded = if (c.isNull(4)) 0L else c.getLong(4)
            } ?: return RecreateResult.FAILED
            bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return RecreateResult.FAILED
            val exifCoords = JpegUtil.gpsCoordinates(bytes)
            coords = exifCoords ?: GpsRecovery.recoverFromVideoSegment(bytes)
                ?: return RecreateResult.NO_COORDS
            if (exifCoords == null) {
                // 从视频段恢复出的坐标写回 EXIF（切 JPEG/拼尾部，视频逐字节保留）
                bytes = injectGpsExif(context, bytes, coords) ?: return RecreateResult.FAILED
            }
        } catch (_: Exception) {
            return RecreateResult.FAILED
        }
        if (dateTaken <= 0L) dateTaken = dateModified * 1000
        if (dateModified <= 0L) dateModified = dateTaken / 1000
        // DATE_ADDED 决定微信等第三方选择器的日期分组：重建必须沿用原值。
        // 曾漏掉此列——vivo 相册按拍摄时间排不露馅，微信里 4699 张全挤进「今天」
        if (dateAdded <= 0L) dateAdded = dateTaken / 1000

        // 删除旧记录：vivo 会把文件移入「第三方删除拦截」回收站（可恢复）
        val deleted = try {
            resolver.delete(uri, null, null)
        } catch (_: Exception) {
            return RecreateResult.FAILED
        }
        if (deleted <= 0) return RecreateResult.FAILED

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var newUri: Uri? = null
        return try {
            // 时间列行为（2026-10 真机实验结论，三条路径逐一验证过）：
            // - DATE_ADDED：系统强制写成插入时刻，插入值与事后 UPDATE 都被无视——
            //   微信等第三方选择器按此列分组，重建过的照片会归到重建当天，无法洗回
            // - DATE_MODIFIED：IS_PENDING 流程会被写入时刻覆盖；**非 pending 直插能保住**
            //   给定值（本条分支即因此改为 0）
            // - DATE_TAKEN（拍摄时间）：插入值正常保留，EXIF/相册时间线不受影响
            newUri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
                put(MediaStore.MediaColumns.DATE_ADDED, dateAdded)
                put("latitude", coords.first)
                put("longitude", coords.second)
            }) ?: return RecreateResult.FAILED
            resolver.openOutputStream(newUri, "w")?.use { it.write(bytes) }
                ?: return RecreateResult.FAILED
            resolver.update(newUri, ContentValues().apply {
                put(MediaStore.MediaColumns.SIZE, bytes.size.toLong())
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            // 写入完成后再次固化拍摄/修改时间（MediaProvider 可能用写入时刻覆盖 DATE_MODIFIED）
            try {
                resolver.update(newUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_MODIFIED, dateModified)
                    put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
                }, null, null)
            } catch (_: Exception) {}
            // 物理文件 mtime 固化为拍摄时间（相册「修改时间」取自 mtime）
            try {
                resolver.query(newUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { path ->
                            File(path).setLastModified(dateTaken)
                        }
                    }
            } catch (_: Exception) {}
            RecreateResult.OK
        } catch (_: Exception) {
            // 新行写失败：删除半成品，原内容仍在回收站可恢复
            newUri?.let { u -> try { resolver.delete(u, null, null) } catch (_: Exception) {} }
            RecreateResult.FAILED
        }
    }

    /**
     * 把坐标写进合并产物的 EXIF：**必须先切出 JPEG 部分（SOI..EOI）单独交给
     * ExifInterface**——它重写文件时会截掉 EOI 之后的全部数据，直接喂合并文件
     * 会毁掉嵌入的视频。改写后的 JPEG 与原样尾部重新拼接；自检失败返回 null。
     */
    private fun injectGpsExif(
        context: Context,
        data: ByteArray,
        coords: Pair<Double, Double>
    ): ByteArray? {
        return try {
            var sosPayloadEnd: Int? = null
            for (seg in JpegUtil.iterateSegments(data)) {
                if (seg.marker == 0xDA.toByte()) {
                    sosPayloadEnd = seg.payloadStart + seg.payloadLen
                    break
                }
            }
            val eoiEnd = sosPayloadEnd?.let { JpegUtil.findEoiEnd(data, it) } ?: return null
            val tail = data.copyOfRange(eoiEnd, data.size)
            val tmp = File.createTempFile("gpsfix", ".jpg", context.cacheDir)
            try {
                tmp.writeBytes(data.copyOfRange(0, eoiEnd))
                ExifInterface(tmp.path).apply {
                    setLatLong(coords.first, coords.second)
                    saveAttributes()
                }
                val newJpeg = tmp.readBytes()
                if (JpegUtil.gpsCoordinates(newJpeg) == null) return null
                newJpeg + tail
            } finally {
                tmp.delete()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultFreeSpaceBytes(): Long = try {
        // 拿不到空间信息（0/异常）时视作充足，避免环境差异把功能误停
        File("/storage/emulated/0").usableSpace.takeIf { it > 0 } ?: Long.MAX_VALUE
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    private fun ContentUrisWithId(collection: Uri, id: Long): Uri =
        android.content.ContentUris.withAppendedId(collection, id)

    private fun readHeader(
        resolver: android.content.ContentResolver,
        uri: Uri,
        limit: Int = SNIFF_LIMIT
    ): ByteArray? {
        return resolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(limit)
            var n = 0
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n <= 0) null else if (n < buf.size) buf.copyOf(n) else buf
        }
    }

    private fun containsBytes(data: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || data.size < needle.size) return false
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
