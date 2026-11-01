package com.vliveconvert.app.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
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
import com.vliveconvert.app.ui.theme.statusColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 找回位置（v2.6.0-test28：检测重做——「需要修复」= 相册里不显示位置）。
 *
 * 判定 = 矩阵（文件字节 EXIF/XMP/视频段 × 行坐标，行坐标可读性每次扫描现场校准）。
 * 列表策略：默认只列「需修复」（可修）与「无位置」（源无坐标、修不了，如实告知）；
 * 「待确认」（列不可读时文件有坐标的那批）折叠展示并说明原因；「已正常」只计数、
 * 点计数可展开核对。不再全量罗列——那是把信号的不确定性甩给用户。
 *
 * 修复（[LocationHealth.repairProduct]）：EXIF 缺的先写回文件，再行重建（字节逐字节
 * 一致、旧记录进回收站可恢复、收藏随迁），每张修复后读回验证。
 * 「导出诊断报告」含校准环境与逐张信号原始读数，真机回传即可定位。
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

    var scanning by remember { mutableStateOf(true) }
    // (已处理, 总数, 已发现产物数)
    var scanProgress by remember { mutableStateOf(Triple(0, 0, 0)) }
    var outcome by remember {
        mutableStateOf<LocationDisplayRepair.ScanOutcome?>(null)
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    // 「待确认」默认折叠（列不可读时的诚实态，不与「需修复」混排）
    var showUnknown by remember { mutableStateOf(false) }
    // 「已正常」默认隐藏（只计数；点计数可展开核对）
    var showOk by remember { mutableStateOf(false) }
    // 最近一次修复的逐张明细（诊断报告用）
    var lastRecords by remember { mutableStateOf<List<LocationHealth.ReportLine>>(emptyList()) }
    // 重建进度来自 LocationRepairService 的共享状态（后台跑，离开页面不中断）
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
            // 后台服务执行重建：离开页面/切后台不中断，进度走共享状态 + 通知栏
            LocationRepairService.start(context, targets)
        }
    }

    // 服务完成/中止 → 结果摘要 + 逐张明细（服务写 repairResult/repairReport；消费后置回）
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

    // 重建完成回到本页时刷新列表：重建过的行已换新 URI、EXIF 已写回
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
    // 可勾选的 = 需修复 + （展开后的）待确认；无位置/读取失败不可选
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
                evidence = r.probe.evidence() + "；" + r.reason +
                    (r.rowCoordsRaw?.let { "；行列原始读数=${"%.5f,%.5f".format(it.first, it.second)}" }
                        ?: ""),
                repair = record?.repair?.let { "${record.verdict}：$it" })
        } + lastRecords.filter { rec -> rows.none { it.name == rec.name } }
        // 版本信息走 PackageManager（不依赖 BuildConfig 生成开关）
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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
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
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onBackground)
            )
        }

        if (!locationGranted) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                GlassCard(radius = 24.dp, contentPadding = PaddingValues(16.dp)) {
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
            // ── 工具行：计数摘要（左） + 全选（右）──
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp),
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
                            " · 已正常 ${okRows.size}（点击显示）"
                    },
                    style = glassSubtitleStyle(),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !scanning && okRows.isNotEmpty()) {
                            showOk = !showOk
                        }
                        .padding(vertical = 4.dp)
                )
                if (!scanning && selectable.isNotEmpty()) {
                    Text(
                        "已选 ${selected.size}",
                        style = TextStyle(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (allSelected) "取消全选" else "全选",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selected = if (allSelected) emptySet()
                                else selectable.map { it.uri.toString() }.toSet()
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }

            GlassCard(
                radius = 24.dp,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
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
                        val calib = outcome
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (okRows.isEmpty()) "没有发现本工具产物"
                                else "全部 ${okRows.size} 张产物的位置都正常\n\n" +
                                    "「需修复」= 相册里不显示位置的照片；判定基于文件坐标" +
                                    (if (calib?.calibration?.isUsable == true) "与媒体库行坐标"
                                    else "（本机媒体库坐标列读不出，行坐标未能核对）"),
                                style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (okRows.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (showOk) "收起已正常列表" else "查看已正常列表",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showOk = !showOk }
                                        .padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 4.dp, end = 4.dp, top = 4.dp, bottom = 116.dp)
                    ) {
                        if (repairNeeded.isNotEmpty()) {
                            item(key = "hdr_repair") {
                                SectionHeader(
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
                                HealthRowCell(
                                    row = row,
                                    selected = row.uri.toString() in selected,
                                    selectable = true,
                                    onToggle = {
                                        val key = row.uri.toString()
                                        selected = if (key in selected) selected - key
                                        else selected + key
                                    }
                                )
                            }
                        }
                        if (noLocation.isNotEmpty()) {
                            item(key = "hdr_noloc") {
                                SectionHeader(
                                    title = "无位置（${noLocation.size}）",
                                    subtitle = "源照片拍摄时未记录坐标，相册不显示且无法恢复",
                                    group = emptyList(),
                                    selected = selected,
                                    onToggleGroup = { _, _ -> }
                                )
                            }
                            items(noLocation, key = { it.uri.toString() }) { row ->
                                HealthRowCell(row = row, selected = false, selectable = false,
                                    onToggle = {})
                            }
                        }
                        if (unreadable.isNotEmpty()) {
                            item(key = "hdr_unreadable") {
                                SectionHeader(
                                    title = "读取失败（${unreadable.size}）",
                                    subtitle = "产物文件读取异常（故障，不是无坐标）",
                                    group = emptyList(),
                                    selected = selected,
                                    onToggleGroup = { _, _ -> }
                                )
                            }
                            items(unreadable, key = { it.uri.toString() }) { row ->
                                HealthRowCell(row = row, selected = false, selectable = false,
                                    onToggle = {})
                            }
                        }
                        if (unknown.isNotEmpty()) {
                            item(key = "hdr_unknown") {
                                // 待确认：折叠态说明「为什么不能确定」；展开后可勾选修复
                                Column(Modifier.fillMaxWidth().padding(
                                    start = 12.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "待确认（${unknown.size}）" +
                                                if (showUnknown) " · 收起" else " · 展开",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { showUnknown = !showUnknown }
                                                .padding(vertical = 6.dp)
                                        )
                                        if (showUnknown) {
                                            val keys = unknown.map { it.uri.toString() }.toSet()
                                            val groupAll = keys.all { it in selected }
                                            CircleTriCheckbox(
                                                state = if (groupAll) ToggleableState.On
                                                else ToggleableState.Off,
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
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (showUnknown) {
                                items(unknown, key = { it.uri.toString() }) { row ->
                                    HealthRowCell(
                                        row = row,
                                        selected = row.uri.toString() in selected,
                                        selectable = true,
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
                            item(key = "hdr_ok") {
                                SectionHeader(
                                    title = "已正常（${okRows.size}）",
                                    subtitle = "文件与媒体库行均带坐标",
                                    group = emptyList(),
                                    selected = selected,
                                    onToggleGroup = { _, _ -> }
                                )
                            }
                            items(okRows, key = { it.uri.toString() }) { row ->
                                HealthRowCell(row = row, selected = false, selectable = false,
                                    onToggle = {})
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
                radius = 28.dp,
                contentPadding = PaddingValues(10.dp)
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
                            text = "修复所选（${selected.size}）",
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

/** 分组头：标题 + 说明 + 可选的组全选圈（group 为空时不显示圈） */
@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    group: List<LocationDisplayRepair.HealthRow>,
    selected: Set<String>,
    onToggleGroup: (Set<String>, Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (group.isNotEmpty()) {
            val keys = group.map { it.uri.toString() }.toSet()
            val groupAll = keys.all { it in selected }
            CircleTriCheckbox(
                state = if (groupAll) ToggleableState.On else ToggleableState.Off,
                onClick = { onToggleGroup(keys, !groupAll) },
                contentDescription = "全选$title"
            )
        }
    }
}

/** 体检行：缩略图 + 文件名 + 分类徽章 + 判定依据（信号原始读数）+ 勾选圈。 */
@Composable
private fun HealthRowCell(
    row: LocationDisplayRepair.HealthRow,
    selected: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    val classification = row.classification
    val (chipColor, chipBg) = when (classification) {
        LocationHealth.Classification.REPAIR_NEEDED ->
            statusColors.warning to statusColors.warning.copy(alpha = 0.14f)
        LocationHealth.Classification.OK ->
            statusColors.success to statusColors.success.copy(alpha = 0.12f)
        LocationHealth.Classification.UNKNOWN ->
            MaterialTheme.colorScheme.onSurfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
        LocationHealth.Classification.NO_LOCATION ->
            MaterialTheme.colorScheme.onSurfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
        LocationHealth.Classification.UNREADABLE ->
            MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = selectable, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp)
                )
        ) {
            MediaThumbnail(row.uri, resolver, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(chipBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        classification.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = chipColor
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                row.reason,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        if (selectable) {
            Spacer(Modifier.width(8.dp))
            CircleTriCheckbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle,
                contentDescription = "选择 ${row.name}"
            )
        }
    }
}
