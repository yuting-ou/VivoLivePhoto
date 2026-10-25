package com.vliveconvert.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.R
import com.vliveconvert.app.ui.theme.statusColors
import dev.chrisbanes.haze.HazeState

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
    /** 批次刚结束的「完成节拍」：底栏在此状态下再多停一会儿（见 ConvertCenter.finishedBeat） */
    finishedBeat: Boolean,
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
    onRemove: (ConvertItem) -> Unit,
    hazeState: HazeState = rememberGlassHazeState()
) {
    val pendingCount = items.count { !it.done && !it.failed }
    // 列表滚动到 Dock 下方时，最后一行仍能滚出来；短列表不垫，避免玻璃面板拖出一截空白
    val dockInset = if (isConverting || finishedBeat) 176.dp else 132.dp
    val listBottomInset = if (items.size >= 5) dockInset else 0.dp

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .glassWallpaper()
                .glassBackdropSource(hazeState)
        ) {
        // ── 顶栏：大标题 + 添加入口 + 设置 ──
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(appName, style = glassTitleStyle())
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.app_tagline), style = glassSubtitleStyle())
            }
            // 列表非空且不在转换中时才有「再添加」；空状态由中央 CTA 承担
            if (items.isNotEmpty() && !isConverting) {
                GlassIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "添加照片",
                    onClick = onAddMore
                )
                Spacer(Modifier.width(10.dp))
            }
            GlassIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "设置",
                onClick = onOpenSettings
            )
        }

        // ── 提示卡片：把多类提示收进同一张卡片，用分隔线分组 ──
        // 语义色只落在「圆点 + 动作文字」上、底色统一为中性容器色：
        // 这样三条同现时是「一组提示」，而不是三条不同颜色的色带横贯屏幕
        val notices = buildList {
            if (locationMissing) add(
                Notice("未授予「位置」权限，转换会丢失 GPS 地点信息",
                    "去授权", statusColors.warning, Icons.Filled.LocationOn, onGrantLocation))
            if (reconvertCount > 0 && !isConverting) add(
                Notice("$reconvertCount 张照片没有位置信息，授权后可一键找回",
                    "重新转换", MaterialTheme.colorScheme.tertiary,
                    Icons.Filled.Refresh, onReconvertLostGps))
            if (pendingRestoreCount > 0) add(
                Notice("已删除的原图可恢复（$pendingRestoreCount 项，30 天内）",
                    "恢复", MaterialTheme.colorScheme.primary,
                    Icons.Filled.Delete, onRestoreOriginals))
        }
        if (notices.isNotEmpty()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                radius = 24.dp
            ) {
                notices.forEachIndexed { index, notice ->
                    if (index > 0) GlassDivider(startInset = 57.dp)
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassIconTile(30.dp, notice.accent) {
                            Icon(notice.icon, null, tint = notice.accent,
                                modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Text(
                            notice.text,
                            style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        InlineTextButton(
                            text = notice.action,
                            contentColor = MaterialTheme.colorScheme.primary,
                            onClick = notice.onClick,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }
        }

        // ── 状态摘要（长文案折叠，点击看详情） ──
        // 文案更新（如一次转换结束的「转换完成：…」）时柔和淡入上移，而非硬切
        AnimatedContent(
            targetState = statusText,
            transitionSpec = {
                (fadeIn(tween(200, easing = EaseOutStrong)) +
                    slideInVertically(tween(200, easing = EaseOutStrong)) { it / 3 })
                    .togetherWith(fadeOut(tween(130)))
            },
            label = "statusSummary"
        ) { text ->
            if (text.isNotEmpty()) {
                val statusInteraction = remember { MutableInteractionSource() }
                val statusPressed by statusInteraction.collectIsPressedAsState()
                // 整行无涟漪：按下让文字整行轻微变淡，松手即复原（90ms 内完成）
                val statusAlpha by animateFloatAsState(
                    targetValue = if (statusPressed) 0.55f else 1f,
                    animationSpec = tween(90),
                    label = "statusRowPress"
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = statusAlpha }
                        // 整行可点：最小尺寸与 clickable 同链，保证命中区高度 ≥48dp
                        .minimumInteractiveComponentSize()
                        .clickableNoRipple(onShowStatusDetail, statusInteraction)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text,
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
        }

        // ── 列表头 ──
        if (items.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // end 6dp：「清空」在 48dp 触摸区内居中，自身留白约 10dp，6+10=16dp
                    // 落到全页右基线（与左缘 16dp 对称）
                    .padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isConverting) "转换中 · 共 ${items.size} 张"
                    else "已选 ${items.size} 张",
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                )
                Spacer(Modifier.weight(1f))
                if (!isConverting) {
                    InlineTextButton(
                        text = "清空",
                        contentColor = MaterialTheme.colorScheme.primary,
                        onClick = onClearAll,
                        style = TextStyle(fontSize = 14.sp)
                    )
                }
            }
        }

        // ── 列表 ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // v1.8.5：空状态 ↔ 列表不再瞬间跳切（加第一张照片/清空时肉眼可见地切换）
            AnimatedContent(
                targetState = items.isEmpty(),
                transitionSpec = {
                    (fadeIn(tween(240, easing = EaseOutStrong)) + scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(240, easing = EaseOutStrong))) togetherWith
                        (fadeOut(tween(160)) + scaleOut(
                            targetScale = 0.96f, animationSpec = tween(160)))
                },
                label = "emptyListSwitch"
            ) { empty ->
                if (empty) {
                    EmptyState(onAdd = onAddMore)
                } else {
                    // 面板只在内容高度内铺玻璃：两条记录时若撑满剩余高度，
                    // 大半个屏幕会是一块空白白板（转换中尤其刺眼）
                    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        val listMaxHeight = maxHeight
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .glassSurface(radius = 26.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight),
                                contentPadding = PaddingValues(
                                    top = 4.dp, bottom = 4.dp + listBottomInset),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                items(items, key = { it.item.key }) { ci ->
                                    ConvertItemRow(
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(220),
                                            placementSpec = spring(
                                                dampingRatio = 0.9f, stiffness = 380f),
                                            fadeOutSpec = tween(180)
                                        ),
                                        ci = ci,
                                        onRemove = { onRemove(ci) },
                                        onReconvert = { onReconvertItem(ci) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        // ↑ 采样源到此结束：Dock 不能包含在源里，否则玻璃会采到自己

        // ── 底部悬浮玻璃 Dock：设置摘要 + 主路径按钮 ──
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 主按钮的彩色辉光透出玻璃（iOS 26 浮动控件的「发光」感）
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 18.dp)
                    .fillMaxWidth(0.8f)
                    .height(90.dp)
                    .background(Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                            Color.Transparent)))
            )
            HazeGlassCard(
                hazeState = hazeState,
                modifier = Modifier.fillMaxWidth(),
                radius = 30.dp,
                contentPadding = PaddingValues(10.dp)
            ) {
                // 三态：2 转换中 / 1 完成节拍 / 0 空闲；尺寸由 SizeTransform 平滑过渡
                val shownProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = spring(stiffness = 360f, dampingRatio = 1f),
                    label = "shownProgress"
                )
                AnimatedContent(
                    targetState = if (isConverting) 2 else if (finishedBeat) 1 else 0,
                    transitionSpec = {
                        (fadeIn(tween(200, easing = EaseOutStrong)) togetherWith fadeOut(tween(150))) using
                            SizeTransform(clip = false)
                    },
                    label = "bottomBar"
                ) { barState ->
                    if (barState >= 1) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    progressDetail,
                                    style = glassRowSubStyle(),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${(shownProgress * 100).toInt()}%",
                                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary)
                                )
                            }
                            LinearProgressIndicator(
                                progress = { shownProgress },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.height(10.dp))
                            if (barState == 2) {
                                GlassOutlineButton("取消转换", onCancelConvert)
                            } else {
                                // 完成节拍：留一块与取消键等高的空位，免得 2→1 高度再跳
                                Spacer(Modifier.fillMaxWidth().height(46.dp))
                            }
                        }
                    } else {
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .minimumInteractiveComponentSize()
                                    .clickableNoRipple(onOpenSettings),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "输出 ${if (moveToCamera) "DCIM/Camera" else outputRelPath}",
                                    style = TextStyle(fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.size(5.dp).clip(CircleShape).background(
                                        if (deleteOriginal) MaterialTheme.colorScheme.error
                                        else statusColors.success)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "删原图 ${if (deleteOriginal) "开" else "关"}",
                                    style = TextStyle(fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    maxLines = 1
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "设置 ›",
                                    style = TextStyle(fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary)
                                )
                            }
                            if (items.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                GlossyButton(
                                    text = if (pendingCount > 0) "开始转换（$pendingCount）"
                                    else "开始转换",
                                    onClick = onStartConvert
                                )
                            }
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
        Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 与启动图标同源的「双卡叠放」；整块可点，语义仍是「添加照片」
        val addInteraction = remember { MutableInteractionSource() }
        val addScale by rememberPressScale(addInteraction, pressedScale = 0.95f)
        Box(
            Modifier
                .size(150.dp)
                .graphicsLayer {
                    scaleX = addScale
                    scaleY = addScale
                }
                .semantics(mergeDescendants = true) { contentDescription = "添加照片" }
                .clickable(
                    interactionSource = addInteraction,
                    indication = LocalIndication.current,
                    onClick = onAdd
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 94.dp, height = 116.dp)
                    .offset(x = (-16).dp, y = 4.dp)
                    .graphicsLayer { rotationZ = -10f }
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                    .border(1.dp, Color.White.copy(alpha = 0.50f), RoundedCornerShape(18.dp))
            )
            val primary = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .size(width = 94.dp, height = 116.dp)
                    .offset(x = 6.dp, y = (-2).dp)
                    .graphicsLayer { rotationZ = -2f }
                    .shadow(14.dp, RoundedCornerShape(18.dp), clip = false,
                        ambientColor = primary.copy(alpha = 0.22f),
                        spotColor = primary.copy(alpha = 0.16f))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f))))
                    .background(Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = 0.32f), Color.Transparent), endY = 170f))
                    .border(1.dp, Color.White.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            )
            // 播放钮用主题色玻璃（不是 iOS 16 那种实色方块）：
            // 高光渐变 + 白色折射描边，缩略图中央才有「能点」的视觉锚点
            Box(
                Modifier
                    .offset(x = 6.dp, y = (-2).dp)
                    .size(46.dp)
                    .shadow(12.dp, CircleShape, clip = false,
                        ambientColor = primary.copy(alpha = 0.42f),
                        spotColor = primary.copy(alpha = 0.34f))
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(
                        lerp(primary, Color.White, 0.18f), primary)))
                    .border(1.dp, Color.White.copy(alpha = 0.60f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.24f), Color.Transparent), endY = 80f)))
                Icon(Icons.Filled.PlayArrow, null,
                    tint = if (primary.luminance() > 0.42f)
                        MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier.size(25.dp))
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            "还没有选择照片",
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "选择 vivo 相机实况模式拍摄的\n.jpg + .mp4 成对文件",
            style = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, lineHeight = 21.sp)
        )
        Spacer(Modifier.height(24.dp))
        Box(Modifier.width(260.dp)) {
            GlossyButton("选择实况照片", onAdd)
        }
    }
}

