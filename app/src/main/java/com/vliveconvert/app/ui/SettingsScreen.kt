package com.vliveconvert.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 设置页：收纳主界面原有的全部低频项（输出目录、两个开关、修复时间、移到相机、崩溃日志）。
 * 主界面因此只保留「选照片 → 转换」主路径。
 */
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
    onRepairLocationDisplay: () -> Unit,
    onMoveOutputsToCamera: () -> Unit,
    onExportCrashLogs: () -> Unit,
    onClearCrashLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    statusText: String = ""
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 设置页的动作反馈就地显示——v1.7.20 前反馈只写主页状态栏，
        // 用户在设置页点「导出崩溃日志」什么也看不见（=「点了没反应」）
        if (statusText.isNotEmpty()) {
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                // 边到边显示下，滚动内容底部会与系统导航栏重叠，最后一行无法滚出遮挡区
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(12.dp))

            // ── 输出 ──
            SectionLabel("输出")
            ToolValueCard(
                icon = Icons.Filled.Home,
                title = "输出目录",
                subtitle = "导出文件的保存位置",
                value = outputRelPath,
                onClick = onEditOutputPath
            )

            Spacer(Modifier.height(16.dp))

            // ── 转换后处理 ──
            SectionLabel("转换后处理")
            ToolSwitchCard(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                title = "移到相机相册",
                subtitle = "写入 DCIM/Camera（配合删原图可免序号）",
                checked = moveToCamera,
                enabled = !isBusy,
                onToggle = onToggleMoveToCamera
            )
            Spacer(Modifier.height(8.dp))
            ToolSwitchCard(
                icon = Icons.Filled.Delete,
                title = "删除原图",
                subtitle = "删除原 .jpg 与伴生 .mp4（可恢复）",
                checked = deleteOriginal,
                enabled = !isBusy,
                onToggle = onToggleDeleteOriginal
            )

            Spacer(Modifier.height(16.dp))

            // ── 工具 ──（v2.0.5 卡片化：图标 + 一句说明，让每个工具做什么一眼可见）
            SectionLabel("工具")
            ToolCard(
                icon = Icons.Filled.DateRange,
                title = "修复文件时间",
                subtitle = "按拍摄时间校正已导出文件的「修改时间」",
                onClick = onOpenFixTime
            )
            Spacer(Modifier.height(8.dp))
            ToolCard(
                icon = Icons.Filled.Place,
                title = "重建位置显示",
                subtitle = "让历史转换的照片在相册里显示拍摄位置",
                onClick = onRepairLocationDisplay,
                enabled = !isBusy
            )
            Spacer(Modifier.height(8.dp))
            ToolCard(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                title = "把输出移到相机相册",
                subtitle = "把其它目录里的转换产物搬进 DCIM/Camera",
                onClick = onMoveOutputsToCamera,
                enabled = !isBusy
            )
            Spacer(Modifier.height(8.dp))
            ToolCard(
                icon = Icons.Filled.Share,
                title = "导出崩溃日志（$crashLogCount）",
                subtitle = "导出到 Download/VLiveConvert 供排查",
                onClick = onExportCrashLogs,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            ToolCard(
                icon = Icons.Filled.Delete,
                title = "清空崩溃日志",
                subtitle = "删除全部本地崩溃日志（不可恢复）",
                onClick = onClearCrashLogs,
                enabled = crashLogCount > 0
            )

            Spacer(Modifier.height(16.dp))

            // ── 关于 ──（GPL-3.0 合规：许可证文本随包内置，可在应用内查看）
            SectionLabel("关于")
            ToolCard(
                icon = Icons.Filled.Info,
                title = "关于与开源许可",
                subtitle = "版本信息与 GPL-3.0 许可证全文",
                onClick = onOpenAbout
            )

            Spacer(Modifier.height(24.dp))

            Text(
                "$appName $versionName · 本地处理，不联网",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

/** 卡片外壳：图标块 + 标题/说明 + 右侧内容（chevron / switch / 当前值） */
@Composable
private fun ToolCardShell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** 工具卡片：点按执行（chevron 指引） */
@Composable
private fun ToolCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    val iconTint = tint ?: if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor = tint ?: if (enabled) MaterialTheme.colorScheme.onSurface
                          else MaterialTheme.colorScheme.onSurfaceVariant
    ToolCardShell(
        icon = icon, title = title, subtitle = subtitle,
        iconTint = iconTint, titleColor = titleColor, onClick = onClick
    ) { ChevronRight() }
}

/** 开关卡片：点按或拨动开关切换 */
@Composable
private fun ToolSwitchCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val iconTint = if (enabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
    ToolCardShell(
        icon = icon, title = title, subtitle = subtitle,
        iconTint = iconTint,
        titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
                     else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = if (enabled) ({ onToggle(!checked) }) else null
    ) {
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}

/** 值卡片：右侧展示当前值 + chevron（如输出目录） */
@Composable
private fun ToolValueCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit
) {
    ToolCardShell(
        icon = icon, title = title, subtitle = subtitle, onClick = onClick
    ) {
        // onSurface 而非 onSurfaceVariant：后者与副标题同色会「融」进说明；
        // 也不能用 primary——在卡片底色上仅 4.40:1，低于正文 AA。onSurface ≈14:1
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(150.dp)
        )
        Spacer(Modifier.width(2.dp))
        ChevronRight()
    }
}

@Composable
private fun ChevronRight() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}
