package com.vliveconvert.app.convert

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.vliveconvert.app.service.ConvertedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「找回位置 / 位置体检」（v2.6.0-test27 重做）：
 *
 * 旧版扫描靠**媒体库坐标列查询**判定「谁缺位置」——test24~26 已证该列在 vivo 上
 * 读不回，以此为判据会把好照片误判、把坏照片漏掉。现在**判定全部基于产物文件
 * 字节**（EXIF/XMP/视频段，见 [LocationHealth]），媒体库只读列（名称/时间）仅作
 * 元数据，坐标列既不读也不信。
 *
 * 修复机制：缺 EXIF 的先把坐标写回文件 EXIF（媒体扫描重算坐标列的唯一长效依据），
 * 再走行重建（[MediaExport.rebuildRowWithCoords]：临时名先建新行、读回校验、
 * 旧行后删、IS_FAVORITE 随迁）；每张修复后**读回验证**，失败显式记录原因。
 * 逐张结果可导出诊断报告（判定依据 + 修复路径 + 失败原因），真机回传即可定位。
 *
 * 分批与空间守卫：重建会在回收站留下副本（约等于产物总体积），可用空间低于
 * [MIN_FREE_BYTES] 自动暂停；已处理文件名持久化，可中断续跑。
 */
internal object LocationDisplayRepair {

    /** 应用产物的 XMP 签名（vivo 模板独有；相机原始双文件不含） */
    private val APP_OUTPUT_MARKER: ByteArray = "VCamera:VMediaKitVersion".toByteArray()

    /** 头部嗅探长度：产物 XMP 标记在头部 APP1，128KB 足够命中 */
    private const val SCAN_SNIFF_LIMIT = 128 * 1024

    /** 体检头部长度：EXIF 与 XMP 都在文件头部，256KB 覆盖带大缩略图的 EXIF */
    private const val PROBE_HEAD_LIMIT = 256 * 1024

    /** 嗅探并行度：IO 密集，4 路已能打满 FUSE 读取 */
    private const val SCAN_PARALLELISM = 4

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
        val remaining: Int,
        /** 逐张修复明细（导出诊断报告用） */
        val records: List<LocationHealth.ReportLine> = emptyList()
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

    /** 体检结果行：产物 + 文件事实判定（含每个坐标来源解析到了什么） */
    data class HealthRow(
        val uri: Uri,
        val name: String,
        val dateTaken: Long,
        val probe: LocationHealth.Probe
    )

    /** 修复目标（后台服务只需要 URI 与名字；修复时重新探针） */
    data class RepairTarget(val uri: Uri, val name: String)

