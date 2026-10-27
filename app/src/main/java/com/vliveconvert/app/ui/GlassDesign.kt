package com.vliveconvert.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import com.vliveconvert.app.ui.theme.statusColors
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import android.os.Build
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 液态玻璃设计系统（方案 B）。
 *
 * 约束：Android/Compose 没有 iOS 26 的实时背景折射（lensing），
 * 这里用「半透明分层 + 顶部高光 + 折射描边 + 环境阴影」模拟；
 * 亮度按主题自动切换（浅色白玻璃 / 深色烟玻璃），跟随莫奈取色。
 */

/** 液态 Dock：镜面高光跟随触点横向移动（只观测、不消费事件，按钮点击不受影响） */
@Composable
private fun Modifier.touchFollowGlow(): Modifier {
    var x by remember { mutableStateOf<Float?>(null) }
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.lastOrNull()
                    x = if (change?.pressed == true) change.position.x else null
                }
            }
        }
        .drawBehind {
            val cx = x ?: return@drawBehind
            val band = size.width * 0.34f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent,
                        Color.White.copy(alpha = 0.12f), Color.Transparent),
                    startX = cx - band, endX = cx + band
                )
            )
        }
}

@Composable
private fun isDarkGlass(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** 整屏壁纸：由主题容器色推出来的柔光斑，玻璃层透过去就是「折射」的观感 */
@Composable
fun Modifier.glassWallpaper(): Modifier {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return drawBehind {
    drawRect(cs.background)
    fun blob(color: Color, centerX: Float, centerY: Float, radiusFactor: Float, alpha: Float) {
        val radius = size.width * radiusFactor
        val center = Offset(size.width * centerX, size.height * centerY)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
    blob(cs.primaryContainer, 0.05f, 0.02f, 0.95f, if (dark) 0.40f else 0.85f)
    blob(cs.tertiaryContainer, 0.95f, 0.16f, 0.95f, if (dark) 0.32f else 0.70f)
    blob(cs.secondaryContainer, 0.05f, 0.52f, 0.95f, if (dark) 0.28f else 0.60f)
    blob(cs.primary, 0.90f, 0.88f, 0.85f, if (dark) 0.18f else 0.30f)
    }
}

/** 玻璃面的修饰符：底、折射描边、环境阴影 */
@Composable
fun Modifier.glassSurface(radius: Dp = 26.dp): Modifier {
    val dark = isDarkGlass()
    val shape = RoundedCornerShape(radius)
    val base = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
    val fillTop = base.copy(alpha = if (dark) 0.68f else 0.60f)
    val fillBottom = base.copy(alpha = if (dark) 0.42f else 0.30f)
    val edgeTop = Color.White.copy(alpha = if (dark) 0.26f else 0.95f)
    val edgeBottom = Color.White.copy(alpha = if (dark) 0.06f else 0.26f)
    return this
        .shadow(18.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.12f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(fillTop, fillBottom)))
        .border(1.dp, Brush.verticalGradient(
            listOf(edgeTop, edgeBottom, Color.White.copy(alpha = if (dark) 0.10f else 0.55f))), shape)
}

/** 底部厚度阴影：玻璃下缘压暗一点，才有「一块玻璃」的体积感 */
@Composable
fun Modifier.glassBottomShade(): Modifier {
    val dark = isDarkGlass()
    return this.background(Brush.verticalGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.12f else 0.05f))))
}

/** 顶部高光（画在内容下方，玻璃的「湿润感」） */
@Composable
fun Modifier.glassSheen(radius: Dp = 26.dp): Modifier {
    val dark = isDarkGlass()
    return this.background(Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.30f), Color.Transparent),
        startY = 0f,
        endY = 260f
    ))
}

/**
 * 真实背景采样模糊（GitHub chrisbanes/haze，Apache-2.0）。
 *
 * Robolectric 的 View 会谎报 isHardwareAccelerated=true，但 view.draw(Canvas)
 * 是软件画布：Haze 在 draw 阶段会退到 RenderScript 并抛 NPE。因此除了硬件加速，
 * 还要排除 Robolectric 运行时；真机/模拟器不受影响。
 */
private fun isRobolectricRuntime(): Boolean =
    Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
        Build.MODEL.contains("robolectric", ignoreCase = true)

