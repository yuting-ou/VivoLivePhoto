package com.vliveconvert.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.ui.theme.statusColors
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 找回位置（选中式「重建位置显示」，独立入口）：
 *
 * 全量重建曾是一次跑完整个相机相册（数千张、分批数小时），用户只想修那几张
 * 「昨天转完才发现相册里没位置」的照片，却要陪着全库重建、还连带把全部照片的
 * 「加入相册时间」重置掉。本页先把缺坐标的本工具产物**检测**出来，用户**勾选**
 * 后只重建选中的几张。
 *
 * 修复机制与全量扫同源（[LocationDisplayRepair.recreateRow] 行重建）：
 * 字节逐字节不变、坐标写回媒体库；产物文件本身无坐标时从嵌入视频段恢复；
 * 「加入相册时间」会被系统重置为重建当天（确认弹窗明示）。
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

    var scanning by remember { mutableStateOf(true) }
    var scanProgress by remember { mutableStateOf(0 to 0) }
    var rows by remember { mutableStateOf<List<LocationDisplayRepair.MissingRow>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var rebuilding by remember { mutableStateOf(false) }
    var rebuildProgress by remember { mutableFloatStateOf(0f) }
    var confirm by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }

    val startScan: () -> Unit = {
        scanning = true
        scanProgress = 0 to 0
        rows = emptyList()
        selected = emptySet()
        summary = null
        scope.launch(Dispatchers.IO) {
            // 进度回调是普通函数（非挂起）：mutableStateOf 可从任意线程写（快照系统保证安全）
            val found = LocationDisplayRepair.scanMissingLocation(context) { done, total ->
                scanProgress = done to total
            }
            withContext(Dispatchers.Main) {
                rows = found
                // 缺坐标的产物默认全选：用户的诉求就是「把没位置的修回来」，
                // 逐个手点反而是负担；不想修的自行取消勾选
                selected = found.map { it.name }.toSet()
                scanning = false
            }
        }
    }

    val startRebuild: () -> Unit = {
        val targets = rows.filter { it.name in selected }
        if (targets.isNotEmpty() && !rebuilding) {
            rebuilding = true
            scope.launch(Dispatchers.IO) {
                val processed = LocationDisplayRepair.loadProcessed(context)
                val stats = LocationDisplayRepair.rebuildSelected(
                    context, targets, processed,
                    onProgress = { done, total ->
                        rebuildProgress = if (total > 0) done.toFloat() / total else 0f
                    })
                LocationDisplayRepair.saveProcessed(context, processed)
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

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 100.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            if (!locationGranted) {
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
            } else {
                Text(
                    "这里列出本工具转换、但相册里不显示拍摄位置的产物。" +
                        "勾选后重建，只动选中的几张；照片内容与文件名不变。",
                    style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (locationGranted) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            scanning -> "扫描中 ${scanProgress.first}/${scanProgress.second}"
                            rebuilding -> "重建中 ${selected.size} 张"
                            else -> "发现 ${rows.size} 张没有位置"
                        },
                        style = glassSubtitleStyle(), modifier = Modifier.weight(1f)
                    )
                }
                if (scanning) {
                    Spacer(Modifier.height(6.dp))
                    GlassIndeterminateBar(Modifier.height(3.dp))
                }
                if (rebuilding) {
                    Spacer(Modifier.height(6.dp))
                    GlassProgressBar(progress = rebuildProgress, height = 5.dp, active = true)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "重建中，请勿离开本页；完成后会自动提示。",
                        style = TextStyle(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))
                if (!scanning && rows.isEmpty()) {
                    GlassCard(radius = 24.dp, contentPadding = PaddingValues(16.dp)) {
                        Text(
                            "没有发现缺位置的产物。\n\n只有「相册里不显示拍摄位置」的本工具" +
                                "产物会出现在这里；刚转换完的照片稍等片刻再扫描。",
                            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    GlassCard(radius = 24.dp, contentPadding = PaddingValues(vertical = 6.dp)) {
                        rows.forEach { row ->
                            val checked = row.name in selected
                            val uri = row.uri
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = checked, role = Role.Checkbox,
                                        onValueChange = {
                                            selected = if (checked) selected - row.name
                                            else selected + row.name
                                        })
                                    .semantics { contentDescription = "选择 ${row.name}" }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 46.dp, height = 46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    MediaThumbnail(uri, resolver, Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.name,
                                        style = TextStyle(fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        if (row.dateTaken > 0)
                                            "拍摄 " + SimpleDateFormat(
                                                "yyyy-MM-dd HH:mm", Locale.getDefault()
                                            ).format(Date(row.dateTaken))
                                        else "拍摄时间未知",
                                        style = TextStyle(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                CircleTriCheckbox(
                                    state = if (checked) androidx.compose.ui.state.ToggleableState.On
                                    else androidx.compose.ui.state.ToggleableState.Off,
                                    onClick = null
                                )
                            }
                            GlassDivider(startInset = 12.dp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        }

        // 底部动作 Dock：与修复破损实况同款液态玻璃
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassOutlineButton(
                        text = "重新扫描",
                        color = if (!rebuilding) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                        onClick = { if (!rebuilding) startScan() }
                    )
                    GlassOutlineButton(
                        text = "重建所选（${selected.size}）",
                        color = if (selected.isNotEmpty() && !rebuilding && !scanning)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (selected.isNotEmpty() && !rebuilding && !scanning) confirm = true
                        }
                    )
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