    /**
     * 扫描本工具全部转换产物并逐张做位置体检（**不读媒体库坐标列**）。
     *
     * 两阶段：
     * - **索引直查**：ConvertedStore（源路径→产物 URI）命中的行直接体检，免嗅探
     * - **全相册嗅探兜底**：DCIM/Camera 与输出目录的行按头部 XMP 产物标记识别，
     *   非产物记入已处理名单（下次跳过）
     *
     * 体检（[probeUri]）：先读头 256KB 查 EXIF/XMP；都没有再全量读查嵌入视频段。
     * 返回**全部产物**（不止缺位置的）——用户要能看出「哪些有、哪些缺、哪些读不动」。
     */
    suspend fun scanProducts(
        context: Context,
        /** (已处理, 总数, 已发现产物数) */
        onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> }
    ): List<HealthRow> {
        val resolver = context.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val processed = loadProcessed(context)
        val results = Collections.synchronizedList(ArrayList<HealthRow>())
        val knownProductUris = Collections.synchronizedSet(HashSet<Uri>())

        // ── 阶段 A：索引直查（ConvertedStore：源路径 → 产物 URI）──
        val pairs = try { ConvertedStore.load(context).toList() } catch (_: Exception) { emptyList() }
        val indexedRows = ArrayList<Triple<Uri, String, Long>>()
        for ((srcPath, outUriString) in pairs) {
            val uri = try { Uri.parse(outUriString) } catch (_: Exception) { continue }
            try {
                resolver.query(
                    uri,
                    arrayOf(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_TAKEN
                    ), null, null, null
                )?.use { c ->
                    if (!c.moveToFirst()) return@use // 行已失效（删除/移动）：交嗅探兜底
                    val name = c.getString(0) ?: File(srcPath).name
                    val dateTaken = if (c.isNull(1)) 0L else c.getLong(1)
                    knownProductUris.add(uri)
                    indexedRows.add(Triple(uri, name, dateTaken))
                }
            } catch (_: Exception) {}
        }

        // ── 阶段 B：全相册嗅探（排除索引已报与已处理名单）──
        data class Row(val uri: Uri, val name: String, val dateTaken: Long)
        val rows = ArrayList<Row>()
        try {
            resolver.query(
                images,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_TAKEN
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/Camera", "DCIM/Camera/%", "Pictures/VLiveConvert%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(images, id)
                    if (uri in knownProductUris) continue
                    val name = c.getString(1) ?: continue
                    if (name in processed) continue
                    val dateTaken = if (c.isNull(2)) 0L else c.getLong(2)
                    rows.add(Row(uri, name, dateTaken))
                }
            }
        } catch (_: Exception) {}

        // 嗅探产物标记 + 体检：IO 密集，4 路并行；进度用原子计数
        val total = indexedRows.size + rows.size
        val done = AtomicInteger(0)
        val sem = Semaphore(SCAN_PARALLELISM)
        coroutineScope {
            for ((uri, name, dateTaken) in indexedRows) {
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            results.add(HealthRow(uri, name, dateTaken, probeUri(resolver, uri)))
                        } catch (_: Exception) {
                        } finally {
                            onProgress(done.incrementAndGet(), total, results.size)
                        }
                    }
                }
            }
            for (row in rows) {
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            val header = readHeader(resolver, row.uri, SCAN_SNIFF_LIMIT)
                                ?: return@withPermit
                            if (containsBytes(header, APP_OUTPUT_MARKER)) {
                                results.add(HealthRow(
                                    row.uri, row.name, row.dateTaken, probeUri(resolver, row.uri)))
                            } else {
                                synchronized(processed) { processed.add(row.name) }
                            }
                        } catch (_: Exception) {
                        } finally {
                            onProgress(done.incrementAndGet(), total, results.size)
                        }
                    }
                }
            }
        }
        saveProcessed(context, processed)
        onProgress(total, total, results.size)
        return results.sortedWith(
            compareByDescending<HealthRow> { it.dateTaken }.thenByDescending { it.name })
    }

    /** 只重建传入的记录（选中式修复）：逐张走 [LocationHealth.repairProduct]，
     * 已处理的记入 [processed]（续跑名单），空间不足即停。逐张明细进 [RebuildStats.records]。 */
    fun rebuildSelected(
        context: Context,
        rows: List<RepairTarget>,
        processed: MutableSet<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        freeSpaceBytes: () -> Long = { defaultFreeSpaceBytes() },
        minFreeBytes: Long = MIN_FREE_BYTES
    ): RebuildStats {
        var rebuilt = 0
        var failed = 0
        var noCoords = 0
        var paused = false
        val records = ArrayList<LocationHealth.ReportLine>()
        for ((index, row) in rows.withIndex()) {
            onProgress(index, rows.size)
            if (row.name in processed) continue
            if (freeSpaceBytes() < minFreeBytes) {
                paused = true
                break
            }
            val outcome = try {
                LocationHealth.repairProduct(context, row.uri)
            } catch (_: Exception) {
                LocationHealth.RepairOutcome(
                    LocationHealth.RepairResult.FAILED, "修复", false, null, "修复过程异常")
            }
            records.add(LocationHealth.ReportLine(
                name = row.name,
                verdict = when (outcome.result) {
                    LocationHealth.RepairResult.OK -> "修复成功"
                    LocationHealth.RepairResult.NO_COORDS -> "无坐标"
                    LocationHealth.RepairResult.FAILED -> "修复失败"
                },
                evidence = outcome.coords?.let {
                    "坐标=%.5f,%.5f".format(it.first, it.second)
                } ?: "未解析到坐标",
                repair = outcome.action + when {
                    outcome.error != null -> "（${outcome.error}）"
                    outcome.verified -> "（读回验证通过）"
                    else -> ""
                }
            ))
            when (outcome.result) {
                LocationHealth.RepairResult.OK -> {
                    rebuilt++
                    processed.add(row.name)
                }
                LocationHealth.RepairResult.NO_COORDS -> {
                    // 无坐标可恢复：不删记录，标记已处理避免反复重试
                    noCoords++
                    processed.add(row.name)
                }
                LocationHealth.RepairResult.FAILED -> failed++
            }
        }
        onProgress(rows.size, rows.size)
        return RebuildStats(
            scanned = rows.size,
            rebuilt = rebuilt,
            failed = failed,
            noCoords = noCoords,
            pausedForSpace = paused,
            remaining = rows.count { it.name !in processed },
            records = records
        )
    }

    /**
     * 全量重建（设置页入口）：扫描全部产物，对**有任何坐标来源**的逐张修复
     * （EXIF 缺的注入 + 行重建；EXIF 有的强制行重建——行坐标是否在位无法用
     * 查询证实，强制重建是确定性兜底）。
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
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/Camera", "DCIM/Camera/%", "Pictures/VLiveConvert%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    rows.add(Row(ContentUris.withAppendedId(images, c.getLong(0)), name))
                }
            }
        } catch (_: Exception) {}

        var scanned = 0
        var rebuilt = 0
        var failed = 0
        var noCoords = 0
        var paused = false
        val records = ArrayList<LocationHealth.ReportLine>()
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
                val outcome = LocationHealth.repairProduct(context, row.uri)
                records.add(LocationHealth.ReportLine(
                    name = row.name,
                    verdict = when (outcome.result) {
                        LocationHealth.RepairResult.OK -> "修复成功"
                        LocationHealth.RepairResult.NO_COORDS -> "无坐标"
                        LocationHealth.RepairResult.FAILED -> "修复失败"
                    },
                    evidence = outcome.coords?.let {
                        "坐标=%.5f,%.5f".format(it.first, it.second)
                    } ?: "未解析到坐标",
                    repair = outcome.action + when {
                        outcome.error != null -> "（${outcome.error}）"
                        outcome.verified -> "（读回验证通过）"
                        else -> ""
                    }
                ))
                when (outcome.result) {
                    LocationHealth.RepairResult.OK -> {
                        rebuilt++
                        processed.add(row.name)
                    }
                    LocationHealth.RepairResult.NO_COORDS -> {
                        noCoords++
                        processed.add(row.name)
                    }
                    LocationHealth.RepairResult.FAILED -> failed++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        onProgress(total, total)
        val remaining = rows.count { it.name !in processed }
        return RebuildStats(scanned, rebuilt, failed, noCoords, paused, remaining, records)
    }

    enum class RecreateResult { OK, NO_COORDS, FAILED }

    /** 单张修复（兼容入口）：语义与 [LocationHealth.repairProduct] 一致 */
    fun recreateRow(context: Context, uri: Uri): RecreateResult =
        when (LocationHealth.repairProduct(context, uri).result) {
            LocationHealth.RepairResult.OK -> RecreateResult.OK
            LocationHealth.RepairResult.NO_COORDS -> RecreateResult.NO_COORDS
            LocationHealth.RepairResult.FAILED -> RecreateResult.FAILED
        }

    /**
     * 逐张体检：先读头部查 EXIF/XMP；都没有再全量读查嵌入视频段。
     * 读失败返回「无法判定」探针（绝不静默当「无坐标」——无坐标是结论，读不动是故障）。
     */
    private fun probeUri(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): LocationHealth.Probe {
        val head = readHeader(resolver, uri, PROBE_HEAD_LIMIT) ?: return LocationHealth.Probe(
            null, null, null, unreadable = true)
        val quick = LocationHealth.probeHead(head)
        if (quick.best != null) return quick
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return quick.copy(unreadable = true)
        return LocationHealth.probeFull(bytes)
    }

    private fun defaultFreeSpaceBytes(): Long = try {
        // 拿不到空间信息（0/异常）时视作充足，避免环境差异把功能误停
        File("/storage/emulated/0").usableSpace.takeIf { it > 0 } ?: Long.MAX_VALUE
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    private fun readHeader(
        resolver: android.content.ContentResolver,
        uri: Uri,
        limit: Int = PROBE_HEAD_LIMIT
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