@Composable
private fun hazeAvailable(): Boolean =
    !isRobolectricRuntime() && LocalView.current.isHardwareAccelerated

@Composable
fun rememberGlassHazeState(): HazeState {
    return rememberHazeState(blurEnabled = hazeAvailable())
}

/** 标记 Haze 的采样源（硬件加速时才真正记录图层） */
@Composable
fun Modifier.glassHazeSource(state: HazeState): Modifier {
    return if (hazeAvailable()) this.hazeSource(state) else this
}

/**
 * 全应用共享的折射采样层（Kyant0/Backdrop，Apache-2.0）：
 * 页面内容录进一个 GraphicsLayer，玻璃卡按自己的位置取出背景并做 SDF 折射。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** 页面内容的采样源：有折射层时用 LayerBackdrop，否则退回 Haze */
@Composable
fun Modifier.glassBackdropSource(hazeState: HazeState): Modifier {
    val backdrop = LocalGlassBackdrop.current
    return if (backdrop != null && hazeAvailable()) this.layerBackdrop(backdrop)
           else this.glassHazeSource(hazeState)
}

/**
 * 带真实背景模糊的玻璃卡：Haze 负责采样/模糊，本组件负责白膜、高光与折射描边。
 * 背景层必须由调用方用 [glassHazeSource] 标记，且不能把本卡片包含进源里（避免反馈）。
 */
