package com.vliveconvert.app.ui

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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState

@Composable
fun SettingsScreen(
    appName: String,
    versionName: String,
    outputRelPath: String,
    moveToCamera: Boolean,
    deleteOriginal: Boolean,
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
                        "当前任务", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已添加 $queueCount 张照片",
                        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "输出 ${if (moveToCamera) "DCIM/Camera" else outputRelPath}",
                        style = settingsDescriptionStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (deleteOriginal) "删除原图已开启" else "保留原 JPG 与 MP4",
                        style = settingsDescriptionStyle(),
                        color = if (deleteOriginal) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
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
                SettingsLinkCard(Icons.Filled.Home, "输出目录", outputRelPath, onEditOutputPath)
                SettingsSection("转换后处理")
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToggleCard(
                            Icons.AutoMirrored.Filled.ArrowForward, "移到相机相册",
                            "保存到 DCIM/Camera", moveToCamera, !isBusy,
                            onToggleMoveToCamera, modifier, singleColumn
                        )
                    },
                    second = { modifier ->
                        SettingsToggleCard(
                            Icons.Filled.Delete, "删除原图", "转换成功后处理原 JPG 与 MP4",
                            deleteOriginal, !isBusy, onToggleDeleteOriginal, modifier, singleColumn
                        )
                    }
                )
                SettingsSection("外观")
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToggleCard(
                            Icons.Filled.Star, "跟随系统取色（莫奈）", "使用壁纸配色，关闭回到品牌蓝",
                            dynamicColor, true, onToggleDynamicColor, modifier, singleColumn
                        )
                    },
                    second = { modifier ->
                        SettingsToggleCard(
                            Icons.Filled.PlayArrow, "减少动效", "减少高光流动与弹窗动画",
                            reduceMotion, true, onToggleReduceMotion, modifier, singleColumn
                        )
                    }
                )
                SettingsSection("工具")
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.DateRange, "修复文件时间", "按拍摄时间校正修改时间",
                            onOpenFixTime, modifier, singleColumn
                        )
                    },
                    second = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Build, "修复破损实况", "合并破损的 JPG 与 MP4，保留原文件",
                            onOpenBrokenRepair, modifier, singleColumn
                        )
                    }
                )
                Spacer(Modifier.height(12.dp))
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Place, "重建位置显示", "让历史照片显示拍摄位置",
                            onRepairLocationDisplay, modifier, singleColumn, available = !isBusy
                        )
                    },
                    second = { modifier ->
                        SettingsToolCard(
                            Icons.AutoMirrored.Filled.ArrowForward, "把输出移到相机相册",
                            "将已有产物搬到 DCIM/Camera", onMoveOutputsToCamera,
                            modifier, singleColumn, available = !isBusy
                        )
                    }
                )
                SettingsSection("记录与诊断")
                SettingsLinkCard(
                    Icons.Filled.List, "转换记录（$batchLogCount）", "查看最近 50 批的结果与产物",
                    onOpenBatchLog
                )
                Spacer(Modifier.height(12.dp))
                SettingsPair(singleColumn,
                    first = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Share, "导出崩溃日志（$crashLogCount）",
                            "保存到 Download/VLiveConvert 供排查", onExportCrashLogs,
                            modifier, singleColumn
                        )
                    },
                    second = { modifier ->
                        SettingsToolCard(
                            Icons.Filled.Delete, "清空崩溃日志", "仅删除本地日志，不能撤销",
                            onClearCrashLogs, modifier, singleColumn,
                            available = crashLogCount > 0, destructive = true
                        )
                    }
                )
                SettingsSection("关于")
                SettingsLinkCard(
                    Icons.Filled.Info, "关于与开源许可", "版本信息与 GPL-3.0 许可证", onOpenAbout
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
    singleColumn: Boolean,
    available: Boolean = true,
    destructive: Boolean = false
) {
    val accent = when {
        !available -> MaterialTheme.colorScheme.onSurfaceVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    // 忙时仍交由业务回调解释原因，不把操作卡变成无反馈的死区。
    SettingsCard(modifier.clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsGlyph(icon, accent)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = settingsCardTitleStyle(), minLines = if (singleColumn) 1 else 2,
            color = if (destructive && available) accent else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(description, style = settingsDescriptionStyle(), minLines = if (singleColumn) 1 else 2,
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
    modifier: Modifier,
    singleColumn: Boolean
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
        MaterialTheme.colorScheme.primaryContainer else primary
    SettingsCard(modifier.clip(RoundedCornerShape(24.dp))
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
        Text(title, style = settingsCardTitleStyle(), minLines = if (singleColumn) 1 else 2,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(description, style = settingsDescriptionStyle(), minLines = if (singleColumn) 1 else 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsLinkCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    SettingsCard(Modifier.clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsGlyph(icon, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = settingsCardTitleStyle(), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(description, style = settingsDescriptionStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

private fun settingsCardTitleStyle() = TextStyle(fontSize = 15.sp, lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold)

private fun settingsDescriptionStyle() = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
