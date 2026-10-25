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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

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
    dynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    statusText: String = "",
    hazeState: HazeState = rememberGlassHazeState()
) {
    Column(
        Modifier
            .fillMaxSize()
            .glassWallpaper()
            .glassBackdropSource(hazeState)
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                size = 38.dp
            )
            Spacer(Modifier.width(10.dp))
            Text("设置", style = glassTitleStyle())
        }

        // 设置页的动作反馈就地显示——v1.7.20 前反馈只写主页状态栏，
        // 用户在设置页点「导出崩溃日志」什么也看不见（=「点了没反应」）
        if (statusText.isNotEmpty()) {
            Text(
                statusText,
                style = glassRowSubStyle(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                // 边到边显示下，滚动内容底部会与系统导航栏重叠，最后一行无法滚出遮挡区
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(12.dp))

            // ── 输出 ──
            SectionLabel("输出")
            GlassCard(radius = 24.dp) {
                ToolValueCard(
                    icon = Icons.Filled.Home,
                    title = "输出目录",
                    subtitle = "保存位置",
                    value = outputRelPath,
                    onClick = onEditOutputPath
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── 转换后处理 ──
            SectionLabel("转换后处理")
            GlassCard(radius = 24.dp) {
                ToolSwitchCard(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    iconOffset = androidx.compose.ui.unit.DpOffset(-0.9.dp, 0.dp),
                    title = "移到相机相册",
                    subtitle = "写入相机相册，删原图时可免序号",
                    checked = moveToCamera,
                    enabled = !isBusy,
                    onToggle = onToggleMoveToCamera
                )
                GlassDivider(startInset = 58.dp)
                ToolSwitchCard(
                    icon = Icons.Filled.Delete,
                    title = "删除原图",
                    subtitle = "删除原 .jpg 与伴生 .mp4（可恢复）",
                    checked = deleteOriginal,
                    enabled = !isBusy,
                    onToggle = onToggleDeleteOriginal
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── 外观 ──
            SectionLabel("外观")
            GlassCard(radius = 24.dp) {
                ToolSwitchCard(
                    icon = Icons.Filled.Star,
                    iconOffset = androidx.compose.ui.unit.DpOffset(0.dp, 0.5.dp),
                    title = "跟随系统取色（莫奈）",
                    subtitle = "用壁纸配色替换固定品牌蓝；关闭则回到品牌蓝",
                    checked = dynamicColor,
                    enabled = true,
                    onToggle = onToggleDynamicColor
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── 工具 ──
            SectionLabel("工具")
            GlassCard(radius = 24.dp) {
                ToolCard(
                    icon = Icons.Filled.DateRange,
                    title = "修复文件时间",
                    subtitle = "按拍摄时间校正已导出文件的「修改时间」",
                    onClick = onOpenFixTime
                )
                GlassDivider(startInset = 58.dp)
                ToolCard(
                    icon = Icons.Filled.Place,
                    iconOffset = androidx.compose.ui.unit.DpOffset(-0.9.dp, 0.dp),
                    title = "重建位置显示",
                    subtitle = "让历史转换的照片在相册里显示拍摄位置",
                    onClick = onRepairLocationDisplay,
                    enabled = !isBusy
                )
                GlassDivider(startInset = 58.dp)
                ToolCard(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    iconOffset = androidx.compose.ui.unit.DpOffset(-0.9.dp, 0.dp),
                    title = "把输出移到相机相册",
                    subtitle = "把其它目录里的转换产物搬进 DCIM/Camera",
                    onClick = onMoveOutputsToCamera,
                    enabled = !isBusy
                )
                GlassDivider(startInset = 58.dp)
                ToolCard(
                    icon = Icons.Filled.Share,
                    title = "导出崩溃日志（$crashLogCount）",
                    subtitle = "导出到 Download/VLiveConvert 供排查",
                    onClick = onExportCrashLogs,
                    tint = MaterialTheme.colorScheme.error
                )
                GlassDivider(startInset = 58.dp)
                ToolCard(
                    icon = Icons.Filled.Delete,
                    title = "清空崩溃日志",
                    subtitle = "删除全部本地崩溃日志（不可恢复）",
                    onClick = onClearCrashLogs,
                    enabled = crashLogCount > 0
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── 关于 ──
            SectionLabel("关于")
            GlassCard(radius = 24.dp) {
                ToolCard(
                    icon = Icons.Filled.Info,
                    title = "关于与开源许可",
                    subtitle = "版本信息与 GPL-3.0 许可证全文",
                    onClick = onOpenAbout
                )
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
        style = glassSectionLabelStyle(),
        modifier = Modifier.padding(start = 0.dp, bottom = 7.dp)
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
    /** 图标光学微调：Material 图标按 24dp 网格几何居中，部分字形墨迹质心偏离
     *  （实测 ArrowForward/Place +0.9dp 右、DateRange +0.9dp 上、Star +0.5dp 上），
     *  按质心补偿而非肉眼随意挪 */
    iconOffset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction, pressedScale = 0.985f)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = onClick
                    )
                } else Modifier
            )
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 玻璃磁贴 + 彩色字形：方圆角与卡片圆角同语言，不再是圆贴纸
        GlassIconTile(size = 34.dp, tint = iconTint) {
            Icon(
                icon, contentDescription = null, tint = iconTint,
                modifier = Modifier
                    .offset(x = iconOffset.x, y = iconOffset.y)
                    .size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = glassRowTitleStyle(), color = titleColor)
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                style = glassRowSubStyle(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
    tint: androidx.compose.ui.graphics.Color? = null,
    iconOffset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero
) {
    val iconTint = tint ?: if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
    val titleColor = tint ?: if (enabled) MaterialTheme.colorScheme.onSurface
                          else MaterialTheme.colorScheme.onSurfaceVariant
    ToolCardShell(
        icon = icon, title = title, subtitle = subtitle,
        iconTint = iconTint, titleColor = titleColor,
        iconOffset = iconOffset, onClick = onClick
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
    onToggle: (Boolean) -> Unit,
    iconOffset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset.Zero
) {
    val iconTint = if (enabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
    ToolCardShell(
        icon = icon, title = title, subtitle = subtitle,
        iconTint = iconTint,
        titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
                     else MaterialTheme.colorScheme.onSurfaceVariant,
        iconOffset = iconOffset,
        onClick = if (enabled) ({ onToggle(!checked) }) else null
    ) {
        // iOS 式开关：白色滑块 + 饱和轨道。
        // 深色主题的 primary 是浅薰衣草色，白滑块会糊在轨道上，故轨道改用 primaryContainer
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val track = if (dark) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.primary
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = track,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.7f),
                disabledCheckedTrackColor = track.copy(alpha = 0.5f),
                disabledUncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                disabledUncheckedTrackColor =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
        )
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
        //
        // 值列宽度随字号收缩：标题「输出目录」是 4 个汉字，2× 字号需要约 112dp，
        // 而 360dp 窄屏卡片内只有约 218dp 可分配——固定 150dp 会把标题挤成竖排。
        // 正常字号仍是 150dp，路径显示与旧版一致。
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val valueWidth = (218f - 60f * fontScale).coerceIn(96f, 150f).dp
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(valueWidth)
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
        // x=+4dp：chevron 字形自带右侧留白，实测墨迹右缘比开关轨道左 6.7dp
        //（1122px vs 1142px @3x），右移后两类行的视觉右缘落在同一竖线
        modifier = Modifier.offset(x = 4.dp).size(20.dp)
    )
}