@Composable
fun HazeGlassCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    /** 弹窗等文字密集场景加一层膜，保证高亮照片透上来时仍可读 */
    dense: Boolean = false,
    /** 浮动 Dock 专用：更深的边缘透镜 + 跟随触点的镜面高光（「更液态」） */
    liquid: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop != null && hazeAvailable()) {
        LensGlassCard(backdrop, modifier, radius, contentPadding, dense, liquid, content)
        return
    }
    val hazeEnabled = hazeAvailable()
    val dark = isDarkGlass()
    val shape = RoundedCornerShape(radius)
    val base = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
    // 有真实模糊时白膜必须更薄，否则会把背景糊成一块白板
    val fillTop = base.copy(alpha = if (hazeEnabled) {
        // 深色主题要走「暗烟玻璃」：照片的高亮会透上来，浅色文字必须压暗底才能读。
        // 弹窗（dense）文字密集，比浮动 Dock 更不透明，避免背后大字透上来抢读。
        when {
            dense && dark -> 0.96f
            dense -> 0.92f
            dark -> 0.56f
            else -> 0.30f
        }
    } else {
        // 非硬件加速回退：浮动 Dock 会压在列表上，必须足够不透明才不露出底下文字
        if (dark) 0.92f else 0.88f
    })
    val fillBottom = base.copy(alpha = if (hazeEnabled) {
        when {
            dense && dark -> 0.94f
            dense -> 0.88f
            dark -> 0.42f
            else -> 0.10f
        }
    } else {
        if (dark) 0.80f else 0.74f
    })
    val edgeTop = Color.White.copy(alpha = if (dark) 0.26f else 0.95f)
    val edgeBottom = Color.White.copy(alpha = if (dark) 0.06f else 0.26f)
    Box(
        modifier
            .shadow(18.dp, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.12f))
            .clip(shape)
    ) {
        if (hazeEnabled) {
            Box(Modifier.matchParentSize().hazeEffect(hazeState) {
                blurRadius = if (dense) 40.dp else 24.dp
                noiseFactor = 0.05f
                tints = listOf(HazeTint(base.copy(alpha = if (dark) 0.20f else 0.06f)))
                fallbackTint = HazeTint(base.copy(alpha = if (dark) 0.72f else 0.62f))
            })
        }
        Box(Modifier.matchParentSize()
            .background(Brush.verticalGradient(listOf(fillTop, fillBottom))))
        Box(Modifier.matchParentSize().glassSheen(radius))
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(
            listOf(edgeTop, edgeBottom,
                Color.White.copy(alpha = if (dark) 0.10f else 0.55f))), shape))
        if (liquid) {
            Box(Modifier.matchParentSize().touchFollowGlow())
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 折射版玻璃卡：Kyant0/Backdrop 把背景采样进 GraphicsLayer，
 * 再用 AGSL 按圆角矩形 SDF 做边缘压缩折射、色散与方向性高光。
 * 这是「玻璃 vs 塑料」的关键：只有模糊和白边时，背景只是被磨砂，没有光学变形。
 */
@Composable
private fun LensGlassCard(
    backdrop: LayerBackdrop,
    modifier: Modifier,
    radius: Dp,
    contentPadding: PaddingValues,
    dense: Boolean,
    liquid: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = isDarkGlass()
    val shape = RoundedCornerShape(radius)
    val base = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
    // 玻璃中心要留出显示背景的余量；文字密集的弹窗膜更厚
    val surfaceAlpha = when {
        dense && dark -> 0.78f
        dense -> 0.78f
        liquid -> if (dark) 0.60f else 0.46f
        dark -> 0.66f
        else -> 0.52f
    }
    // 液态 Dock：边缘透镜更深（折射高度/位移更大）、更清澈（模糊更少）、高光更锐
    val blurRadius = when {
        dense -> 18.dp
        liquid -> 14.dp
        else -> 18.dp
    }
    val refractionHeight = when {
        dense -> 24.dp
        liquid -> 28.dp
        else -> 22.dp
    }
    val refractionAmount = when {
        dense -> 44.dp
        liquid -> 52.dp
        else -> 40.dp
    }
    val highlight = if (dense) {
        Highlight.Plain.copy(width = 0.8.dp,
            alpha = if (dark) 0.28f else 0.36f)
    } else {
        Highlight.Default.copy(width = if (liquid) 1.5.dp else 1.2.dp,
            alpha = if (dark) (if (liquid) 0.60f else 0.52f)
                    else (if (liquid) 0.72f else 0.60f))
    }
    val innerShadow = InnerShadow(
        radius = if (dense) 18.dp else 12.dp,
        offset = DpOffset(0.dp, 3.dp),
        color = Color.Black.copy(alpha = if (dark) 0.22f else 0.10f)
    )
    val shadow = Shadow(
        radius = if (dense) 28.dp else 20.dp,
        offset = DpOffset(0.dp, if (dark) 10.dp else 8.dp),
        color = Color.Black.copy(alpha = if (dark) 0.42f else 0.16f)
    )
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(
                    refractionHeight = refractionHeight.toPx(),
                    refractionAmount = refractionAmount.toPx(),
                    depthEffect = true,
                    chromaticAberration = !dense
                )
                if (dense && !dark) {
                    colorControls(brightness = 0.06f, saturation = 1.15f)
                }
            },
            highlight = { highlight },
            shadow = { shadow },
            innerShadow = { innerShadow },
            onDrawSurface = {
                drawRect(base.copy(alpha = surfaceAlpha))
                // 顶部湿面高光 + 底部厚度阴影，补足玻璃的「体积感」
                drawRect(Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.08f else 0.20f),
                        Color.Transparent),
                    startY = 0f, endY = size.height * 0.55f
                ))
                drawRect(Brush.verticalGradient(
                    listOf(Color.Transparent,
                        Color.Black.copy(alpha = if (dark) 0.10f else 0.05f)),
                    startY = size.height * 0.55f, endY = size.height
                ))
            }
        )
    ) {
        if (liquid) {
            Box(Modifier.matchParentSize().touchFollowGlow())
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 应用内玻璃弹窗：不另开系统窗口，直接盖在当前页面上，
 * 因此弹窗玻璃可以采样并模糊它背后的真实内容（Haze 的 source 必须在同窗口内）。
 */
@Composable
fun GlassDialog(
    hazeState: HazeState,
    onDismissRequest: () -> Unit,
    title: String,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismissAction: (() -> Unit)? = null,
    dismissOnBack: Boolean = true,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = isDarkGlass()
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val latestOnDismiss = rememberUpdatedState(onDismissRequest)
    val latestOnConfirm = rememberUpdatedState(onConfirm)
    val latestOnDismissAction = rememberUpdatedState(onDismissAction)
    var dismissing by remember { mutableStateOf(false) }
    // 入场动效：遮罩淡入 + 卡片弹簧轻微放大弹出（与相册面板同一弹簧手感）
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) appear.snapTo(1f)
        else appear.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 380f))
    }
    // 退场与入场对称：先把卡片收回再真正关闭，父层状态在动画结束后才翻转，
    // 弹窗不会「啪」地闪没。所有关闭路径（按钮 / 遮罩 / 返回键）都走这里。
    val dismissThen: (() -> Unit) -> Unit = { action ->
        if (!dismissing) {
            dismissing = true
            scope.launch {
                if (reduceMotion) appear.snapTo(0f)
                else appear.animateTo(0f, tween(140, easing = FastOutLinearInEasing))
                action()
                // 动作可能拒绝关闭（例如校验失败要留在原地）：等一帧确认自己是否已被
                // 移出组合，还在就淡回来——否则会留下看不见却吞掉所有点击的遮罩
                withFrameNanos { }
                if (reduceMotion) appear.snapTo(1f)
                else appear.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 380f))
                dismissing = false
            }
        }
    }
    val requestDismiss: () -> Unit = { dismissThen { latestOnDismiss.value() } }
    BackHandler(enabled = dismissOnBack) { requestDismiss() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = (if (dark) 0.52f else 0.28f) * appear.value))
            .imePadding()
            .then(if (dismissOnOutside) Modifier.pointerInput(Unit) {
                detectTapGestures { requestDismiss() }
            } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        HazeGlassCard(
            hazeState = hazeState,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.82f)
                .graphicsLayer {
                    val p = appear.value
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                    alpha = p
                }
                // 吃掉落在卡片空白处的点击，避免穿透到遮罩触发关闭
                .pointerInput(Unit) { detectTapGestures { } },
            radius = 28.dp,
            contentPadding = PaddingValues(20.dp),
            dense = true
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    title,
                    style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                )
                Spacer(Modifier.height(10.dp))
                ProvideTextStyle(TextStyle(fontSize = 14.sp, lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface)) {
                    content()
                }
            }
            if (confirmLabel != null || dismissLabel != null) {
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissLabel != null) {
                        GlassDialogSecondaryButton(dismissLabel) {
                            dismissThen {
                                latestOnDismissAction.value?.invoke()
                                    ?: latestOnDismiss.value()
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (confirmLabel != null) {
                        GlassDialogPrimaryButton(confirmLabel) {
                            latestOnConfirm.value?.let { action ->
                                dismissThen { action() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassDialogPrimaryButton(text: String, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val contentColor = if (primary.luminance() > 0.42f)
        MaterialTheme.colorScheme.onPrimary else Color.White
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .shadow(10.dp, CircleShape, clip = false,
                ambientColor = primary.copy(alpha = 0.35f),
                spotColor = primary.copy(alpha = 0.28f))
            .background(Brush.verticalGradient(listOf(
                lerp(primary, Color.White, 0.18f), primary)))
            .border(1.dp, Color.White.copy(alpha = 0.60f), CircleShape)
            .clickable(interactionSource = interaction,
                indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = contentColor))
    }
}

@Composable
private fun GlassDialogSecondaryButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction,
                indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

/** 玻璃卡片：用于提示组、设置分组、底部 Dock */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.glassSurface(radius)) {
        Box(Modifier.matchParentSize().glassSheen())
        Box(Modifier.matchParentSize().glassBottomShade())
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** 只有玻璃底、内容自己排（例如整块可滚动列表） */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, radius: Dp = 24.dp) {
    Box(modifier.glassSurface(radius)) {
        Box(Modifier.matchParentSize().glassSheen())
        Box(Modifier.matchParentSize().glassBottomShade())
    }
}

/**
 * 玻璃进度条：内凹玻璃槽 + 主题渐变填充 + 转换中持续流动的高光。
 * M3 默认进度条是平涂色块，压在玻璃底上像贴纸；这条与液态语言同源。
 */
@Composable
fun GlassProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    active: Boolean = false,
    finished: Boolean = false
) {
    val dark = isDarkGlass()
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val success = statusColors.success
    val fillEnd = if (finished) success else tertiary
    // 只把相位记成 State，在 draw 阶段读取：高光每帧只重画，不重组整条底栏
    // 减少动效：不做流动高光，静态渐变即可
    val phase = if (active && !LocalReduceMotion.current) {
        rememberInfiniteTransition(label = "progressSheen").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
            label = "progressSheenPhase"
        )
    } else null
    // 完成时一次白色闪光：数字停在 100% 之前先「亮一下」
    val flash = remember { Animatable(0f) }
    LaunchedEffect(finished) {
        if (finished) {
            flash.snapTo(0.55f)
            flash.animateTo(0f, tween(380, easing = FastOutLinearInEasing))
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.20f else 0.10f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.10f else 0.50f), CircleShape)
            .semantics {
                progressBarRangeInfo =
                    ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val corner = size.height / 2f
            val filled = size.width * progress.coerceIn(0f, 1f)
            if (filled <= 0f) return@Canvas
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(primary, fillEnd),
                    startX = 0f,
                    endX = size.width.coerceAtLeast(1f)
                ),
                size = Size(filled, size.height),
                cornerRadius = CornerRadius(corner)
            )
            // 流动高光只裁在填充段内，进度条不会「自己长出一条尾巴」
            if (phase != null) {
                val band = size.width * 0.38f
                val x = (size.width + band) * phase.value - band
                clipRect(0f, 0f, filled, size.height) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent,
                                Color.White.copy(alpha = 0.45f), Color.Transparent),
                            startX = x, endX = x + band
                        ),
                        topLeft = Offset(x, 0f),
                        size = Size(band, size.height)
                    )
                }
                // 推进端一颗高光点，像液体在前面走
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = corner * 0.55f,
                    center = Offset(filled - corner, corner)
                )
            }
            if (flash.value > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = flash.value),
                    size = Size(filled, size.height),
                    cornerRadius = CornerRadius(corner)
                )
            }
        }
    }
}

