package com.vliveconvert.app.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.convert.LocationHealth
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.service.LocationRepairService
import com.vliveconvert.app.ui.theme.DesignTokens
import com.vliveconvert.app.ui.theme.statusColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.lerp

/**
 * 找回位置（v2.6.0-test28：检测重做 + 视觉对齐选择器网格语言）。
 *
 * 检测：判定矩阵（文件字节 × 行坐标，行坐标可读性每次扫描现场校准）。
 * 「需要修复」= 相册里不显示位置。默认只列「需修复」与「无位置」；
 * 「待确认」折叠说明原因；「已正常」只计数、点计数展开核对。
 *
 * 视觉：与「选择照片」同一套语言——16dp 栅格、3 列网格（2dp 间距、格子 12/16dp
 * 圆角、选中 0.86 中心缩放 + 弹簧徽章）、分类分段头（16sp/12sp + 组全选圈）、
 * 右下角状态角标（「需修复」琥珀 / 「无位置」中性，同「已添加/已转」槽位）、
 * 底部液态玻璃 Dock。规格引用 [DesignTokens]，不再各自硬编码。
 */
@Composable
fun LocationRecoveryScreen(
    /** 「位置」权限：文件坐标与行坐标的读取都被它管辖，扫描前必须拿到 */
    locationGranted: Boolean,
    onGrantLocation: () -> Unit,
    onBack: () -> Unit,
    hazeState: HazeState = rememberGlassHazeState()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val gridState = rememberLazyGridState()

    var scanning by remember { mutableStateOf(true) }
    var scanProgress by remember { mutableStateOf(Triple(0, 0, 0)) }
    var outcome by remember {
        mutableStateOf<LocationDisplayRepair.ScanOutcome?>(null)
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var showUnknown by remember { mutableStateOf(false) }
    /** 导出诊断报告默认脱敏坐标；勾选后才带精确坐标（主动选择） */
    var reportWithCoords by remember { mutableStateOf(false) }
    var showOk by remember { mutableStateOf(false) }
    var lastRecords by remember { mutableStateOf<List<LocationHealth.ReportLine>>(emptyList()) }
    val repairing = ConvertCenter.isLocationRepairing
    val repairProgress = ConvertCenter.repairProgress
    val repairCurrent = ConvertCenter.repairCurrent

    val startScan: () -> Unit = {
        if (!ConvertCenter.isLocationRepairing) {
            scanning = true
            scanProgress = Triple(0, 0, 0)
            outcome = null
            selected = emptySet()
            showUnknown = false
            showOk = false
            scope.launch(Dispatchers.IO) {
                val found = LocationDisplayRepair.scanProducts(
                    context,
                    onProgress = { done, total, foundN ->
                        scanProgress = Triple(done, total, foundN)
                    })
                withContext(Dispatchers.Main) {
                    outcome = found
                    // 默认只勾选「需修复」——它们才是用户要修的那几张
                    selected = found.rows
                        .filter { it.classification == LocationHealth.Classification.REPAIR_NEEDED }
                        .map { it.uri.toString() }.toSet()
                    scanning = false
                }
            }
        }
    }

    val startRebuild: () -> Unit = {
        val targets = (outcome?.rows ?: emptyList()).filter { it.uri.toString() in selected }
            .map { LocationDisplayRepair.RepairTarget(it.uri, it.name) }
        if (targets.isNotEmpty() && !ConvertCenter.isLocationRepairing) {
            LocationRepairService.start(context, targets)
        }
    }

    LaunchedEffect(ConvertCenter.repairResult) {
        val result = ConvertCenter.repairResult
        if (result != null) {
            ConvertCenter.repairResult = null
            lastRecords = ConvertCenter.repairReport.orEmpty()
            ConvertCenter.repairReport = null
            summary = result
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
    }

    LaunchedEffect(repairing) {
        if (!repairing && !scanning && ConvertCenter.repairResult == null &&
            outcome?.rows?.isNotEmpty() == true) {
            startScan()
        }
    }

    LaunchedEffect(locationGranted) {
        if (locationGranted && !ConvertCenter.isLocationRepairing) startScan()
    }
    BackHandler(enabled = !repairing) { onBack() }

    // ── 分类分组（判定矩阵输出）──
    val rows = outcome?.rows ?: emptyList()
    val repairNeeded = remember(rows) {
        rows.filter { it.classification == LocationHealth.Classification.REPAIR_NEEDED }
    }
    val noLocation = remember(rows) {
        rows.filter { it.classification == LocationHealth.Classification.NO_LOCATION }
    }
    val unknown = remember(rows) {
        rows.filter { it.classification == LocationHealth.Classification.UNKNOWN }
    }
    val okRows = remember(rows) {
        rows.filter { it.classification == LocationHealth.Classification.OK }
    }
    val unreadable = remember(rows) {
        rows.filter { it.classification == LocationHealth.Classification.UNREADABLE }
    }
    val selectable = remember(rows, showUnknown) {
        repairNeeded + if (showUnknown) unknown else emptyList()
    }
    val allSelected = selectable.isNotEmpty() && selected.size == selectable.size

    val exportReport: () -> Unit = {
        val lines = rows.map { r ->
            val record = lastRecords.firstOrNull { it.name == r.name }
            LocationHealth.ReportLine(
                name = r.name,
                verdict = r.classification.label +
                    (r.rowCoordsPresent?.let { if (it) "（行坐标在位）" else "（行坐标缺失）" }
                        ?: "（行坐标不可观测）"),
                evidence = r.probe.evidence(redact = !reportWithCoords) + "；" + r.reason +
                    (if (reportWithCoords) r.rowCoordsRaw?.let { "；行列原始读数=${"%.5f,%.5f".format(it.first, it.second)}" }
                        ?: "" else ""),
                repair = record?.repair?.let { "${record.verdict}：$it" })
        } + lastRecords.filter { rec -> rows.none { it.name == rec.name } }
        val (verName, verCode) = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName to pi.longVersionCode
        } catch (_: Exception) {
            "unknown" to 0L
        }
        val calib = outcome
        val report = LocationHealth.buildReport(
            appVersion = "$verName ($verCode)",
            device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            calibration = calib?.let { "${it.calibration}（${it.calibDetail}）" } ?: "未扫描",
            rows = lines,
            summary = "共 ${rows.size} 张产物：需修复 ${repairNeeded.size}，待确认 ${unknown.size}，" +
                "无位置 ${noLocation.size}，已正常 ${okRows.size}，读取失败 ${unreadable.size}"
        )
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "影中影 位置诊断报告")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(Intent.createChooser(intent, "导出诊断报告"))
        } catch (_: Exception) {}
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .glassWallpaper()
                .glassBackdropSource(hazeState)
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = DesignTokens.ScreenHMargin, end = DesignTokens.ScreenHMargin,
                    top = DesignTokens.TitleTop, bottom = DesignTokens.TitleBottom),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (repairing) {
                // 重建中禁止返回：行重建中途被打断会留下回收站副本
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                GlassIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    size = 38.dp
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "找回位置",
                style = TextStyle(fontSize = DesignTokens.TitleSubpage,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = DesignTokens.TitleSubpageTracking,
                    color = MaterialTheme.colorScheme.onBackground)
            )
        }

        if (!locationGranted) {
            Column(
                Modifier.fillMaxWidth().weight(1f)
                    .padding(horizontal = DesignTokens.ScreenHMargin)
            ) {
                Spacer(Modifier.height(10.dp))
                GlassCard(radius = DesignTokens.CardRadius, contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, null,
                            tint = statusColors.warning, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "未授予「位置」权限，系统会在读取层抹掉照片坐标，无法做位置体检。",
                            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    GlassOutlineButton("去授权", onGrantLocation)
                }
            }
        } else {
            // ── 工具行（选择器同款节奏）：计数摘要（左） + 已选/全选（右）──
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = DesignTokens.ScreenHMargin, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        scanning -> "体检中 ${scanProgress.first}/${scanProgress.second}"
                        repairing -> "重建中 ${repairProgress.first}/${repairProgress.second}"
                        rows.isEmpty() -> "没有发现本工具产物"
                        else -> "需修复 ${repairNeeded.size}" +
                            (if (unknown.isNotEmpty()) " · 待确认 ${unknown.size}" else "") +
                            (if (noLocation.isNotEmpty()) " · 无位置 ${noLocation.size}" else "") +
                            (if (unreadable.isNotEmpty()) " · 读失败 ${unreadable.size}" else "") +
                            " · 已正常 ${okRows.size}"
                    },
                    style = glassSubtitleStyle(),
                    modifier = Modifier
                        .weight(1f)
                        .clip(continuousShape(8.dp))
                        .clickable(enabled = !scanning && okRows.isNotEmpty()) {
                            showOk = !showOk
                        }
                        .padding(vertical = 8.dp)
                )
                if (!scanning && selectable.isNotEmpty()) {
                    Text(
                        "已选 ${selected.size} 张",
                        fontSize = DesignTokens.ToolRowText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (allSelected) "取消全选" else "全选",
                        fontSize = DesignTokens.ToolRowText,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(continuousShape(8.dp))
                            .clickable {
                                selected = if (allSelected) emptySet()
                                else selectable.map { it.uri.toString() }.toSet()
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }

            GlassCard(
                radius = DesignTokens.CardRadius,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.ScreenHMargin, vertical = 8.dp)
                    .weight(1f)
            ) {
                when {
                    scanning -> Box(
                        Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassIndeterminateBar(Modifier.width(120.dp).height(3.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "正在逐张体检（文件坐标 + 媒体库行坐标），已发现 ${scanProgress.third} 张",
                                style = TextStyle(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    repairNeeded.isEmpty() && noLocation.isEmpty() && unknown.isEmpty() &&
                        unreadable.isEmpty() -> Box(
                        Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (okRows.isEmpty()) "没有发现本工具产物"
                                else "全部 ${okRows.size} 张产物的位置都正常\n\n" +
                                    "「需修复」= 相册里不显示位置的照片；判定基于文件坐标" +
                                    (if (outcome?.calibration?.isUsable == true) "与媒体库行坐标"
                                    else "（本机媒体库坐标列读不出，行坐标未能核对）"),
                                style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (okRows.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (showOk) "收起已正常列表" else "查看已正常列表",
                                    fontSize = DesignTokens.ToolRowText,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(continuousShape(8.dp))
                                        .clickable { showOk = !showOk }
                                        .padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(DesignTokens.GridColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 116.dp),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.GridSpacing),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.GridSpacing)
                    ) {
                        if (repairNeeded.isNotEmpty()) {
                            item(key = "hdr_repair", span = { GridItemSpan(DesignTokens.GridColumns) }) {
                                ClassificationHeader(
                                    title = "需修复（${repairNeeded.size}）",
                                    subtitle = "相册里不显示位置，勾选后可修复",
                                    group = repairNeeded,
                                    selected = selected,
                                    onToggleGroup = { keys, select ->
                                        selected = if (select) selected + keys else selected - keys
                                    }
                                )
                            }
                            items(repairNeeded, key = { it.uri.toString() }) { row ->
                                RecoveryGridCell(
                                    row = row,
                                    selected = row.uri.toString() in selected,
                                    onToggle = {
                                        val key = row.uri.toString()
                                        selected = if (key in selected) selected - key
                                        else selected + key
                                    }
                                )
                            }
                        }
                        if (noLocation.isNotEmpty()) {
                            item(key = "hdr_noloc", span = { GridItemSpan(DesignTokens.GridColumns) }) {
                                ClassificationHeader(
                                    title = "无位置（${noLocation.size}）",
                                    subtitle = "源照片拍摄时未记录坐标，相册不显示且无法恢复",
                                    group = emptyList(),
                                    selected = selected,
                                    onToggleGroup = { _, _ -> }
                                )
                            }
                            items(noLocation, key = { it.uri.toString() }) { row ->
                                RecoveryGridCell(row = row, selected = false, onToggle = {})
                            }
                        }
                        if (unreadable.isNotEmpty()) {
                            item(key = "hdr_unreadable", span = { GridItemSpan(DesignTokens.GridColumns) }) {
                                ClassificationHeader(
                                    title = "读取失败（${unreadable.size}）",
                                    subtitle = "产物文件读取异常（故障，不是无坐标）",
                                    group = emptyList(),
                                    selected = selected,
                                    onToggleGroup = { _, _ -> }
                                )
                            }
                            items(unreadable, key = { it.uri.toString() }) { row ->
                                RecoveryGridCell(row = row, selected = false, onToggle = {})
                            }
                        }
                        if (unknown.isNotEmpty()) {
                            item(key = "hdr_unknown", span = { GridItemSpan(DesignTokens.GridColumns) }) {
                                // 待确认：折叠态说明「为什么不能确定」；展开后组内可全选修复
                                Column(Modifier.fillMaxWidth().padding(
                                    start = 8.dp, end = 8.dp, top = 16.dp, bottom = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "待确认（${unknown.size}）" +
                                                if (showUnknown) " · 收起" else " · 展开",
                                            fontSize = DesignTokens.SectionTitle,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(continuousShape(8.dp))
                                                .clickable { showUnknown = !showUnknown }
                                                .padding(vertical = 8.dp)
                                        )
                                        if (showUnknown) {
                                            val keys = unknown.map { it.uri.toString() }.toSet()
                                            val groupAll = keys.all { it in selected }
                                            val groupSome = keys.any { it in selected }
                                            CircleTriCheckbox(
                                                state = when {
                                                    groupAll -> ToggleableState.On
                                                    groupSome -> ToggleableState.Indeterminate
                                                    else -> ToggleableState.Off
                                                },
                                                onClick = {
                                                    selected = if (groupAll) selected - keys
                                                    else selected + keys
                                                },
                                                contentDescription = "全选待确认照片"
                                            )
                                        }
                                    }
                                    Text(
                                        "这些照片文件已带坐标、大概率正常显示；" +
                                            (if (outcome?.calibration?.isUsable == true)
                                                "但个别行坐标读取失败，无法确认"
                                            else
                                                "本机媒体库坐标列读不出，无法确认行坐标是否在位") +
                                            "。如发现某张在相册里没位置，展开勾选即可修复",
                                        fontSize = DesignTokens.SectionSub,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (showUnknown) {
                                items(unknown, key = { it.uri.toString() }) { row ->
                                    RecoveryGridCell(
                                        row = row,
                                        selected = row.uri.toString() in selected,
                                        onToggle = {
                                            val key = row.uri.toString()
                                            selected = if (key in selected) selected - key
                                            else selected + key
                                        }
                                    )
                                }
                            }
                        }
                        if (showOk && okRows.isNotEmpty()) {
                            item(key = "hdr_ok", span = { GridItemSpan(DesignTokens.GridColumns) }) {
                                ClassificationHeader(
                                    title = "已正常（${okRows.size}）",
                                    subtitle = "文件与媒体库行均带坐标",
                                    group = emptyList(),
                                    selected = selected,
                                    onToggleGroup = { _, _ -> }
                                )
                            }
                            items(okRows, key = { it.uri.toString() }) { row ->
                                RecoveryGridCell(row = row, selected = false, onToggle = {})
                            }
                        }
                    }
                }
            }
        }
        }

        // ── 底部玻璃 Dock：空闲（三按钮）/ 重建中（进度条）──
        if (locationGranted) {
            HazeGlassCard(
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                radius = DesignTokens.DockRadius,
                contentPadding = PaddingValues(DesignTokens.DockPadding)
            ) {
                if (repairing) {
                    val done = repairProgress.first
                    val total = repairProgress.second
                    Text(
                        "已重建 $done/$total" + (repairCurrent?.let { " · $it" } ?: ""),
                        style = TextStyle(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassProgressBar(
                        progress = if (total > 0) done.toFloat() / total else 0f,
                        height = 5.dp, active = true
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { reportWithCoords = !reportWithCoords }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = reportWithCoords,
                            onCheckedChange = { reportWithCoords = it }
                        )
                        Text(
                            "报告包含精确坐标（默认脱敏）",
                            style = TextStyle(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassOutlineButton(
                            text = "重新扫描",
                            color = if (!scanning) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                            onClick = { if (!scanning) startScan() }
                        )
                        GlassOutlineButton(
                            text = "导出报告",
                            color = if (!scanning && rows.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                            onClick = { if (!scanning && rows.isNotEmpty()) exportReport() }
                        )
                        GlassOutlineButton(
                            // 文案保持单行宽度（组件已兜底 maxLines=1，这里同步缩短）
                            text = "修复（${selected.size}）",
                            color = if (selected.isNotEmpty() && !scanning)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                            onClick = { if (selected.isNotEmpty() && !scanning) confirm = true }
                        )
                    }
                }
            }
        }
    }

    if (confirm) {
        GlassDialog(
            hazeState = hazeState,
            onDismissRequest = { confirm = false },
            title = "修复所选（${selected.size}）",
            confirmLabel = "开始修复",
            onConfirm = {
                confirm = false
                startRebuild()
            },
            dismissLabel = "取消",
            onDismissAction = { confirm = false }
        ) {
            Text(
                "逐张修复让相册显示位置：EXIF 缺失的先把坐标写回文件（照片内容与文件名" +
                    "不变，字节逐字节一致），再重建媒体库记录——旧记录进相册" +
                    "「最近删除 / 第三方删除拦截」（可恢复），收藏状态保留，每张修复后读回验证。\n\n" +
                    "⚠ 修复过的照片「加入相册时间」可能被系统重置为操作当天" +
                    "（平台限制；拍摄时间与相册时间线不受影响）。\n\n" +
                    "存储空间不足时自动暂停。确认开始？"
            )
        }
    }
    summary?.let { msg ->
        GlassDialog(
            hazeState = hazeState,
            onDismissRequest = {
                summary = null
                startScan()
            },
            title = "修复结果",
            confirmLabel = "知道了",
            onConfirm = {
                summary = null
                startScan()
            }
        ) {
            Text(msg + "\n\n可点底部「导出报告」把逐张判定与修复明细回传，便于定位问题。")
        }
    }
}

/** 分类分段头（选择器日期分段同款）：主标题 + 副文本 + 可选组全选圈（支持半选态） */
@Composable
private fun ClassificationHeader(
    title: String,
    subtitle: String,
    group: List<LocationDisplayRepair.HealthRow>,
    selected: Set<String>,
    onToggleGroup: (Set<String>, Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(
            start = 8.dp, top = DesignTokens.SectionTop, bottom = DesignTokens.SectionBottom),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = DesignTokens.SectionTitle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = DesignTokens.SectionSub,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (group.isNotEmpty()) {
            val keys = group.map { it.uri.toString() }.toSet()
            val groupAll = keys.all { it in selected }
            val groupSome = keys.any { it in selected }
            CircleTriCheckbox(
                state = when {
                    groupAll -> ToggleableState.On
                    groupSome -> ToggleableState.Indeterminate
                    else -> ToggleableState.Off
                },
                onClick = { onToggleGroup(keys, !groupAll) },
                contentDescription = "全选$title"
            )
        }
    }
}

/**
 * 体检网格格子（选择器 GridCell 同款语言）：选中 0.86 中心缩放 + 12→16dp 圆角动画 +
 * 右上角弹簧勾选徽章 + 左下角实况点 + 右下角分类角标（与「已添加/已转」同槽位）。
 * 不可修复的格子（无位置/读取失败/已正常）不响应点击、不显示勾选圈，角标即状态。
 */
@Composable
private fun RecoveryGridCell(
    row: LocationDisplayRepair.HealthRow,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    val selectable = row.classification == LocationHealth.Classification.REPAIR_NEEDED ||
        row.classification == LocationHealth.Classification.UNKNOWN
    val transition = updateTransition(selected, label = "recoveryCell")

    // 选中时以几何中心为定点缩小（选择器同款动效，未退化）
    val scale by transition.animateFloat(label = "scale") {
        if (it) DesignTokens.CellScaleSelected else 1f
    }
    val cornerFraction by transition.animateFloat(label = "cornerF") { if (it) 1f else 0f }
    val cornerRadius = lerp(DesignTokens.CellRadius, DesignTokens.CellRadiusSelected, cornerFraction)

    val badgeBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(200), label = "badgeBg"
    )
    val badgeScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "badgeScale"
    )

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(continuousShape(DesignTokens.CellRadius))
            .clickable(enabled = selectable, onClick = onToggle)
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .clip(continuousShape(cornerRadius))
                .border(
                    width = if (selected) DesignTokens.CellBorderSelected else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    shape = continuousShape(cornerRadius)
                )
        ) {
            MediaThumbnail(row.uri, resolver, Modifier.fillMaxSize())
        }

        // 实况标记（左下角小圆点）：这些都是单文件实况（选择器同款）
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .size(8.dp)
                .background(Color.White.copy(alpha = 0.9f), CircleShape)
        )

        // 分类角标（右下角，「已添加/已转」同槽位同规格）：
        // 需修复=琥珀（与主界面丢位置警告同语义色）；无位置/待确认=中性黑；
        // 读取失败=error；已正常=不显示（干净格子）
        val (badgeText, badgeBgColor) = when (row.classification) {
            LocationHealth.Classification.REPAIR_NEEDED ->
                "需修复" to statusColors.warning
            LocationHealth.Classification.UNKNOWN ->
                "待确认" to Color.Black.copy(alpha = 0.55f)
            LocationHealth.Classification.NO_LOCATION ->
                "无位置" to Color.Black.copy(alpha = 0.55f)
            LocationHealth.Classification.UNREADABLE ->
                "读失败" to MaterialTheme.colorScheme.error
            LocationHealth.Classification.OK -> "" to Color.Transparent
        }
        if (badgeText.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(continuousShape(DesignTokens.CornerBadgeRadius))
                    .background(badgeBgColor.copy(alpha = 0.88f))
                    .padding(
                        horizontal = DesignTokens.CornerBadgeHPadding,
                        vertical = DesignTokens.CornerBadgeVPadding)
            ) {
                Text(
                    badgeText,
                    fontSize = DesignTokens.CornerBadgeFontSize,
                    lineHeight = DesignTokens.CornerBadgeLineHeight,
                    color = Color.White
                )
            }
        }

        // 勾选徽章（右上角）：选中 primary 圆底 + 白勾，未选中空心圈（弹簧弹入）
        if (selectable) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(DesignTokens.CheckBadgeSize)
                    .graphicsLayer {
                        scaleX = badgeScale
                        scaleY = badgeScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .background(badgeBg, CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
