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
import androidx.compose.material.icons.filled.Share
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
 * 找回位置（v2.6.0-test27 重做：位置体检 + 证据可见 + 诊断报告）。
 *
 * 判定**全部基于产物文件字节**（EXIF/XMP/视频段），不再查询媒体库坐标列
 * （test24~26 已证其在 vivo 上读不回）。每张产物一行：判定结论 + 判定依据
 * （每个来源解析到了什么），用户能直接看出「哪些有位置、哪些缺、哪些读不动」。
 *
 * 修复（[LocationHealth.repairProduct]）：EXIF 缺失的先写回文件 EXIF，再行重建
 * （字节逐字节一致、旧记录进回收站可恢复、收藏随迁），每张修复后**读回验证**。
 * 「导出诊断报告」把逐张判定与修复路径/结果整成文本分享出来——真机回传它，
 * 不用再靠猜定位问题。
 */
@Composable
fun LocationRecoveryScreen(
    /** 「位置」权限：读文件坐标仍被它管辖，扫描前必须拿到（未授予只显示引导卡） */
    locationGranted: Boolean,
    onGrantLocation: () -> Unit,
    onBack: () -> Unit,
    hazeState: HazeState = rememberGlassHazeState()
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var scanning by remember { mutableStateOf(true) }
    // (已处理, 总数, 已发现产物数)
    var scanProgress by remember { mutableStateOf(Triple(0, 0, 0)) }
    var rows by remember { mutableStateOf<List<LocationDisplayRepair.HealthRow>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    // 最近一次修复的逐张明细（诊断报告用）
    var lastRecords by remember { mutableStateOf<List<LocationHealth.ReportLine>>(emptyList()) }
    // 重建进度来自 LocationRepairService 的共享状态（后台跑，离开页面不中断）
    val repairing = ConvertCenter.isLocationRepairing
    val repairProgress = ConvertCenter.repairProgress
    val repairCurrent = ConvertCenter.repairCurrent

    // 可修复的判定（EXIF 缺：注入+重建；EXIF 在：可强制重建让行坐标就位）
    fun LocationHealth.Verdict.repairable(): Boolean = this in setOf(
        LocationHealth.Verdict.XMP_ONLY, LocationHealth.Verdict.VIDEO_ONLY,
        LocationHealth.Verdict.HAS_EXIF)

    val startScan: () -> Unit = {
        if (!ConvertCenter.isLocationRepairing) {
            scanning = true
            scanProgress = Triple(0, 0, 0)
            rows = emptyList()
            selected = emptySet()
            scope.launch(Dispatchers.IO) {
                val found = LocationDisplayRepair.scanProducts(context) { done, total, foundN ->
                    scanProgress = Triple(done, total, foundN)
                }
                withContext(Dispatchers.Main) {
                    rows = found
                    // 默认选中「EXIF 缺失」的（它们是真要修的）；EXIF 在的留手动
                    selected = found.filter {
                        it.probe.verdict in setOf(
                            LocationHealth.Verdict.XMP_ONLY,
                            LocationHealth.Verdict.VIDEO_ONLY)
                    }.map { it.uri.toString() }.toSet()
                    scanning = false
                }
            }
        }
    }

    val startRebuild: () -> Unit = {
        val targets = rows.filter { it.uri.toString() in selected }
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
        if (!repairing && !scanning && ConvertCenter.repairResult == null && rows.isNotEmpty()) {
            startScan()
        }
    }

    LaunchedEffect(locationGranted) {
        if (locationGranted && !ConvertCenter.isLocationRepairing) startScan()
    }
    BackHandler(enabled = !repairing) { onBack() }

    // 计数与分组
    val counts = remember(rows) {
        LocationHealth.Verdict.entries.associateWith { v -> rows.count { it.probe.verdict == v } }
    }
    val selectable = remember(rows) { rows.filter { it.probe.verdict.repairable() } }
    val allSelected = selectable.isNotEmpty() && selected.size == selectable.size
    val sections = remember(rows) {
        rows.groupBy { r -> if (r.dateTaken > 0) dateLabel(r.dateTaken) else "时间未知" }
            .map { (label, list) -> label to list }
    }

    val exportReport: () -> Unit = {
        val lines = rows.map { r ->
            val record = lastRecords.firstOrNull { it.name == r.name }
            LocationHealth.ReportLine(
                name = r.name,
                verdict = r.probe.verdictLabel,
                evidence = r.probe.evidence(),
                repair = record?.repair?.let { "${record.verdict}：$it" })
        } + lastRecords.filter { rec -> rows.none { it.name == rec.name } }
        // 版本信息走 PackageManager（不依赖 BuildConfig 生成开关）
        val (verName, verCode) = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName to pi.longVersionCode
        } catch (_: Exception) {
            "unknown" to 0L
        }
        val report = LocationHealth.buildReport(
            appVersion = "$verName ($verCode)",
            device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            rows = lines,
            summary = "共 ${rows.size} 张产物：" +
                "有位置 ${counts[LocationHealth.Verdict.HAS_EXIF] ?: 0}，" +
                "EXIF 缺失 ${(counts[LocationHealth.Verdict.XMP_ONLY] ?: 0) +
                    (counts[LocationHealth.Verdict.VIDEO_ONLY] ?: 0)}，" +
                "无坐标 ${counts[LocationHealth.Verdict.NONE] ?: 0}，" +
                "读失败 ${counts[LocationHealth.Verdict.UNREADABLE] ?: 0}"
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
                        else -> "共 ${rows.size} 张：有位置 ${counts[LocationHealth.Verdict.HAS_EXIF] ?: 0}" +
                            " · EXIF 缺失 ${(counts[LocationHealth.Verdict.XMP_ONLY] ?: 0) +
                                (counts[LocationHealth.Verdict.VIDEO_ONLY] ?: 0)}" +
                            " · 无坐标 ${counts[LocationHealth.Verdict.NONE] ?: 0}" +
                            (if ((counts[LocationHealth.Verdict.UNREADABLE] ?: 0) > 0)
                                " · 读失败 ${counts[LocationHealth.Verdict.UNREADABLE]}" else "")
                    },
                    style = glassSubtitleStyle(), modifier = Modifier.weight(1f)
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
                                "正在逐张读取产物文件做体检，已发现 ${scanProgress.third} 张",
                                style = TextStyle(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    rows.isEmpty() -> Box(
                        Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "没有发现本工具产物\n\n体检范围：转换产物（EXIF/XMP/视频段逐张判定），\n" +
                                "判定与修复都不依赖媒体库坐标列",
                            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 4.dp, end = 4.dp, top = 4.dp, bottom = 116.dp)
                    ) {
                        sections.forEach { (label, group) ->
                            item(key = "hdr_$label") {
                                val groupKeys = group
                                    .filter { it.probe.verdict.repairable() }
                                    .map { it.uri.toString() }.toSet()
                                val groupAll = groupKeys.isNotEmpty() && groupKeys.all { it in selected }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(start = 12.dp, top = 14.dp, bottom = 6.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            label,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "${group.size} 张",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (groupKeys.isNotEmpty()) {
                                        CircleTriCheckbox(
                                            state = if (groupAll) ToggleableState.On
                                            else ToggleableState.Off,
                                            onClick = {
                                                selected = if (groupAll) selected - groupKeys
                                                else selected + groupKeys
                                            },
                                            contentDescription = "全选 $label 的可修复照片"
                                        )
                                    }
                                }
                            }
                            items(group, key = { it.uri.toString() }) { row ->
                                HealthRowCell(
                                    row = row,
                                    selected = row.uri.toString() in selected,
                                    selectable = row.probe.verdict.repairable(),
                                    onToggle = {
                                        val key = row.uri.toString()
                                        selected = if (key in selected) selected - key
                                        else selected + key
                                    }
                                )
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

/** 体检行：缩略图 + 文件名 + 判定结论徽章 + 判定依据（每个来源解析到了什么）+ 勾选圈。 */
@Composable
private fun HealthRowCell(
    row: LocationDisplayRepair.HealthRow,
    selected: Boolean,
    selectable: Boolean,
    onToggle: () -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    val verdict = row.probe.verdict
    val (chipColor, chipBg) = when (verdict) {
        LocationHealth.Verdict.HAS_EXIF -> statusColors.success to
            statusColors.success.copy(alpha = 0.12f)
        LocationHealth.Verdict.XMP_ONLY, LocationHealth.Verdict.VIDEO_ONLY ->
            statusColors.warning to statusColors.warning.copy(alpha = 0.14f)
        LocationHealth.Verdict.NONE -> MaterialTheme.colorScheme.onSurfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
        LocationHealth.Verdict.UNREADABLE -> MaterialTheme.colorScheme.error to
            MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
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
                        row.probe.verdictLabel +
                            if (verdict == LocationHealth.Verdict.VIDEO_ONLY) "（坐标在视频段）" else "",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = chipColor
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                row.probe.evidence() + when {
                    verdict == LocationHealth.Verdict.HAS_EXIF ->
                        "（相册不显示位置时也可勾选强制重建）"
                    verdict == LocationHealth.Verdict.NONE -> "（三处都没有，无可修复）"
                    verdict == LocationHealth.Verdict.UNREADABLE -> "（文件读失败，无法判定）"
                    else -> ""
                },
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