/** 转换中的不确定进度：玻璃轨道里一段主题渐变光带循环掠过 */
@Composable
fun GlassIndeterminateBar(modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val dark = isDarkGlass()
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track: @Composable androidx.compose.foundation.layout.BoxScope.(Float) -> Unit = { phaseValue ->
        Canvas(Modifier.fillMaxSize()) {
            val seg = size.width * 0.34f
            val x = (size.width + seg) * phaseValue - seg
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, primary.copy(alpha = 0.9f),
                        tertiary.copy(alpha = 0.9f), Color.Transparent),
                    startX = x, endX = x + seg
                ),
                topLeft = Offset(x, 0f),
                size = Size(seg, size.height)
            )
        }
    }
    if (LocalReduceMotion.current) {
        // 静态分支：固定位置的一段光带（不建 infinite transition，零帧消耗）
        Box(
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.18f else 0.08f))
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
        ) {
            track(0.22f)
        }
        return
    }
    val phase = rememberInfiniteTransition(label = "indeterminate").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing)),
        label = "indeterminatePhase"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.18f else 0.08f))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
    ) {
        track(phase.value)
    }
}

/**
 * 玻璃输入框：12dp 圆角的内嵌玻璃槽。
 * 替代 M3 浮标签输入框——后者的直角、浮动标签压边线与整体圆角语言不相容。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true
) {
    val dark = isDarkGlass()
    val shape = RoundedCornerShape(12.dp)
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (dark) 0.08f else 0.05f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.14f else 0.60f), shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = TextStyle(fontSize = 15.sp, color = hintColor))
                }
                inner()
            }
        }
    )
}

/** 发丝分隔线（玻璃上不能用实色线，会显脏） */
@Composable
fun GlassDivider(startInset: Dp = 0.dp, endInset: Dp = 0.dp) {
    val dark = isDarkGlass()
    Row(Modifier.fillMaxWidth().padding(start = startInset, end = endInset)) {
        Box(Modifier.weight(1f).height(0.7.dp).background(Brush.horizontalGradient(
            listOf(Color.Transparent,
                Color.White.copy(alpha = if (dark) 0.14f else 0.75f),
                Color.Transparent))))
    }
}

