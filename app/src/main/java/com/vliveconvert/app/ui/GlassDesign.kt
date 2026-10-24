package com.vliveconvert.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import android.os.Build
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
    val fillTop = base.copy(alpha = if (dark) 0.74f else 0.66f)
    val fillBottom = base.copy(alpha = if (dark) 0.46f else 0.34f)
    val edgeTop = Color.White.copy(alpha = if (dark) 0.22f else 0.95f)
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
 * 带真实背景模糊的玻璃卡：Haze 负责采样/模糊，本组件负责白膜、高光与折射描边。
 * 背景层必须由调用方用 [glassHazeSource] 标记，且不能把本卡片包含进源里（避免反馈）。
 */
@Composable
fun HazeGlassCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    radius: Dp = 30.dp,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val hazeEnabled = hazeAvailable()
    val dark = isDarkGlass()
    val shape = RoundedCornerShape(radius)
    val base = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
    // 有真实模糊时白膜必须更薄，否则会把背景糊成一块白板
    val fillTop = base.copy(alpha = if (hazeEnabled) {
        // 深色主题要走「暗烟玻璃」：照片的高亮会透上来，浅色文字必须压暗底才能读
        if (dark) 0.56f else 0.30f
    } else {
        // 非硬件加速回退：浮动 Dock 会压在列表上，必须足够不透明才不露出底下文字
        if (dark) 0.92f else 0.88f
    })
    val fillBottom = base.copy(alpha = if (hazeEnabled) {
        if (dark) 0.42f else 0.10f
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
                blurRadius = 24.dp
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
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** 玻璃卡片：用于提示组、设置分组、底部 Dock */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.glassSurface(radius)) {
        Box(Modifier.matchParentSize().glassSheen())
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** 只有玻璃底、内容自己排（例如整块可滚动列表） */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, radius: Dp = 26.dp) {
    Box(modifier.glassSurface(radius)) {
        Box(Modifier.matchParentSize().glassSheen())
    }
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

/** 玻璃圆钮：iOS 26 的 Ctrl Center 语言，彩色字形落在淡色玻璃上（不是实色方块） */
@Composable
fun GlassCircle(
    size: Dp = 32.dp,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isDarkGlass()
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction, pressedScale = 0.92f)
    val shape = CircleShape
    val top = if (tint != null) tint.copy(alpha = if (dark) 0.34f else 0.26f)
              else Color.White.copy(alpha = if (dark) 0.16f else 0.72f)
    val bottom = if (tint != null) tint.copy(alpha = 0.10f)
                 else Color.White.copy(alpha = if (dark) 0.08f else 0.34f)
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
        .border(1.dp, Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.24f else 0.95f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.30f))), shape)
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
        content()
    }
}


/** 玻璃圆形图标按钮（齿轮、返回、添加） */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    GlassCircle(size = size, modifier = modifier, onClick = onClick) {
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
            .height(46.dp)
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
