package com.vliveconvert.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vliveconvert.app.R
import com.vliveconvert.app.ui.theme.statusColors

/**
 * 主界面：待转换列表 + 开始转换。
 *
 * 信息架构（v1.3.0 重排）：主界面只保留「选照片 → 转换」主路径。
 * 低频项（输出目录 / 两个开关 / 修复时间 / 移到相机 / 日志）全部收进 [SettingsScreen]，
 * 顶栏只剩标题与设置入口，底部只剩设置摘要 + 两个主按钮——照片列表因此获得绝大部分竖向空间。
 */
@Composable
fun MainScreen(
    items: List<ConvertItem>,
    /** 应用内品牌名（统一取自 R.string.app_display_name，避免多处硬编码） */
    appName: String,
    statusText: String,
    isConverting: Boolean,
    progress: Float,
    progressDetail: String,
    pendingRestoreCount: Int,
    onRestoreOriginals: () -> Unit,
    /** 位置权限缺失：转换会因系统读取层脱敏而丢失 GPS 地点信息 */
    locationMissing: Boolean,
    onGrantLocation: () -> Unit,
    /** 已完成但丢了位置的可重转条数：>0 时显示「重新转换找回位置」入口 */
    reconvertCount: Int,
    onReconvertLostGps: () -> Unit,
    /** 单个条目行内「重转」 */
    onReconvertItem: (ConvertItem) -> Unit,
    outputRelPath: String,
    moveToCamera: Boolean,
    deleteOriginal: Boolean,
    onOpenSettings: () -> Unit,
    onShowStatusDetail: () -> Unit,
    onCancelConvert: () -> Unit,
    onAddMore: () -> Unit,
    onStartConvert: () -> Unit,
    onClearAll: () -> Unit,
    onRemove: (ConvertItem) -> Unit
) {
    val pendingCount = items.count { !it.done && !it.failed }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── 顶栏：标题 + 设置 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 设置入口：与其它页面的返回按钮同为「无底色的图标按钮」，
            // 原先套一个 primaryContainer 实心圆，看起来更像头像而不是设置
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // ── 提示卡片：把多类提示收进同一张卡片，用分隔线分组 ──
        // 语义色只落在「圆点 + 动作文字」上、底色统一为中性容器色：
        // 这样三条同现时是「一组提示」，而不是三条不同颜色的色带横贯屏幕
        val notices = buildList {
            if (locationMissing) add(
                Notice("未授予「位置」权限，转换会丢失 GPS 地点信息",
                    "去授权", MaterialTheme.colorScheme.error, onGrantLocation))
            if (reconvertCount > 0 && !isConverting) add(
                Notice("$reconvertCount 张照片没有位置信息，授权后可一键找回",
                    "重新转换", MaterialTheme.colorScheme.tertiary, onReconvertLostGps))
            if (pendingRestoreCount > 0) add(
                Notice("已删除的原图可恢复（$pendingRestoreCount 项，30 天内）",
                    "恢复", MaterialTheme.colorScheme.primary, onRestoreOriginals))
        }
        if (notices.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                notices.forEachIndexed { index, notice ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 34.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(notice.accent)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            notice.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                        )
                        InlineTextButton(
                            text = notice.action,
                            contentColor = notice.accent,
                            onClick = notice.onClick
                        )
                    }
                }
            }
        }

        // ── 状态摘要（长文案折叠，点击看详情） ──
        if (statusText.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // 整行可点：最小尺寸与 clickable 同链，保证命中区高度 ≥48dp
                    .minimumInteractiveComponentSize()
                    .clickableNoRipple(onShowStatusDetail)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "详情 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ── 列表头 ──
        if (items.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isConverting) "转换中 · 共 ${items.size} 张"
                    else "已选 ${items.size} 张",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.weight(1f))
                if (!isConverting) {
                    InlineTextButton(
                        text = "清空",
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onClearAll,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // ── 列表 ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyState(onAdd = onAddMore)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.item.key }) { ci ->
                        ConvertItemRow(
                            ci = ci,
                            onRemove = { onRemove(ci) },
                            onReconvert = { onReconvertItem(ci) }
                        )
                    }
                }
            }
        }

        // ── 底部操作栏：设置摘要 + 主路径按钮 ──
        Surface(tonalElevation = 3.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (isConverting) {
                    // 整体进度
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            progressDetail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onCancelConvert,
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) { Text("取消转换") }
                } else {
                    // 设置摘要：一眼看清关键选项，点击进设置
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            // 整行可点：最小尺寸与 clickable 同链，保证命中区高度 ≥48dp
                            .minimumInteractiveComponentSize()
                            .clickableNoRipple(onOpenSettings)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "输出 ${if (moveToCamera) "DCIM/Camera" else outputRelPath}" +
                                " · 删原图 ${if (deleteOriginal) "开" else "关"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "设置 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onAddMore,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("添加")
                        }
                        Button(
                            onClick = onStartConvert,
                            enabled = pendingCount > 0,
                            modifier = Modifier.weight(1.4f).height(48.dp)
                        ) {
                            Text(
                                if (pendingCount > 0) "开始转换（$pendingCount）"
                                else "开始转换"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 中央大 + 号：用户的第一反应就是点它，必须真的能点（点按有涟漪反馈）
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "添加照片",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "还没有选择照片",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "点击上方「＋」选择 vivo 相机实况模式\n拍摄的 .jpg + .mp4 成对文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 列表项：缩略图右下角状态角标 + 状态文案前置图标 + 行底色。
 * 状态不再只靠文字颜色区分（图标/徽标/底色三重编码）。
 */
@Composable
private fun ConvertItemRow(
    ci: ConvertItem,
    onRemove: () -> Unit,
    onReconvert: () -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    val busy = isBusyStatus(ci.status)
    val lostGps = ci.done && ci.lostGps && !ci.failed
    val status = statusColors
    val bg = when {
        ci.failed -> MaterialTheme.colorScheme.errorContainer
        lostGps -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val main = MaterialTheme.colorScheme.onSurface
    val statusColor = when {
        ci.failed -> MaterialTheme.colorScheme.onErrorContainer
        lostGps -> MaterialTheme.colorScheme.onTertiaryContainer
        ci.done -> status.success
        busy -> status.info
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩略图 + 右下角状态角标
            Box(Modifier.size(48.dp)) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    MediaThumbnail(ci.item.uri, resolver, Modifier.fillMaxSize())
                }
                // 状态角标：填充色与符号色成对取自主题（深色下不再用浅色底配白字，
                // 那会让符号对比度跌到 1.7:1 几乎不可读）
                val badge: Triple<String, Color, Color>? = when {
                    ci.failed -> Triple(
                        "✕",
                        MaterialTheme.colorScheme.error,
                        MaterialTheme.colorScheme.onError
                    )
                    lostGps -> Triple("!", status.warning, status.onWarning)
                    ci.done -> Triple("✓", status.success, status.onSuccess)
                    else -> null
                }
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(badge.second),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            badge.first,
                            style = MaterialTheme.typography.labelSmall,
                            color = badge.third,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    ci.item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = main,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    ci.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = if (busy) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                // 转换中的单项进度（不定长：字节级转换无粒度回调，不伪造百分比）
                if (busy) {
                    Spacer(Modifier.height(5.dp))
                    IndeterminateBar()
                }
            }

            // 行内重转（丢位置）/ 移除
            if (lostGps) {
                InlineTextButton(
                    text = "重转",
                    // 背景是实色 tertiary，前景必须用配套的 onTertiary；
                    // 原先误用 onTertiaryContainer，深色下仅 1.29:1，文字几乎不可见
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    background = MaterialTheme.colorScheme.tertiary,
                    onClick = onReconvert,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.width(2.dp))
            }
            if (!busy) {
                // 不限定 size：IconButton 默认 48dp，满足最小触摸目标（原先压到 34dp 过小）
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

/** 不定长进度条（字节级转换无粒度回调，用系统不定长动画，不伪造百分比） */
@Composable
private fun IndeterminateBar() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
    )
}

/** 转换中的项目不显示移除按钮 */
private fun isBusyStatus(status: String): Boolean =
    status.startsWith("转换中") || status.startsWith("重新转换中")

/**
 * 主界面顶部的一条提示。
 * 卡片底色统一为中性容器色，语义只落在 [accent]（圆点与动作文字）上——
 * 多条同时出现时才是一组提示，而不是一片彩色色带。
 */
private class Notice(
    val text: String,
    val action: String,
    val accent: Color,
    val onClick: () -> Unit
)