/**
 * 玻璃图标磁贴：二级页的图标底座。
 * 方圆角（squircle 感）而不是整圆——整圆在列表里显得像贴纸，
 * 磁贴才能和卡片的圆角语言对齐；彩色字形落在淡色玻璃上。
 */
@Composable
fun GlassIconTile(
    size: Dp = 34.dp,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    cornerFraction: Float = 0.26f,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isDarkGlass()
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction, pressedScale = 0.92f)
    // 圆角并入全局体系：磁贴 = min(12dp, 34%)，与缩略图(12dp)同心于 24dp 卡片；
    // cornerFraction >= 0.5 表示显式要圆（播放角标等圆形语义）
    val shape = if (cornerFraction >= 0.5f) CircleShape
                else RoundedCornerShape(minOf(12.dp, size * cornerFraction))
    val top = if (tint != null) tint.copy(alpha = if (dark) 0.34f else 0.26f)
              else Color.White.copy(alpha = if (dark) 0.16f else 0.72f)
    val bottom = if (tint != null) tint.copy(alpha = if (dark) 0.12f else 0.08f)
                 else Color.White.copy(alpha = if (dark) 0.08f else 0.34f)
    val edgeTop = Color.White.copy(alpha = if (dark) 0.28f else 0.95f)
    val edgeBottom = Color.White.copy(alpha = if (dark) 0.06f else 0.30f)
    val base = modifier
        .size(size)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .shadow(8.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = 0.12f),
            spotColor = Color.Black.copy(alpha = 0.08f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(1.dp, Brush.verticalGradient(listOf(edgeTop, edgeBottom)), shape)
    Box(
        if (onClick != null) {
            base.clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            )
        } else base,
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (dark) 0.06f else 0.22f),
                Color.Transparent), endY = 70f)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.Transparent,
                Color.Black.copy(alpha = if (dark) 0.10f else 0.04f)),
            startY = 40f)))
        content()
    }
}

