package com.vliveconvert.app.convert

import android.content.ContentUris
import android.content.ContentValues
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
 * 「找回位置 / 位置体检」（v2.6.0-test28 重做检测）：
 *
 * 「需要修复」的定义 = **vivo 相册里该照片不显示位置**（用户可观察的最终事实），
 * 不是「文件无 EXIF」也不是「坐标列为空」。检测 = 判定矩阵
 * （[LocationHealth.classify]）：文件字节信号（EXIF/XMP/视频段）× 行坐标信号
 * （媒体库坐标列，**每次扫描先现场校准其可读性**——正反两条 IS_PENDING 探针行
 * 对照，不信任何历史结论）。
 *
 * 旧版（test27）的问题：扫描返回**全部产物**（100 张全列出），而文件信号对
 * 「相册显示与否」没有区分力——文件有 EXIF 的 95 张（大概率正常）把真正不显示
 * 的 5 张淹没了。现在默认只列「需修复」与「无位置」，「待确认」（列不可读时
 * 文件有坐标的那批）折叠展示并说明原因，「已正常」只计数不列出。
 *
 * 修复机制不变：缺 EXIF 的先写回文件 EXIF，再行重建
 * （[MediaExport.rebuildRowWithCoords]：临时名先建新行、读回校验、旧行后删、
 * IS_FAVORITE 随迁）；每张修复后**读回验证**，失败显式记录原因。
 * 逐张信号与修复结果可导出诊断报告，真机回传即可定位。
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

    /** 体检结果行：产物 + 全部信号 + 判定矩阵输出（含人可读的判定依据） */
    data class HealthRow(
        val uri: Uri,
        val name: String,
        val dateTaken: Long,
        val probe: LocationHealth.Probe,
        /** 直接 projection 读到的行坐标原始值（脱敏/缺失/不可读时为 null；诊断报告用） */
        val rowCoordsRaw: Pair<Double, Double>?,
        /** 行坐标是否在位（null = 不可观测：列不可读且无 escalate 证据） */
        val rowCoordsPresent: Boolean?,
        /** 判定矩阵输出 */
        val classification: LocationHealth.Classification,
        /** 判定依据（人可读） */
        val reason: String
    )

    /** 修复目标（后台服务只需要 URI 与名字；修复时重新探针） */
    data class RepairTarget(val uri: Uri, val name: String)

    /** 列读信号：校准结论 + 两种读法（生产由 [calibrateColumns] 构建，测试可注入） */
    internal class ColumnSignal(
        val calibration: LocationHealth.ColumnCalibration,
        val detail: String,
        /** 直接 projection 读行坐标；读不到值返回 null */
        val readDirect: (Uri) -> Pair<Double, Double>?,
        /** selection oracle 读「行坐标是否在位」；该方法不可用时返回 null */
        val readPresenceViaSelection: (Uri) -> Boolean?
    )

    /** 扫描结果：校准环境 + 全部产物行（已分类） */
    data class ScanOutcome(
        val calibration: LocationHealth.ColumnCalibration,
        val calibDetail: String,
        val rows: List<HealthRow>
    )

    /**
     * 坐标列可读性校准（每次扫描现场执行，毫秒级）：
     * 正探针 = IS_PENDING 行**出生带坐标**；负探针 = 同型行**出生不带坐标**。
     * ① 直接 projection 读正探针 → 有值 = READABLE；
     * ② 否则 selection oracle（`latitude IS NOT NULL AND _ID=?`）：正命中 + 负不命中
     *    = READABLE_VIA_SELECTION（projection 被脱敏但 selection 判定仍用真值）；
     *    正负都命中 = selection 被忽略，不可用；
     * ③ 都不行 = OPAQUE；过程异常 = FAILED（detail 带原因）。
     * 探针行对相册不可见（pending），finally 里必删。
     */
    fun calibrateColumns(resolver: android.content.ContentResolver): ColumnSignal {
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var pos: Uri? = null
        var neg: Uri? = null
        var outcome: LocationHealth.ColumnCalibration
        var detail: String
        try {
            pos = resolver.insert(images, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "vlc_calib_pos.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VLiveConvert")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put("latitude", 22.0)
                put("longitude", 108.0)
            })
            neg = resolver.insert(images, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "vlc_calib_neg.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VLiveConvert")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
            if (pos == null || neg == null) {
                outcome = LocationHealth.ColumnCalibration.FAILED
                detail = "探针行插入失败（pos=${pos != null}, neg=${neg != null}）"
            } else {
                // 写最小内容：个别 Provider 对空文件行为不定
                val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
                runCatching { resolver.openOutputStream(pos, "w")?.use { it.write(head) } }
                runCatching { resolver.openOutputStream(neg, "w")?.use { it.write(head) } }
                val direct = readCoordsDirect(resolver, pos)
                if (direct != null) {
                    outcome = LocationHealth.ColumnCalibration.READABLE
                    detail = "直接读回 ${direct.first},${direct.second}（正探针出生带坐标）"
                } else {
                    val posHit = presenceViaSelection(resolver, pos)
                    val negHit = presenceViaSelection(resolver, neg)
                    when {
                        posHit == true && negHit == false -> {
                            outcome = LocationHealth.ColumnCalibration.READABLE_VIA_SELECTION
                            detail = "projection 读不出值；selection 可区分在位与否"
                        }
                        posHit == true && negHit == true -> {
                            outcome = LocationHealth.ColumnCalibration.OPAQUE
                            detail = "selection 不区分（正负探针都命中）"
                        }
                        else -> {
                            outcome = LocationHealth.ColumnCalibration.OPAQUE
                            detail = "projection 与 selection 都读不出正探针的坐标"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            outcome = LocationHealth.ColumnCalibration.FAILED
            detail = "校准异常：${e.javaClass.simpleName}: ${e.message}"
        } finally {
            pos?.let { runCatching { resolver.delete(it, null, null) } }
            neg?.let { runCatching { resolver.delete(it, null, null) } }
        }
        return ColumnSignal(
            calibration = outcome,
            detail = detail,
            readDirect = { uri -> readCoordsDirect(resolver, uri) },
            readPresenceViaSelection = { uri ->
                if (outcome == LocationHealth.ColumnCalibration.READABLE_VIA_SELECTION)
                    presenceViaSelection(resolver, uri) else null
            }
        )
    }

    /** 直接 projection 读行坐标（item URI）；读不到值返回 null */
    private fun readCoordsDirect(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Pair<Double, Double>? = try {
        resolver.query(uri, arrayOf("latitude", "longitude"), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1))
                c.getDouble(0) to c.getDouble(1) else null
        }
    } catch (_: Exception) {
        null
    }

    /** selection oracle：行坐标是否在位（`latitude IS NOT NULL`）；异常返回 null */
    private fun presenceViaSelection(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Boolean? = try {
        val id = ContentUris.parseId(uri)
        resolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "latitude IS NOT NULL AND ${MediaStore.MediaColumns._ID}=?",
            arrayOf(id.toString()), null
        )?.use { it.count > 0 }
    } catch (_: Exception) {
        null
    }

    /**
     * 扫描本工具全部转换产物，逐张做位置体检并按判定矩阵分类。
     *
     * 两阶段收集（同旧版）：
     * - **索引直查**：ConvertedStore（源路径→产物 URI）命中的行直接体检，免嗅探
     * - **全相册嗅探兜底**：DCIM/Camera 与输出目录的行按头部 XMP 产物标记识别，
     *   非产物记入已处理名单（下次跳过）
     *
     * 体检信号：文件字节（[probeUri]：头 256KB 查 EXIF/XMP，都没有再全量读查视频段）
     * + 行坐标（[ColumnSignal]；每行都做直接读——列不可读时该读数仍进诊断报告，
     * 且若任一行意外读到值，说明列实际可读，整体升级为可读（escalate））。
     */
    suspend fun scanProducts(
        context: Context,
        /** (已处理, 总数, 已发现产物数) */
        onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
        /** 测试注入的列读信号；null = 现场校准 */
        columnSignal: ColumnSignal? = null
    ): ScanOutcome {
        val resolver = context.contentResolver
        val signal = columnSignal ?: calibrateColumns(resolver)
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val processed = loadProcessed(context)

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
                    indexedRows.add(Triple(uri, name, dateTaken))
                }
            } catch (_: Exception) {}
        }

        // ── 阶段 B：全相册嗅探（排除索引已报与已处理名单）──
        val indexedKeys = HashSet<Uri>()
        for ((uri, _, _) in indexedRows) indexedKeys.add(uri)
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
                    if (uri in indexedKeys) continue // 阶段 A 索引已报，免重复
                    val name = c.getString(1) ?: continue
                    if (name in processed) continue
                    val dateTaken = if (c.isNull(2)) 0L else c.getLong(2)
                    rows.add(Row(uri, name, dateTaken))
                }
            }
        } catch (_: Exception) {}

        // 并行体检：文件探针 + 行坐标直接读 +（selection 模式下的）在位判定
        data class RawRow(
            val uri: Uri, val name: String, val dateTaken: Long,
            val probe: LocationHealth.Probe,
            val rowCoordsRaw: Pair<Double, Double>?,
            val presenceViaSel: Boolean?
        )
        val raws = Collections.synchronizedList(ArrayList<RawRow>())
        val total = indexedRows.size + rows.size
        val done = AtomicInteger(0)
        val sem = Semaphore(SCAN_PARALLELISM)
        suspend fun probeAll(uri: Uri, name: String, dateTaken: Long) {
            sem.withPermit {
                try {
                    raws.add(RawRow(
                        uri, name, dateTaken,
                        probeUri(resolver, uri),
                        signal.readDirect(uri),
                        if (signal.calibration == LocationHealth.ColumnCalibration.READABLE_VIA_SELECTION)
                            signal.readPresenceViaSelection(uri) else null))
                } catch (_: Exception) {
                } finally {
                    onProgress(done.incrementAndGet(), total, raws.size)
                }
            }
        }
        coroutineScope {
            for ((uri, name, dateTaken) in indexedRows) {
                launch(Dispatchers.IO) { probeAll(uri, name, dateTaken) }
            }
            for (row in rows) {
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            val header = readHeader(resolver, row.uri, SCAN_SNIFF_LIMIT)
                                ?: return@withPermit
                            if (containsBytes(header, APP_OUTPUT_MARKER)) {
                                raws.add(RawRow(
                                    row.uri, row.name, row.dateTaken,
                                    probeUri(resolver, row.uri),
                                    signal.readDirect(row.uri),
                                    if (signal.calibration == LocationHealth.ColumnCalibration.READABLE_VIA_SELECTION)
                                        signal.readPresenceViaSelection(row.uri) else null))
                            } else {
                                synchronized(processed) { processed.add(row.name) }
                            }
                        } catch (_: Exception) {
                        } finally {
                            onProgress(done.incrementAndGet(), total, raws.size)
                        }
                    }
                }
            }
        }
        saveProcessed(context, processed)
        onProgress(total, total, raws.size)

        // ── 分类（判定矩阵）：escalate = 列名义不可读，但有任一行读到值 → 实际可读 ──
        val escalated = raws.any { it.rowCoordsRaw != null }
        val columnsReadable = signal.calibration.isUsable || escalated
        val classified = raws.map { r ->
            val presence: Boolean? = when (signal.calibration) {
                LocationHealth.ColumnCalibration.READABLE_VIA_SELECTION -> r.presenceViaSel
                LocationHealth.ColumnCalibration.READABLE -> r.rowCoordsRaw != null
                LocationHealth.ColumnCalibration.OPAQUE,
                LocationHealth.ColumnCalibration.FAILED ->
                    if (escalated) r.rowCoordsRaw != null else null
            }
            val fileUnreadable = r.probe.unreadable
            val fileHasExif = r.probe.exif != null
            val hasFallback = r.probe.xmp != null || r.probe.video != null
            val c = LocationHealth.classify(
                fileUnreadable, fileHasExif, hasFallback, columnsReadable, presence)
            HealthRow(
                uri = r.uri, name = r.name, dateTaken = r.dateTaken,
                probe = r.probe,
                rowCoordsRaw = r.rowCoordsRaw,
                rowCoordsPresent = presence,
                classification = c,
                reason = LocationHealth.classificationReason(
                    c, fileHasExif, hasFallback, columnsReadable, presence))
        }.sortedWith(
            compareByDescending<HealthRow> { it.dateTaken }.thenByDescending { it.name })
        return ScanOutcome(signal.calibration, signal.detail, classified)
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
        // 异常安全：行失效/文件缺失时 openInputStream 抛异常——返回 null 让上层
        // 把它归入 UNREADABLE（读取失败），而不是异常上抛被吞、该行凭空消失
        return try {
            resolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(limit)
                var n = 0
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) null else if (n < buf.size) buf.copyOf(n) else buf
            }
        } catch (_: Exception) {
            null
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