/**
 * 列表项：缩略图右下角状态角标 + 状态文案前置图标 + 行底色。
 * 状态不再只靠文字颜色区分（图标/徽标/底色三重编码）。
 */
@Composable
private fun ConvertItemRow(
    modifier: Modifier = Modifier,
    ci: ConvertItem,
    onRemove: () -> Unit,
    onReconvert: () -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    val busy = isBusyStatus(ci.status)
    val lostGps = ci.done && ci.lostGps && !ci.failed
    val status = statusColors
    // 行底必须不透明（挡住左滑红底），但深色主题用纯 surface 会黑成一块板；
    // 换成低容器色，和玻璃面板的暗部接得上
    val rowBase = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
        MaterialTheme.colorScheme.surfaceContainerLow
    else MaterialTheme.colorScheme.surface
    // 玻璃卡内的行底色只做「淡淡的一层」：失败/丢位置用语义色 10% 透明，
    // 其余透明，让玻璃材质本身承担层级
    val bgTarget = when {
        ci.failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
        lostGps -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val bg by animateColorAsState(bgTarget, tween(300), label = "rowBg")
    val main = MaterialTheme.colorScheme.onSurface
    val statusColor = when {
        ci.failed -> MaterialTheme.colorScheme.error
        lostGps -> MaterialTheme.colorScheme.tertiary
        ci.done -> status.success
        busy -> status.info
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 失败抖动 + 同帧触觉（逻辑与 v2.0.9 一致）
    val hapticView = LocalView.current
    var prevFailed by remember(ci.item.key) { mutableStateOf(ci.failed) }
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(ci.failed) {
        if (ci.failed && !prevFailed) {
            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
            for (d in listOf(-10f, 10f, -7f, 7f, -3f, 0f)) {
                shakeX.snapTo(d)
                delay(45)
            }
        }
        prevFailed = ci.failed
    }
    // 左滑移除：阈值 60% + 撤销条（逻辑不变）
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !busy) {
                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onRemove()
                true
            } else {
                false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.6f }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !busy,
        backgroundContent = {
            // 只有真正开始滑动才画红底：行内容本身是半透明的玻璃行，
            // 静止时若红底常驻会透出来，整列表看起来像全部失败
            if (dismissState.progress > 0.001f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .graphicsLayer { alpha = dismissState.progress },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        "移除",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(end = 20.dp)
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 行内容必须有一层接近不透明的底：否则 SwipeToDismiss 的红色背景
                // 会透过整块玻璃行显出来，静止状态看起来所有行都「失败」了
                .background(rowBase)
                .background(bg)
                .graphicsLayer { translationX = shakeX.value }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(46.dp)) {
                    // 转换中：缩略图外圈一道不确定弧
                    if (busy) {
                        val ringAngle by rememberInfiniteTransition(label = "ring").animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                tween(900, easing = LinearEasing)), label = "ringAngle"
                        )
                        Canvas(Modifier.size(54.dp).align(Alignment.Center)) {
                            drawArc(
                                color = status.info,
                                startAngle = -90f + ringAngle, sweepAngle = 110f, useCenter = false,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                    ) {
                        // 完成瞬间「显影」：低饱和 → 全彩
                        val develop by animateFloatAsState(
                            targetValue = if (ci.done) 1f else 0.25f,
                            animationSpec = tween(600, delayMillis = 80,
                                easing = LinearOutSlowInEasing),
                            label = "develop"
                        )
                        MediaThumbnail(
                            ci.item.uri, resolver,
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = 0.35f + 0.65f * develop }
                                .then(
                                    if (develop < 1f) Modifier.blur((8 * (1 - develop)).dp)
                                    else Modifier
                                )
                        )
                    }
                    // 状态角标：玻璃圆钮 + 符号；弹入动画保留
                    val badge: Triple<String, Color, Color>? = when {
                        ci.failed -> Triple("!", MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.onError)
                        lostGps -> Triple("!", status.warning, status.onWarning)
                        ci.done -> Triple("✓", status.success, status.onSuccess)
                        else -> null
                    }
                    val badgeScale by animateFloatAsState(
                        targetValue = if (badge == null) 0.4f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "badgeScale"
                    )
                    if (badge != null) {
                        GlassCircle(
                            size = 19.dp,
                            tint = badge.second,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .graphicsLayer {
                                    scaleX = badgeScale
                                    scaleY = badgeScale
                                }
                        ) {
                            Text(
                                badge.first,
                                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    color = badge.third)
                            )
                        }
                    }
                    // 实况小徽标（装饰，不占语义）
                    GlassCircle(
                        size = 16.dp,
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(9.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        ci.item.name,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = main),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        ci.status,
                        style = TextStyle(fontSize = 12.sp, color = statusColor),
                        maxLines = if (busy) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (busy) {
                        Spacer(Modifier.height(5.dp))
                        IndeterminateBar()
                    }
                }

                if (lostGps) {
                    Box(
                        Modifier
                            .height(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))
                            .clickableNoRipple(onReconvert)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("重转", style = TextStyle(fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (!busy) {
                    GlassIconTile(size = 28.dp, onClick = onRemove) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            GlassDivider(startInset = 70.dp)
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
    val icon: ImageVector,
    val onClick: () -> Unit
)
