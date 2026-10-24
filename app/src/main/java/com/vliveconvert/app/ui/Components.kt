package com.vliveconvert.app.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.foundation.interaction.collectIsPressedAsState

/**
 * 按压缩放反馈（Emil：任何可点击元素都必须对手指有回应）：
 * 按下 0.97、松开弹回，临界阻尼弹簧（无过冲），120ms 量级内完成。
 */
@Composable
internal fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): androidx.compose.runtime.State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh),
        label = "pressScale"
    )
}


/**
 * 强 ease-out 入场曲线（Emil Kowalski 的 0.23,1,0.32,1）：
 * Compose/Material 默认 FastOutSlowInEasing 起步慢，入场会比实际时长更显拖沓；
 * 入场类动画统一改用这条「起步即冲」的曲线。退场保持更快时长即可。
 */
internal val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)


/**
 * 缩略图内存 LRU 缓存（按需加载）：
 * 每次仅组合屏幕可见区域附近的网格项，滚动回来命中缓存直接显示。
 */
val thumbCache = LruCache<String, Bitmap>(96)

// 缩略图加载有界并发：限制同时进行的 ContentResolver.loadThumbnail 数量，
// 避免快速滚动/千张列表时并发 I/O 过多拖垮主线程与磁盘
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
val thumbDispatcher = Dispatchers.IO.limitedParallelism(4)

/**
 * 系统缩略图（loadThumbnail，走系统缓存不落盘）：
 * - 按需加载：仅组合屏幕可见区域附近的项；命中 LRU 缓存直接显示
 * - 加载动画：占位呼吸脉冲，加载完成淡入+缩放进入
 */
