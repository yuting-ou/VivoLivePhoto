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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Color
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
                // end 4dp：齿轮在 48dp 触摸区内居中，自身留白 (48-22)/2=13dp，
                // 4+13=17dp ≈ 标题左缘 16dp——与设置/修复页返回键（horizontal 4dp）同一套对齐
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
                    // 16dp 与列表 / 摘要 / 底栏同一基线（原先 12dp，与全页不对齐）
                    .padding(horizontal = 16.dp, vertical = 6.dp)
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
                        .padding(horizontal = 16.dp),
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
                    .padding(start = 16.dp, end = 6.dp, bottom = 4.dp),
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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

        // ── 底部操作栏：设置摘要 + 主路径按钮 ──
        Surface(tonalElevation = 3.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 三态：2 转换中 / 1 完成节拍 / 0 空闲。
                // 用 AnimatedContent + SizeTransform 而不是 Crossfade：底栏各态高度不同
                // （进度+取消 ≈ 88dp，摘要+按钮 ≈ 74dp），Crossfade 期间容器按最高子项定尺寸，
                // 切换瞬间整块高度跳变，观感就是「原地闪一下」；SizeTransform 让尺寸随动画平滑过渡
                // v2.0 数字滚动：服务端按条目跳变 progress，临界阻尼弹簧让它平滑追赶
                // （无过冲：100% 恒为 100%；新批次归零时快速泄回，读作"重置"）
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
                        // 整体进度
                        Column {
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
                                    "${(shownProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { shownProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            Spacer(Modifier.height(10.dp))
                            if (barState == 2) {
                                val cancelInteraction = remember { MutableInteractionSource() }
                                val cancelScale = rememberPressScale(cancelInteraction)
                                OutlinedButton(
                                    onClick = onCancelConvert,
                                    interactionSource = cancelInteraction,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .graphicsLayer {
                                            scaleX = cancelScale.value
                                            scaleY = cancelScale.value
                                        }
                                ) { Text("取消转换") }
                            } else {
                                // 完成节拍：留一块与取消键等高的空位，
                                // 免得 2→1 时高度又跳一次
                                Spacer(Modifier.fillMaxWidth().height(46.dp))
                            }
                        }
                    } else {
                        Column {
                            // 设置摘要：一眼看清关键选项，点击进设置
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    // 整行可点：最小尺寸与 clickable 同链，保证命中区高度 ≥48dp
                                    .minimumInteractiveComponentSize()
                                    .clickableNoRipple(onOpenSettings),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "输出 ${if (moveToCamera) "DCIM/Camera" else outputRelPath}" +
                                        " · 删原图 ${if (deleteOriginal) "开" else "关"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // 最多两行：2× 字号下单行会把「删原图 开/关」省略掉，
                                    // 而这是安全状态，不能被截断（正常字号仍是一行）
                                    maxLines = 2,
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
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // 添加收窄为方形图标按钮（空态中央已有大 ＋，列表头也有「清空」，
                                // 图标足够达意），横向空间让给主操作——主次更分明
                                val addInteraction = remember { MutableInteractionSource() }
                                val addScale = rememberPressScale(addInteraction)
                                OutlinedButton(
                                    onClick = onAddMore,
                                    interactionSource = addInteraction,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .graphicsLayer {
                                            scaleX = addScale.value
                                            scaleY = addScale.value
                                        },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "添加",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                val startInteraction = remember { MutableInteractionSource() }
                                val startScale = rememberPressScale(startInteraction)
                                Button(
                                    onClick = onStartConvert,
                                    interactionSource = startInteraction,
                                    // 不置灰：置灰的主按钮点下去连 onClick 都不派发，
                                    // 界面也没有一处解释「为什么按不动」——用户视角就是「点了没反应」。
                                    // 让 startConvert() 里那句原本走不到的提示替它说话
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .graphicsLayer {
                                            scaleX = startScale.value
                                            scaleY = startScale.value
                                        }
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
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
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 中央大 + 号：用户的第一反应就是点它，必须真的能点（涟漪 + 按压缩放）
        val addInteraction = remember { MutableInteractionSource() }
        val addScale by rememberPressScale(addInteraction, pressedScale = 0.94f)
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer {
                    scaleX = addScale
                    scaleY = addScale
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(
                    interactionSource = addInteraction,
                    indication = LocalIndication.current,
                    onClick = onAdd
                ),
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
    modifier: Modifier = Modifier,
    ci: ConvertItem,
    onRemove: () -> Unit,
    onReconvert: () -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    val busy = isBusyStatus(ci.status)
    val lostGps = ci.done && ci.lostGps && !ci.failed
    val status = statusColors
    val bgTarget = when {
        ci.failed -> MaterialTheme.colorScheme.errorContainer
        lostGps -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    // 状态切换时底色平滑过渡（v1.8.5）：待转换→转换中→完成不再瞬变
    val bg by animateColorAsState(bgTarget, tween(300), label = "rowBg")
    val main = MaterialTheme.colorScheme.onSurface
    val statusColor = when {
        ci.failed -> MaterialTheme.colorScheme.onErrorContainer
        lostGps -> MaterialTheme.colorScheme.onTertiaryContainer
        ci.done -> status.success
        busy -> status.info
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // v1.8.9 失败抖动：状态**翻转到**失败的一瞬间左右轻抖（三次衰减），红色底色同步淡入。
    // 必须对比上一态：恢复队列里本来就有失败项时，首组合不得抖（v1.9.0 修）
    val hapticView = LocalView.current
    var prevFailed by remember(ci.item.key) { mutableStateOf(ci.failed) }
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(ci.failed) {
        if (ci.failed && !prevFailed) {
            // 多模态：与抖动同帧的失败触觉（因果明确、仅在失败瞬间给一次）
            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
            for (d in listOf(-10f, 10f, -7f, 7f, -3f, 0f)) {
                shakeX.snapTo(d)
                delay(45)
            }
        }
        prevFailed = ci.failed
    }
    // 左滑移除（v2.0.3）：与行内移除按钮同一动作；转换中的项目禁用滑除
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !busy) {
                // 提交瞬间的触觉「到位感」；撤销条随后出现，失败可撤销
                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onRemove()
                true
            } else {
                false
            }
        },
        // 阈值从默认 50% 提高到 60%：半程滑一半就反弹，防手滑误删
        // （业界共识：滑动类破坏性操作要么要求明确的完整手势，要么给撤销；
        //   本应用两者都做——阈值 + 移除后 Snackbar 撤销）
        positionalThreshold = { totalDistance -> totalDistance * 0.6f }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !busy,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    "移除",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 20.dp)
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bg,
            modifier = Modifier
                .graphicsLayer { translationX = shakeX.value }
                .fillMaxWidth()
        ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩略图 + 右下角状态角标
            Box(Modifier.size(48.dp)) {
                // v1.8.9 进度环：转换中时缩略图外圈一道旋转弧（生命感；批次进度对单项无意义，
                // 用不确定弧而不是假进度）
                if (busy) {
                    val ringAngle by rememberInfiniteTransition(label = "ring").animateFloat(
                        initialValue = 0f, targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            tween(900, easing = LinearEasing)), label = "ringAngle"
                    )
                    Canvas(
                        Modifier
                            .size(56.dp)
                            .align(Alignment.Center)
                    ) {
                        drawArc(
                            color = status.info,
                            startAngle = -90f + ringAngle, sweepAngle = 110f, useCenter = false,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    // v1.8.9 显影动画：状态翻到完成的一瞬间，缩略图从低饱和半透明
                    // "显影"为全彩（暗房冲印的品牌隐喻）。恢复队列里的已完成项
                    // 首次组合即为目标值（animateFloatAsState 初组合不动画），不会误放
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
                                if (develop < 1f) Modifier.blur(
                                    (8 * (1 - develop)).dp)
                                else Modifier
                            )
                    )
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
                // 角标弹入：状态翻到完成/失败/丢位置的一瞬间从 40% 缩放弹出
                //（spring 回弹；初始组合即完成态时无动画，不影响静态截图）
                val badgeScale by animateFloatAsState(
                    targetValue = if (badge == null) 0.4f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "badgeScale"
                )
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = badgeScale
                                scaleY = badgeScale
                            }
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
                // 不限定 size：IconButton 默认 48dp，满足最小触摸目标（原先压到 34dp 过小）。
                // 图标在 48dp 触摸区内居中会显得「浮」（视觉右缩进 ≈25dp，卡片内容左缩进仅 10dp），
                // 按视觉重心右移 10dp 贴齐卡片右缘内容线；触摸区不变
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(17.dp)
                            .offset(x = 10.dp)
                    )
                }
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
