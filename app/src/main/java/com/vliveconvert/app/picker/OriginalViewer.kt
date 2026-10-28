package com.vliveconvert.app.picker

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.vliveconvert.app.ui.GlassIconButton
import com.vliveconvert.app.ui.GlossyButton
import com.vliveconvert.app.ui.HazeGlassCard
import com.vliveconvert.app.ui.MediaThumbnail
import com.vliveconvert.app.ui.glassBackdropSource
import com.vliveconvert.app.ui.rememberGlassHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 全屏原图查看：点网格左上角放大镜进入。
 * 原图与网格缩略图分开加载（这里才解码 JPG 本身），左右翻当前可见列表。
 */
@Composable
internal fun OriginalViewer(
    items: List<MediaItem>,
    initialIndex: Int,
    selectedIds: Set<Long>,
    onToggleSelected: (MediaItem) -> Unit,
    onClose: () -> Unit
) {
    if (items.isEmpty()) return

    val context = LocalContext.current
    val pager = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )
    val hazeState = rememberGlassHazeState()
    val scope = rememberCoroutineScope()

    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    val page = pager.currentPage
    val item = items[page]
    val selected = item.id in selectedIds
    val resetZoom: () -> Unit = {
        scope.launch {
            coroutineScope {
                launch { scale.animateTo(1f, tween(ZOOM_MS)) }
                launch { offset.animateTo(Offset.Zero, tween(ZOOM_MS)) }
            }
        }
    }

    BackHandler {
        if (scale.value > 1.01f) resetZoom() else onClose()
    }
    LaunchedEffect(page) {
        scale.snapTo(1f)
        offset.snapTo(Offset.Zero)
        imageSize = IntSize.Zero
    }
    DisposableEffect(Unit) {
        onDispose { OriginalImageLoader.clear() }
    }
    ForceLightSystemBars()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().glassBackdropSource(hazeState)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { container = it }
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = offset.value.x
                        translationY = offset.value.y
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .pointerInput(Unit) {
                        var lastTapUp = 0L
                        var lastTapPos = Offset.Unspecified
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var up: PointerInputChange? = null
                            var moved = false
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val oldScale = scale.value
                                val newScale = (oldScale * event.calculateZoom()).coerceIn(1f, 4f)
                                val shouldHandle = pressed >= 2 || oldScale > 1.01f
                                if (shouldHandle) {
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val center = Offset(container.width / 2f, container.height / 2f)
                                    val pan = event.calculatePan()
                                    var target = offset.value + pan
                                    if (centroid != Offset.Unspecified && newScale != oldScale) {
                                        val focus = centroid - center
                                        target = (offset.value - focus) * (newScale / oldScale) +
                                            focus + pan
                                    }
                                    val max = maxPan(newScale, container, imageSize)
                                    target = Offset(
                                        target.x.coerceIn(-max.x, max.x),
                                        target.y.coerceIn(-max.y, max.y)
                                    )
                                    scope.launch {
                                        scale.snapTo(newScale)
                                        offset.snapTo(target)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null) {
                                    if ((change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) moved = true
                                    if (!change.pressed) up = change
                                }
                            } while (event.changes.any { it.pressed })

                            val tap = up
                            if (tap != null && !moved) {
                                val now = tap.uptimeMillis
                                if (now - lastTapUp <= 300L &&
                                    lastTapPos != Offset.Unspecified &&
                                    (tap.position - lastTapPos).getDistance() <= 48.dp.toPx()
                                ) {
                                    lastTapUp = 0L
                                    lastTapPos = Offset.Unspecified
                                    scope.launch {
                                        if (scale.value > 1.01f) {
                                            coroutineScope {
                                                launch { scale.animateTo(1f, tween(ZOOM_MS)) }
                                                launch {
                                                    offset.animateTo(Offset.Zero, tween(ZOOM_MS))
                                                }
                                            }
                                        } else {
                                            val target = doubleTapZoomTarget(
                                                tap.position, container, imageSize
                                            )
                                            // 两条动画必须同帧起步、同一曲线：
                                            // 先放大再平移会把锚点甩出手指，看起来就是「缩放时图片跑偏」
                                            coroutineScope {
                                                launch {
                                                    scale.animateTo(DOUBLE_TAP_SCALE, tween(ZOOM_MS))
                                                }
                                                launch { offset.animateTo(target, tween(ZOOM_MS)) }
                                            }
                                        }
                                    }
                                } else {
                                    lastTapUp = now
                                    lastTapPos = tap.position
                                }
                            }
                        }
                    }
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = scale.value <= 1.01f,
                    key = { items[it].id }
                ) { index ->
                    OriginalPage(
                        item = items[index],
                        modifier = Modifier.fillMaxSize(),
                        onImageSize = { size ->
                            if (pager.currentPage == index) imageSize = size
                        }
                    )
                }
            }
            // 顶部/底部暗渐变压在原图上，保证白色状态栏图标与玻璃栏可读
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.58f),
                        0.24f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
            )
        }

        // 顶栏：与选择器/设置同一套液态玻璃
        HazeGlassCard(
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            radius = 28.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "关闭预览",
                    onClick = onClose,
                    size = 38.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${pager.currentPage + 1} / ${items.size}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "原图",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 底栏：选择/取消与手势提示
        HazeGlassCard(
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            radius = 28.dp,
            contentPadding = PaddingValues(10.dp)
        ) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已选 ${selectedIds.size} 张",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "双指缩放 · 双击放大",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.size(8.dp))
                GlossyButton(
                    text = if (selected) "已选 · 取消选择" else "选择这张",
                    onClick = { onToggleSelected(item) }
                )
            }
        }
    }
}

