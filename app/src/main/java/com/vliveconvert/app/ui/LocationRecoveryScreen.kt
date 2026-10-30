package com.vliveconvert.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.service.BatchLog
import com.vliveconvert.app.ui.theme.statusColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 找回位置（选中式「重建位置显示」，独立入口）：
 *
 * 全量重建曾是一次跑完整个相机相册（数千张、分批数小时），用户只想修那几张
 * 「昨天转完才发现相册里没位置」的照片，却要陪着全库重建、还连带把全部照片的
 * 「加入相册时间」重置掉。本页先把缺坐标的本工具产物**检测**出来，用户**勾选**
 * 后只重建选中的几张。
 *
 * 交互对齐「选择照片转换」：3 列网格 + 日期分段（段头组全选）+ 工具行（张数/已选/全选）
 * + 底部玻璃 Dock（重建进度 = 转换进度的同款表达）。修复机制与全量扫同源
 * （[LocationDisplayRepair.recreateRow] 行重建）：字节逐字节不变、坐标写回媒体库；
 * 产物文件本身无坐标时从嵌入视频段恢复；「加入相册时间」会被系统重置为重建当天
 * （确认弹窗明示）。重建结果写入「转换与修复记录」（BatchLog.appendRepair）。
 */
@Composable
fun LocationRecoveryScreen(
    /** 「位置」权限：坐标列的读取被它管辖，扫描前必须拿到（未授予只显示引导卡） */
    locationGranted: Boolean,
    onGrantLocation: () -> Unit,
    onBack: () -> Unit,
    hazeState: HazeState = rememberGlassHazeState()
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val gridState = rememberLazyGridState()

    var scanning by remember { mutableStateOf(true) }
    // (已嗅探, 待嗅探总数, 已发现缺坐标产物数)
    var scanProgress by remember { mutableStateOf(Triple(0, 0, 0)) }
    var rows by remember { mutableStateOf<List<LocationDisplayRepair.MissingRow>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var rebuilding by remember { mutableStateOf(false) }
    var rebuildDone by remember { mutableStateOf(0 to 0) }
    var rebuildCurrent by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }

    val startScan: () -> Unit = {
        scanning = true
        scanProgress = Triple(0, 0, 0)
        rows = emptyList()
        selected = emptySet()
        summary = null
        scope.launch(Dispatchers.IO) {
            val found = LocationDisplayRepair.scanMissingLocation(context) { done, total, foundN ->
                scanProgress = Triple(done, total, foundN)
            }
            withContext(Dispatchers.Main) {
                rows = found
                // 缺坐标的产物默认全选：用户的诉求就是「把没位置的修回来」，
                // 逐个手点反而是负担；不想修的自行取消勾选
                selected = found.map { it.uri.toString() }.toSet()
                scanning = false
            }
        }
    }

    val startRebuild: () -> Unit = {
        val targets = rows.filter { it.uri.toString() in selected }
        if (targets.isNotEmpty() && !rebuilding) {
            rebuilding = true
            scope.launch(Dispatchers.IO) {
                val processed = LocationDisplayRepair.loadProcessed(context)
                val stats = LocationDisplayRepair.rebuildSelected(
                    context, targets, processed,
                    onProgress = { done, total ->
                        rebuildDone = done to total
                        rebuildCurrent = targets.getOrNull(done)?.name
                    })
                LocationDisplayRepair.saveProcessed(context, processed)
                // 修复记录落盘：与破损实况修复同一通道，记录页显示「修复」条目
                BatchLog.appendRepair(context, stats.rebuilt, stats.failed, null)
                val msg = StringBuilder("重建成功 ${stats.rebuilt} 张").apply {
                    if (stats.noCoords > 0) append("，无坐标可恢复跳过 ${stats.noCoords} 张")
                    if (stats.failed > 0) append("，失败 ${stats.failed} 张")
                    append("。")
                    if (stats.pausedForSpace) {
                        append("\n\n可用空间不足已暂停——请到相册「最近删除 / 第三方删除拦截」" +
                            "清空回收站后重新扫描，剩余的会继续出现在列表里。")
                    }
                    if (stats.rebuilt > 0) {
                        append("\n\n重建过的照片「加入相册时间」已变为今天（拍摄时间不受影响）。")
                    }
                }
                withContext(Dispatchers.Main) {
                    rebuilding = false
                    summary = msg.toString()
                }
            }
        }
    }

    // 完成节拍触感：与转换底栏的完成态同一时刻、同一语义
    LaunchedEffect(summary) {
        if (summary != null) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
    }

    // 日期分段：扫描结果已按拍摄时间倒序，分组保持相遇顺序（新的段在前）
    val sections = remember(rows) {
        rows.groupBy { r -> if (r.dateTaken > 0) dateLabel(r.dateTaken) else "时间未知" }
            .map { (label, list) -> label to list }
    }
    val allSelected = rows.isNotEmpty() && selected.size == rows.size

    LaunchedEffect(locationGranted) {
        if (locationGranted && !rebuilding) startScan()
    }
    BackHandler(enabled = !rebuilding) { onBack() }

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
            if (rebuilding) {
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
                            "未授予「位置」权限，系统会抹掉坐标列，无法检测哪些照片缺位置。",
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
            // ── 工具行：张数（左） + 已选 + 全选（右），对齐选择器 ──
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        scanning -> "扫描中 ${scanProgress.first}/${scanProgress.second} · " +
                            "已发现 ${scanProgress.third} 张"
                        rebuilding -> "重建中 ${rebuildDone.first}/${rebuildDone.second}"
                        else -> "发现 ${rows.size} 张没有位置"
                    },
                    style = glassSubtitleStyle(), modifier = Modifier.weight(1f)
                )
                if (!scanning && rows.isNotEmpty()) {
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
                                else rows.map { it.uri.toString() }.toSet()
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
                                "正在逐张识别本工具产物，已发现 ${scanProgress.third} 张",
                                style = TextStyle(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    rows.isEmpty() -> Box(
                        Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "没有发现缺位置的产物\n\n只有「相册里不显示拍摄位置」的本工具产物" +
                                "会出现在这里；刚转换完的照片稍等片刻再扫描",
                            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 116.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        sections.forEach { (label, group) ->
                            item(
                                key = "hdr_$label",
                                span = { GridItemSpan(3) },
                                contentType = "header"
                            ) {
                                val groupKeys = group.map { it.uri.toString() }.toSet()
                                val groupAll = groupKeys.all { it in selected }
                                // 段头：日期 + 张数 + 组全选圈（与选择器同款交互）
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(start = 8.dp, top = 14.dp, bottom = 8.dp),
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
                                    CircleTriCheckbox(
                                        state = if (groupAll)
                                            androidx.compose.ui.state.ToggleableState.On
                                        else androidx.compose.ui.state.ToggleableState.Off,
                                        onClick = {
                                            selected = if (groupAll) selected - groupKeys
                                            else selected + groupKeys
                                        },
                                        contentDescription = "全选 $label 的照片"
                                    )
                                }
                            }
                            items(
                                group,
                                key = { it.uri.toString() },
                                contentType = { "photo" }
                            ) { row ->
                                RecoveryCell(
                                    uri = row.uri,
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
                }
            }
        }
        }

        // ── 底部玻璃 Dock：空闲（两按钮）/ 重建中（进度条），对齐转换底栏 ──
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
                if (rebuilding) {
                    val (done, total) = rebuildDone
                    Text(
                        "已重建 $done/$total" + (rebuildCurrent?.let { " · $it" } ?: ""),
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
                            text = "重建所选（${selected.size}）",
                            color = if (selected.isNotEmpty() && !scanning)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (selected.isNotEmpty() && !scanning) confirm = true
                            }
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
            title = "重建所选（${selected.size}）",
            confirmLabel = "开始重建",
            onConfirm = {
                confirm = false
                startRebuild()
            },
            dismissLabel = "取消",
            onDismissAction = { confirm = false }
        ) {
            Text(
                "逐张重建媒体库记录让相册显示位置：照片内容与文件名不变，字节逐字节一致；" +
                    "旧记录进相册「最近删除 / 第三方删除拦截」（可恢复）。\n\n" +
                    "⚠ 重建过的照片「加入相册时间」会重置为操作当天（微信等按加入时间分组的" +
                    "应用会把它们归入「今天」），拍摄时间与相册时间线不受影响。\n\n" +
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
            title = "重建结果",
            confirmLabel = "知道了",
            onConfirm = {
                summary = null
                startScan()
            }
        ) {
            Text(msg)
        }
    }
}

