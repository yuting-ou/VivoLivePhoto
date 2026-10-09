package com.vliveconvert.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
    onMoveOutputsToCamera: () -> Unit,
    onExportCrashLogs: () -> Unit,
    onOpenAbout: () -> Unit
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
            SettingsCard {
                ClickableRow(
                    title = "输出目录",
                    subtitle = "相对主存储路径，导出文件的保存位置",
                    onClick = onEditOutputPath
                ) {
                    Text(
                        outputRelPath,
                        style = MaterialTheme.typography.bodyMedium,
                        // 卡片底色是 surfaceContainerLow，`primary` 在其上仅约 4.40:1（低于正文 AA 的 4.5）；
                        // 值属次要信息，改用与副标题一致的 onSurfaceVariant（≈8:1），
                        // 可交互性由整行可点 + 右侧 › 表达
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(140.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    ChevronRight()
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 转换后处理 ──
            SectionLabel("转换后处理")
            SettingsCard {
                SwitchRow(
                    title = "移到相机相册",
                    subtitle = "写入 DCIM/Camera；配合「删除原图」先删后移，不产生 (1) 序号",
                    checked = moveToCamera,
                    enabled = !isBusy,
                    onToggle = onToggleMoveToCamera
                )
                CardDivider()
                SwitchRow(
                    title = "删除原图",
                    subtitle = "删除原 .jpg 与伴生 .mp4（可在提示的入口中恢复）",
                    checked = deleteOriginal,
                    enabled = !isBusy,
                    onToggle = onToggleDeleteOriginal
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 工具 ──
            SectionLabel("工具")
            SettingsCard {
                ActionRow("修复文件时间", onOpenFixTime)
                CardDivider()
                ActionRow("把输出移到相机相册", onMoveOutputsToCamera, enabled = !isBusy)
                if (crashLogCount > 0) {
                    CardDivider()
                    ActionRow(
                        "导出崩溃日志（$crashLogCount）",
                        onExportCrashLogs,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 关于 ──（GPL-3.0 合规：许可证文本随包内置，可在应用内查看）
            SectionLabel("关于")
            SettingsCard {
                ActionRow("关于与开源许可", onOpenAbout)
            }

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

/** 分组卡片容器（子项之间用 CardDivider 分隔） */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) { content() }
}

@Composable
private fun CardDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun ClickableRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ActionRow(
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = tint ?: if (enabled) MaterialTheme.colorScheme.onSurface
                          else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        ChevronRight()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
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