@Composable
private fun OriginalPage(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onImageSize: (IntSize) -> Unit
) {
    val context = LocalContext.current
    var attempt by remember(item.uri) { mutableIntStateOf(0) }
    val state by produceState<OriginalLoadState>(OriginalLoadState.Loading, item.uri, attempt) {
        value = OriginalLoadState.Loading
        value = try {
            val bitmap = withContext(Dispatchers.IO) {
                OriginalImageLoader.load(context.contentResolver, item.uri)
            }
            if (!isActive) return@produceState
            OriginalLoadState.Ready(bitmap)
        } catch (_: Exception) {
            OriginalLoadState.Failed("无法打开原图")
        }
    }

    Crossfade(targetState = state, animationSpec = tween(220), label = "originalImage") { s ->
        when (s) {
            OriginalLoadState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
                MediaThumbnail(item.uri, context.contentResolver, Modifier.fillMaxSize().alpha(0.32f))
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(32.dp)
                )
            }
            is OriginalLoadState.Ready -> {
                val bitmap = s.bitmap
                LaunchedEffect(bitmap) {
                    onImageSize(IntSize(bitmap.width, bitmap.height))
                }
                Box(modifier) {
                    // 同图虚化铺满黑边，让玻璃顶/底栏始终有背景可折射
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().blur(32.dp).alpha(0.62f),
                        contentScale = ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)))
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            is OriginalLoadState.Failed -> Column(
                modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text("无法打开原图", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
                Spacer(Modifier.size(4.dp))
                Text(
                    "文件可能已被移动或删除",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.size(14.dp))
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable {
                            OriginalImageLoader.evict(item.uri)
                            attempt++
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("重试", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

private sealed interface OriginalLoadState {
    data object Loading : OriginalLoadState
    data class Ready(val bitmap: Bitmap) : OriginalLoadState
    data class Failed(val message: String) : OriginalLoadState
}

/** 原图最多解码到长边 2048px：4× 缩放有细节，同时单张内存可控。 */
internal fun computeOriginalDecodeSize(
    width: Int,
    height: Int,
    maxLongSide: Int = OriginalImageLoader.TARGET_LONG_SIDE
): IntSize {
    if (width <= 0 || height <= 0 || maxLongSide <= 0) return IntSize(1, 1)
    val longest = maxOf(width, height)
    if (longest <= maxLongSide) return IntSize(width, height)
    val scale = maxLongSide.toFloat() / longest
    return IntSize(
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1)
    )
}

internal object OriginalImageLoader {
    const val TARGET_LONG_SIDE = 2048
    private val maxBytes = minOf(
        64L * 1024 * 1024,
        Runtime.getRuntime().maxMemory() / 8
    ).toInt().coerceAtLeast(8 * 1024 * 1024)

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val target = computeOriginalDecodeSize(info.size.width, info.size.height)
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        cache.put(key, bitmap)
        return bitmap
    }

    fun evict(uri: Uri) {
        cache.remove(uri.toString())
    }

    fun clear() {
        cache.evictAll()
    }
}

/** 双击放大到的目标倍率，与「一屏放不下时的夹取」一起决定锚点平移量 */
internal const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_MS = 220

/**
 * 双击放大时让手指点保持不动所需的平移量。
 * 屏幕位置 = 容器中心 + (内容点-容器中心)×scale + offset ⇒ offset = (中心-手指)×(scale-1)，
 * 再按图片边界夹取：某一轴放大后仍填不满屏幕时，该轴不许偏移（保持居中）。
 */
internal fun doubleTapZoomTarget(
    tap: Offset,
    container: IntSize,
    image: IntSize,
    scale: Float = DOUBLE_TAP_SCALE
): Offset {
    val center = Offset(container.width / 2f, container.height / 2f)
    val raw = (center - tap) * (scale - 1f)
    val max = maxPan(scale, container, image)
    return Offset(raw.x.coerceIn(-max.x, max.x), raw.y.coerceIn(-max.y, max.y))
}

private fun maxPan(scale: Float, container: IntSize, image: IntSize): Offset {
    if (container.width <= 0 || container.height <= 0 ||
        image.width <= 0 || image.height <= 0
    ) return Offset.Zero
    val containerAspect = container.width.toFloat() / container.height
    val imageAspect = image.width.toFloat() / image.height
    val displayedW = if (imageAspect > containerAspect) {
        container.width.toFloat()
    } else {
        container.height * imageAspect
    }
    val displayedH = displayedW / imageAspect
    val maxX = ((displayedW * scale - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((displayedH * scale - container.height) / 2f).coerceAtLeast(0f)
    return Offset(maxX, maxY)
}

@Composable
private fun ForceLightSystemBars() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, view) {
        val window = activity?.window
        if (window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