/**
 * 网格单元：对齐选择器 GridCell 的选中语言（中心缩放 + 圆角增大 + 右上角勾选徽章），
 * 省去序号/「已转」角标——这里所有格子都是待修的产物，没有额外状态要表达。
 */
@Composable
private fun RecoveryCell(uri: android.net.Uri, selected: Boolean, onToggle: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.86f else 1f,
        animationSpec = tween(200), label = "scale"
    )
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
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
                .clip(RoundedCornerShape(if (selected) 16.dp else 12.dp))
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(if (selected) 16.dp else 12.dp)
                )
        ) {
            MediaThumbnail(uri, resolver, Modifier.fillMaxSize())
        }
        // 实况标记（左下角小圆点）：这些都是单文件实况
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .size(8.dp)
                .background(Color.White.copy(alpha = 0.9f), CircleShape)
        )
        // 勾选徽章（右上角）：选中 primary 圆底 + 白勾，未选中空心圈
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp)
                .size(22.dp)
                .graphicsLayer {
                    scaleX = badgeScale
                    scaleY = badgeScale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .background(badgeBg, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (scaleIn(
                        initialScale = 0.5f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(tween(200))) togetherWith
                        (scaleOut(targetScale = 0.5f, animationSpec = tween(150)) +
                            fadeOut(tween(150)))
                },
                label = "badge"
            ) { sel ->
                if (sel) {
                    Icon(
                        Icons.Filled.Check, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(14.dp)
                    )
                } else {
                    Box(Modifier.size(14.dp))
                }
            }
        }
    }
}
