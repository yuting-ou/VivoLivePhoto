package com.vliveconvert.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState

@Composable
internal fun SettingsScreen(
    appName: String,
    versionName: String,
    outputRelPath: String,
    moveToCamera: Boolean,
    deleteOriginal: Boolean,
    perf: com.vliveconvert.app.service.PerfSettings = com.vliveconvert.app.service.PerfSettings(),
    perfAutoDetail: String = "",
    deviceInfo: String = "",
    onPerfChange: (com.vliveconvert.app.service.PerfSettings) -> Unit = {},
    crashLogCount: Int,
    isBusy: Boolean,
    onBack: () -> Unit,
    onEditOutputPath: () -> Unit,
    onToggleMoveToCamera: (Boolean) -> Unit,
    onToggleDeleteOriginal: (Boolean) -> Unit,
    onOpenFixTime: () -> Unit,
    batchLogCount: Int = 0,
    onOpenBatchLog: () -> Unit = {},
    onOpenBrokenRepair: () -> Unit,
    onRepairLocationDisplay: () -> Unit,
    onMoveOutputsToCamera: () -> Unit,
    onExportCrashLogs: () -> Unit,
    onClearCrashLogs: () -> Unit,
    dynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
    reduceMotion: Boolean = false,
    onToggleReduceMotion: (Boolean) -> Unit = {},
    onOpenAbout: () -> Unit,
    queueCount: Int = 0,
    statusText: String = "",
    hazeState: HazeState = rememberGlassHazeState()
) {
    Column(Modifier.fillMaxSize().glassWallpaper().glassBackdropSource(hazeState)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回", onClick = onBack, size = 38.dp
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "设置",
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val singleColumn = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).navigationBarsPadding()
            ) {
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    Text(
                        "已添加 $queueCount 张照片",
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildAnnotatedString {
                            append("输出 ${if (moveToCamera) "DCIM/Camera" else outputRelPath} · ")
                            if (deleteOriginal) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                                    append("删除原图")
                                }
                            } else {
                                append("保留原图")
                            }
                        },
                        style = settingsDescriptionStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (statusText.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        GlassDivider()
                        Spacer(Modifier.height(10.dp))
                        Text(statusText, style = settingsDescriptionStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsSection("输出")
                SettingsLinkCard("输出目录", outputRelPath, onEditOutputPath)
                SettingsSection("转换后处理")
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToggleCard(
                            Icons.AutoMirrored.Filled.ArrowForward, "移到相机相册",
                            "保存到 DCIM/Camera", moveToCamera, !isBusy,
                            onToggleMoveToCamera, modifier
                        )
                    },
                    second = { modifier ->
                        SettingsToggleCard(
                            Icons.Filled.Delete, "删除原图", "转换成功后处理原文件",
                            deleteOriginal, !isBusy, onToggleDeleteOriginal, modifier
                        )
                    }
                )
                SettingsSection("性能")
                SettingsCard {
                    SettingsToggleCard(
                        Icons.Filled.Refresh, "手动调节性能",
                        if (perf.manual) "手动挡：按下面的旋钮执行"
                        else "自动挡：按设备内存自动决定（推荐）",
                        perf.manual, true,
                        { on ->
                            onPerfChange(
                                if (on) com.vliveconvert.app.service.PerfPreset.Balanced.apply()
                                else perf.copy(manual = false))
                        },
                        Modifier
                    )
                    // 自动↔手动内容切换：淡入+轻上滑（与底栏三态切换同一套曲线）；
                    // 高度差用 SizeTransform 平滑过渡，减少动效时直切
                    val reduceMotion = LocalReduceMotion.current
                    AnimatedContent(
                        targetState = perf.manual,
                        transitionSpec = {
                            if (reduceMotion) {
                                (fadeIn(snap()) togetherWith fadeOut(snap()))
                            } else {
                                (fadeIn(tween(220, easing = EaseOutStrong)) +
                                    slideInVertically(tween(220, easing = EaseOutStrong)) { it / 6 }
                                ) togetherWith
                                    (fadeOut(tween(140))) using SizeTransform(clip = false)
                            }
                        },
                        label = "perfModeSwitch"
                    ) { manual ->
                        if (!manual) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                GlassDivider()
                                Spacer(Modifier.height(12.dp))
                                Text(perfAutoDetail, style = settingsDescriptionStyle(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(deviceInfo, style = settingsDescriptionStyle(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                GlassDivider()
                                Spacer(Modifier.height(12.dp))
                                Text("一键预设（填充下面四个旋钮）", style = settingsDescriptionStyle(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(2.dp))
                                // 量墨迹：InlineTextButton 文字内边距 10dp，行整体左移 10dp
                                // 让「省电/均衡/性能」的字左缘与下方旋钮标题左缘对齐
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier // DEBUG: offset 已移除以定位点击失效
                                ) {
                                    val view = LocalView.current
                                    com.vliveconvert.app.service.PerfPreset.entries.forEach { p ->
                                        InlineTextButton(
                                            text = p.label,
                                            contentColor = MaterialTheme.colorScheme.primary,
                                            onClick = {
                                                // 预设是「一键落定」：CONFIRM 触觉 + 旋钮弹簧滑移到新值
                                                view.performHapticFeedback(
                                                    HapticFeedbackConstants.CONFIRM)
                                                onPerfChange(p.apply())
                                            },
                                            style = TextStyle(fontSize = 14.sp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                PerfSlider("转换并发", "并发 ${perf.concurrency} 张",
                                    perf.concurrency.toFloat(), 1f..8f, steps = 6, hapticStep = true
                                ) { v ->
                                    onPerfChange(perf.copy(concurrency = v.roundToInt().coerceIn(1, 8)))
                                }
                                PerfSlider("内存预算上限", "约 ${perf.memoryLimitMb} MB",
                                    perf.memoryLimitMb.toFloat(),
                                    128f..com.vliveconvert.app.service.PerfSettings.memoryUpperMb().toFloat(),
                                    steps = 0, hapticStep = false
                                ) { v ->
                                    onPerfChange(perf.copy(memoryLimitMb = v.roundToInt()
                                        .coerceIn(128, com.vliveconvert.app.service.PerfSettings.memoryUpperMb())))
                                }
                                PerfSlider("相册扫描线程", "${perf.scanThreads} 个",
                                    perf.scanThreads.toFloat(), 1f..8f, steps = 6, hapticStep = true
                                ) { v ->
                                    onPerfChange(perf.copy(scanThreads = v.roundToInt().coerceIn(1, 8)))
                                }
                                PerfSlider("缩略图线程", "${perf.thumbThreads} 个",
                                    perf.thumbThreads.toFloat(), 1f..8f, steps = 6, hapticStep = true
                                ) { v ->
                                    onPerfChange(perf.copy(thumbThreads = v.roundToInt().coerceIn(1, 8)))
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "修改对下一批转换生效；拧过头也有内存预算闸和自动重试兜底。",
                                    style = settingsDescriptionStyle(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                SettingsSection("外观")
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToggleCard(
                            Icons.Filled.Star, "跟随系统取色", "莫奈取色，关闭回品牌蓝",
                            dynamicColor, true, onToggleDynamicColor, modifier
                        )
                    },
                    second = { modifier ->
                        SettingsToggleCard(
                            Icons.Filled.PlayArrow, "减少动效", "减少高光与弹窗动画",
                            reduceMotion, true, onToggleReduceMotion, modifier
                        )
                    }
                )
                SettingsSection("工具")
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.DateRange, "修复文件时间", "按拍摄时间校正",
                            onOpenFixTime, modifier
                        )
                    },
                    second = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Build, "修复破损实况", "合并成实况，保留原文件",
                            onOpenBrokenRepair, modifier
                        )
                    }
                )
                Spacer(Modifier.height(12.dp))
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Place, "找回位置", "体检产物位置，选中修复并可导出诊断报告",
                            onRepairLocationDisplay, modifier, available = !isBusy
                        )
                    },
                    second = { modifier ->
                        SettingsToolCard(
                            Icons.AutoMirrored.Filled.ArrowForward, "移动输出到相册",
                            "已有产物搬进相机相册", onMoveOutputsToCamera,
                            modifier, available = !isBusy
                        )
                    }
                )
                SettingsSection("记录与诊断")
                SettingsLinkCard(
                    "转换与修复记录（$batchLogCount）", "查看最近 50 批的结果与产物",
                    onOpenBatchLog
                )
                Spacer(Modifier.height(12.dp))
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Share, "导出崩溃日志（$crashLogCount）",
                            "导出到 Download", onExportCrashLogs,
                            modifier
                        )
                    },
                    second = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Delete, "清空崩溃日志", "删除后不可恢复",
                            onClearCrashLogs, modifier,
                            available = crashLogCount > 0, destructive = true
                        )
                    }
                )
                SettingsSection("关于")
                SettingsLinkCard(
                    "关于与开源许可", "版本信息与 GPL-3.0 许可证", onOpenAbout
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "$appName $versionName · 本地处理，不联网",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = glassSectionLabelStyle(),
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier.fillMaxWidth(), radius = 24.dp,
        contentPadding = PaddingValues(12.dp), content = content)
}