/** 玻璃圆钮：播放角标等「圆形语义」的场景（视频标记、圆形开关底座） */
@Composable
fun GlassCircle(
    size: Dp = 32.dp,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    GlassIconTile(
        size = size, tint = tint, modifier = modifier,
        cornerFraction = 0.5f, onClick = onClick, content = content
    )
}


/** 玻璃图标按钮（返回、齿轮、添加）：方圆角磁贴，与卡片圆角同语言 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    GlassIconTile(size = size, modifier = modifier, onClick = onClick) {
        Icon(icon, contentDescription = contentDescription, tint = tint,
            modifier = Modifier.size(size * 0.45f))
    }
}

/** 高光玻璃主按钮：上亮下深的主题色渐变 + 顶部反光 + 彩色辉光 */
@Composable
fun GlossyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    // 深色主题的 primary 是浅薰衣草色：若仍用白字，对比度只有 ~1.5:1。
    // onPrimary 在浅色主题是白、深色主题是深藏青，正好是对 primary 的对比配对。
    val contentColor = if (primary.luminance() > 0.42f)
        MaterialTheme.colorScheme.onPrimary else Color.White
    val top = lerp(primary, Color.White, 0.18f)
    val bottom = lerp(primary, Color.Black, 0.12f)
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction, pressedScale = 0.975f)
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
            .shadow(18.dp, CircleShape, clip = false,
                ambientColor = primary.copy(alpha = 0.42f),
                spotColor = primary.copy(alpha = 0.34f))
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(1.dp, Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.18f))), CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), endY = 110f)))
        Text(text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp, color = contentColor))
    }
}

/** 玻璃次级按钮（取消转换这类破坏性但非主路径的动作） */
@Composable
fun GlassOutlineButton(
    text: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.error,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction, pressedScale = 0.975f)
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color))
    }
}

// ---- 文本样式（随主题取色） ----

@Composable
fun glassTitleStyle(): TextStyle = TextStyle(
    fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp,
    color = MaterialTheme.colorScheme.onBackground)

@Composable
fun glassSubtitleStyle(): TextStyle = TextStyle(
    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun glassSectionLabelStyle(): TextStyle = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun glassRowTitleStyle(): TextStyle = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)

@Composable
fun glassRowSubStyle(): TextStyle = TextStyle(
    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 供设置页/选择器复用的玻璃胶囊（菜单、相册切换） */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val base = modifier
        .height(34.dp)
        .glassSurface(radius = 17.dp)
    Box(
        if (onClick != null) {
            base.clickable(onClick = onClick, indication = LocalIndication.current,
                interactionSource = remember { MutableInteractionSource() })
        } else base,
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.matchParentSize().glassSheen(17.dp))
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            content = content)
    }
}