@Composable
fun MediaThumbnail(uri: Uri, resolver: ContentResolver, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, uri) {
        val key = uri.toString()
        // 命中缓存（滚动回来）：直接显示，不重复加载
        thumbCache.get(key)?.let { value = it; return@produceState }
        value = withContext(thumbDispatcher) {
            val b = try {
                resolver.loadThumbnail(uri, Size(160, 160), null)
            } catch (_: Exception) {
                null
            }
            if (b != null) thumbCache.put(key, b)
            b
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = bitmap != null,
            enter = fadeIn(tween(200, easing = EaseOutStrong)) +
                scaleIn(tween(240, easing = EaseOutStrong), initialScale = 0.92f),
            exit = fadeOut(tween(140))
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        // 加载占位：呼吸底色 + 淡淡的「照片」字形。
        // 只给一块灰板会让人以为「这里本来就空」，画上轮廓才读得出是在等缩略图
        if (bitmap == null) {
            val pulse by rememberInfiniteTransition(label = "thumbPulse").animateFloat(
                initialValue = 0.45f,
                targetValue = 0.9f,
                animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
                label = "pulseAlpha"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = pulse)),
                contentAlignment = Alignment.Center
            ) {
                val glyph = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
                Canvas(Modifier.size(26.dp)) {
                    val w = size.width
                    val h = size.height
                    val stroke = 1.6.dp.toPx()
                    drawRoundRect(
                        color = glyph,
                        cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
                        size = ComposeSize(w, h),
                        style = Stroke(width = stroke)
                    )
                    drawCircle(glyph, radius = w * 0.075f, center = Offset(w * 0.30f, h * 0.30f))
                    val mountain = Path().apply {
                        moveTo(w * 0.14f, h * 0.76f)
                        lineTo(w * 0.40f, h * 0.46f)
                        lineTo(w * 0.58f, h * 0.68f)
                        lineTo(w * 0.70f, h * 0.56f)
                        lineTo(w * 0.86f, h * 0.76f)
                    }
                    drawPath(
                        path = mountain,
                        color = glyph,
                        style = Stroke(
                            width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}

/**
 * 圆形三态复选框：
 * - Off：外圈描边
 * - Indeterminate：primary 横线
 * - On：primary 填充 + 白色对勾
 */
@Composable
fun CircleTriCheckbox(
    state: ToggleableState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val borderColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val checkColor = MaterialTheme.colorScheme.onPrimary
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            // 触摸目标 ≥48dp：视觉仍是 26dp 圆，点击区域扩到 Material 标准最小尺寸
            // （原先是 26dp，而本组件承担全选/按日期全选等关键交互，过小易误触）
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        // 视觉圆（26dp，与修复前完全一致）：描边 + 三态图形
        Box(
            Modifier
                .size(26.dp)
                .border(2.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(26.dp)) {
                val s = size.minDimension
                val stroke = 2.dp.toPx()
                when (state) {
                    ToggleableState.On -> {
                        drawCircle(fillColor, radius = s / 2 - stroke / 2 - 1.dp.toPx())
                        drawLine(
                            color = checkColor,
                            start = Offset(s * 0.30f, s * 0.53f),
                            end = Offset(s * 0.45f, s * 0.68f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = checkColor,
                            start = Offset(s * 0.45f, s * 0.68f),
                            end = Offset(s * 0.72f, s * 0.34f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                    ToggleableState.Indeterminate -> {
                        drawLine(
                            color = fillColor,
                            start = Offset(s * 0.28f, s / 2),
                            end = Offset(s * 0.72f, s / 2),
                            strokeWidth = stroke * 1.2f,
                            cap = StrokeCap.Round
                        )
                    }
                    ToggleableState.Off -> {}
                }
            }
        }
    }
}

/**
 * 无涟漪点击（文本按钮用）。
 * 全应用唯一实现：此前 MainScreen 与 SettingsScreen 各有一份完全相同的私有副本。
 */
internal fun Modifier.clickableNoRipple(
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource? = null
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    clickable(
        interactionSource = source,
        indication = null,
        onClick = onClick
    )
}

/**
 * 横幅 / 工具栏内的紧凑文本按钮（「去授权 / 重新转换 / 恢复 / 清空」这类内联动作）。
 *
 * 视觉仍是贴合文字的紧凑文字（可选底色胶囊），但**触摸目标**扩到 ≥48dp——
 * 原先仅靠 padding 只有约 28~32dp，低于 Material 可达性下限。
 *
 * 两个易错点（都曾踩到）：
 * 1. `clickable` 必须与 `minimumInteractiveComponentSize()` 在**同一条修饰符链**上，
 *    否则放大的只是外层 Box 的布局尺寸，可点区域仍是文字本身那点大小；
 * 2. 最小尺寸要加在外层 Box（内含居中）而不是 Text 上——Text 在高于自身内容的约束里
 *    会贴顶排版，文字会相对行动区错位。
 */
@Composable
fun InlineTextButton(
    text: String,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color? = null,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    // 无涟漪的紧凑按钮只能靠缩放表达"按到了"：0.96 足够可见又不跳
    val interaction = remember { MutableInteractionSource() }
    val pressScale by rememberPressScale(interaction, pressedScale = 0.96f)
    Box(
        modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .minimumInteractiveComponentSize()
            .clickableNoRipple(onClick, interaction),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = style,
            color = contentColor,
            modifier = Modifier
                .then(if (background != null) Modifier.background(background, shape) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** 日期 key（年-月-日；月份补 1，与 dateLabel 一致） */
fun dateKey(time: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = time }
    return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
}

/** 日期标签：今天 / 昨天 / x月x日（今年） / yyyy年x月x日（往年） */
fun dateLabel(time: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = time }
    val now = Calendar.getInstance()

    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(cal, now) -> "今天"
        sameDay(cal, yesterday) -> "昨天"
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
        else ->
            "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    }
}

/**
 * 与应用一致的撤销条（替代 M3 默认的反相近黑 Snackbar）：
 * 默认反相色在莫奈浅色主题里像一块黑砖，与卡片体系割裂；
 * 这里改用 surfaceContainerHigh 浮层 + 细描边 + 阴影，动作色用 primary。
 */
@Composable
fun ThemedSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> ThemedSnackbar(data) }
}

/** 单条撤销条本体（拆出便于截图预览与复用） */
@Composable
fun ThemedSnackbar(
    snackbarData: androidx.compose.material3.SnackbarData,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    // 自绘而不是套 M3 Snackbar：后者的默认皮肤会在布局边界内再缩进一层，
    // 从外面加描边/阴影总会露出「双层壳」；这里直接用应用卡片同款 Surface
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        border = BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                snackbarData.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            snackbarData.visuals.actionLabel?.let { label ->
                InlineTextButton(
                    text = label,
                    contentColor = MaterialTheme.colorScheme.primary,
                    onClick = { snackbarData.performAction() },
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (snackbarData.visuals.withDismissAction) {
                IconButton(onClick = { snackbarData.dismiss() }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