@Composable
private fun SettingsPair(
    singleColumn: Boolean,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit
) {
    if (singleColumn) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        // 逐行测量最高卡片，避免两列各自排版使第二行错位。
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(1f).fillMaxHeight())
            second(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun SettingsGlyph(icon: ImageVector, tint: Color) {
    GlassIconTile(size = 34.dp, tint = tint) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsToolCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier,
    available: Boolean = true,
    destructive: Boolean = false
) {
    val accent = when {
        !available -> MaterialTheme.colorScheme.onSurfaceVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    // 忙时仍交由业务回调解释原因，不把操作卡变成无反馈的死区。
    SettingsCard(modifier.clip(continuousShape(24.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsGlyph(icon, accent)
            Spacer(Modifier.weight(1f))
            SettingsChevron(MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = settingsCardTitleStyle(),
            color = if (destructive && available) accent else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(description, style = settingsDescriptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsToggleCard(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
        MaterialTheme.colorScheme.primaryContainer else primary
    SettingsCard(modifier.clip(continuousShape(24.dp))
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsGlyph(icon, if (enabled) primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = null, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = track,
                    checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    uncheckedBorderColor = Color.Transparent,
                    disabledCheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    disabledCheckedTrackColor = track.copy(alpha = 0.5f),
                    disabledUncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    disabledUncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                )
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = settingsCardTitleStyle(),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(description, style = settingsDescriptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsLinkCard(title: String, description: String, onClick: () -> Unit) {
    SettingsCard(Modifier.clip(continuousShape(24.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = settingsCardTitleStyle(), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(description, style = settingsDescriptionStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            SettingsChevron(MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 墨迹右缘贴齐内容右线：系统箭头的字形内缩约 7dp，和开关轨道对不齐 */
@Composable
private fun SettingsChevron(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val right = size.width - stroke / 2
        val left = right - 5.dp.toPx()
        val centerY = size.height / 2
        val half = 5.dp.toPx()
        drawLine(color, Offset(left, centerY - half), Offset(right, centerY),
            stroke, StrokeCap.Round)
        drawLine(color, Offset(right, centerY), Offset(left, centerY + half),
            stroke, StrokeCap.Round)
    }
}

private fun settingsCardTitleStyle() = TextStyle(fontSize = 15.sp, lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold)

private fun settingsDescriptionStyle() = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)

/** 性能旋钮行：标题 + 当前值 + 滑杆（读屏读「标题，当前值」）。
 *  动效：程序性变更（点预设/开关）时旋钮弹簧滑移到新值，拖动中直接跟手不打架；
 *  整数档滑杆跨档时 CLOCK_TICK 触觉（音量条同款）。减少动效时全部退化为直变。 */
@Composable
private fun PerfSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    hapticStep: Boolean,
    onChange: (Float) -> Unit
) {
    val view = LocalView.current
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    // 预设应用时旋钮滑移（拖动中用原始值，动画不能和手指打架）
    val animated = animateFloatAsState(
        targetValue = value.coerceIn(range.start, range.endInclusive),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "perfKnob_$title"
    )
    val shown = if (dragged || reduceMotion) value else animated.value
    var lastStep by remember(title) { mutableIntStateOf(value.roundToInt()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = settingsCardTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            // 数值变化轻跳一下（上滑 6dp + 淡入），比原地换字有「生效了」的确认感
            AnimatedContent(
                targetState = valueLabel,
                transitionSpec = {
                    if (reduceMotion) fadeIn(snap()) togetherWith fadeOut(snap())
                    else (fadeIn(tween(160, easing = EaseOutStrong)) +
                        slideInVertically(tween(160, easing = EaseOutStrong)) { it / 2 }) togetherWith
                        fadeOut(tween(90))
                },
                label = "perfValue_$title"
            ) { label ->
                Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            onValueChange = { raw ->
                if (hapticStep) {
                    val step = raw.roundToInt()
                    if (step != lastStep) {
                        lastStep = step
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                onChange(raw)
            },
            valueRange = range,
            steps = steps,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "$title，$valueLabel" }
        )
    }
}
