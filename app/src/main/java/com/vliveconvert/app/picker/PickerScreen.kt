package com.vliveconvert.app.picker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.ui.CircleTriCheckbox
import com.vliveconvert.app.ui.GlassDivider
import com.vliveconvert.app.ui.HazeGlassCard
import com.vliveconvert.app.ui.glassBackdropSource
import com.vliveconvert.app.ui.glassSurface
import com.vliveconvert.app.ui.glassWallpaper
import com.vliveconvert.app.ui.rememberGlassHazeState
import dev.chrisbanes.haze.HazeState
import com.vliveconvert.app.ui.MediaThumbnail
import com.vliveconvert.app.ui.clickableNoRipple
import com.vliveconvert.app.ui.dateKey
import com.vliveconvert.app.ui.dateLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * 内置双文件实况照片选择器。
 *
 * 数据源为 MediaStore 直查 + PickerScanner 会话化多线程扫描，
 * 仅显示「.jpg + 同目录同名 .mp4」的 vivo 双文件实况照片。
 */
@Composable
fun PickerScreen(
    albums: List<AlbumInfo>,
    scanner: PickerScanner,
    scannerScope: CoroutineScope,
    onBack: () -> Unit,
    onConfirm: (List<MediaItem>) -> Unit,
    hazeState: HazeState = rememberGlassHazeState()
) {
    var currentBucketId by remember { mutableStateOf(albums.firstOrNull()?.bucketId) }
    var showAlbumMenu by remember { mutableStateOf(false) }
    val bucketId = currentBucketId
    val selected = remember { mutableStateListOf<MediaItem>() }
    // 选择查询表：每个格子/日期头若都线性扫 selected，八千张规模就是 O(n²)；
    // 真机全选 8550 张会把主线程打到 ANR。派生一次、全局 O(1) 查询。
    val selectedIds by remember {
        derivedStateOf { selected.mapTo(HashSet(selected.size)) { it.id } }
    }
    val selectedIndex by remember {
        derivedStateOf { selected.withIndex().associate { it.value.id to it.index } }
    }

    // 进入/离开相册：进入 diff+续扫，离开取消（保留进度）
    DisposableEffect(bucketId) {
        if (bucketId != null) scanner.enter(bucketId, scannerScope)
        onDispose { if (bucketId != null) scanner.leave(bucketId) }
    }

    val state = remember(bucketId) { if (bucketId != null) scanner.stateOf(bucketId) else null }
    val results = state?.results
    val total = state?.total ?: 0
    // 进度轮询（done/running 为普通并发变量，需轮询驱动重组）；
    // 扫描完成后终止轮询，避免空转耗电（重新进入扫描会生成新 state 自动重启轮询）
    val progress by produceState(0 to false, state) {
        val s = state ?: return@produceState // 无相册：不轮询（否则永不完成、每 200ms 空转）
        while (true) {
            val doneAll = s.completed
            // running 与 completed 是两个独立变量：收尾瞬间可能先读到 running=true、
            // 再读到 completed=true，若直接用 running 会让 spinner 永久卡在「扫描中」，
            // 故以 !completed 兜底
            value = s.doneCount.get() to (s.running && !doneAll)
            if (doneAll) break
            delay(200)
        }
    }
    val running = progress.second

    // 网格显示顺序：固定按日期降序
    val n = results?.size ?: 0
    val displayOrder = remember(results, n) {
        results.orEmpty().sortedByDescending { it.sortTime }
    }

    // 单项选择切换（追加到末尾，序号递增）
    val toggleItem: (MediaItem) -> Unit = { item ->
        val idx = selectedIndex[item.id]
        if (idx != null) selected.removeAt(idx) else selected.add(item)
    }
    // 成组选择切换（日期栏/全选）：组内全选 → 全部取消；否则按传入顺序补齐未选
    val toggleGroup: (List<MediaItem>) -> Unit = { group ->
        if (group.isNotEmpty()) {
            if (group.all { it.id in selectedIds }) {
                val ids = group.mapTo(HashSet(group.size)) { it.id }
                selected.removeAll { it.id in ids }
            } else {
                // 一次 addAll：逐张 add 会触发 N 次快照通知与重组，
                // 是「全选九千张」卡死的放大器
                val toAdd = group.filter { it.id !in selectedIds }
                if (toAdd.isNotEmpty()) selected.addAll(toAdd)
            }
        }
    }

    val density = LocalDensity.current
    // 初始给一个估值，卡片测量后校正；避免首帧网格从顶部跳下来
    var headerHeight by remember { mutableStateOf(104.dp) }

    val gridState = remember(bucketId) { LazyGridState() }
    // 日期分段：照片必须按天断开，只靠一个浮动日期标签会全部堆在一起。
    // groupBy 保留插入顺序，displayOrder 已按时间倒序，所以分段顺序也正确。
    val sections = remember(displayOrder) {
        displayOrder.groupBy { dateKey(it.sortTime) }
            .map { (key, items) -> PickerSection(key, items) }
    }

    Box(Modifier.fillMaxSize()) {
        // 壁纸 + 网格是 Haze 的采样源；浮动导航不在源里，
        // 否则玻璃会把自己也采进背景，形成反馈
        Box(
            Modifier
                .fillMaxSize()
                .glassWallpaper()
                .glassBackdropSource(hazeState)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                when {
                    results == null -> Box(
                        Modifier.fillMaxSize().padding(top = headerHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("没有可用相册", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    results.isEmpty() && !running -> Column(
                        Modifier.fillMaxSize()
                            .padding(top = headerHeight + 14.dp, bottom = 48.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("没有找到双文件实况照片", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("拍摄时请保持相机的「实况」开关开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> AlbumGridPage(
                        sections = sections,
                        gridState = gridState,
                        selectedIds = selectedIds,
                        selectedIndex = selectedIndex,
                        onToggle = toggleItem,
                        onToggleGroup = toggleGroup,
                        running = running,
                        contentPadding = PaddingValues(
                            top = headerHeight + 12.dp, bottom = 10.dp)
                    )
                }
            }
        }

        // ── 悬浮玻璃导航：取消 / 标题（点按切换相册）/ 添加 + 全选工具行 ──
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
        ) {
            HazeGlassCard(
                hazeState = hazeState,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        val h = with(density) { coords.size.height.toDp() }
                        if (h != headerHeight) headerHeight = h
                    },
                radius = 28.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "取消",
                            style = TextStyle(fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickableNoRipple(onBack)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        val primary = MaterialTheme.colorScheme.primary
                        Box(
                            Modifier
                                .heightIn(min = 32.dp)
                                .clip(CircleShape)
                                .shadow(10.dp, CircleShape, clip = false,
                                    ambientColor = primary.copy(alpha = 0.35f),
                                    spotColor = primary.copy(alpha = 0.28f))
                                .background(Brush.verticalGradient(listOf(
                                    lerp(primary, Color.White, 0.18f), primary)))
                                .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                                .clickable { onConfirm(selected.toList()) }
                                .padding(horizontal = 14.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("添加", style = TextStyle(fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary))
                        }
                    }
                    Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                        val albumName = albums.firstOrNull { it.bucketId == bucketId }?.name
                            ?: "相册"
                        val count = results?.size ?: 0
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickableNoRipple(onClick = {
                                if (albums.size > 1) showAlbumMenu = true
                            })
                        ) {
                            Text("选择照片", style = TextStyle(fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "$albumName · $count 张",
                                    style = TextStyle(fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                if (albums.size > 1) {
                                    Icon(Icons.Filled.KeyboardArrowDown, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        DropdownMenu(expanded = showAlbumMenu,
                            onDismissRequest = { showAlbumMenu = false }) {
                            albums.forEach { album ->
                                DropdownMenuItem(
                                    text = { Text("${album.name} · ${album.count} 张") },
                                    onClick = {
                                        currentBucketId = album.bucketId
                                        showAlbumMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                GlassDivider()
                Spacer(Modifier.height(4.dp))
                // ── 工具行：张数（左） + 已选 + 全选（右） ──
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (running) "扫描中 ${progress.first}/$total"
                        else "${results?.size ?: 0} 张双文件实况",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    // 选中计数必须是独立文本节点：批量选择测试与无障碍朗读都按它验收
                    if (selected.isNotEmpty()) {
                        Text("已选 ${selected.size} 张", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                    }
                    val allSel = n > 0 && displayOrder.all { it.id in selectedIds }
                    val someSel = displayOrder.any { it.id in selectedIds }
                    // 文字与圆圈都算作点击目标：只让圆圈可点会让人误以为点了没反应
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickableNoRipple(onClick = { if (n > 0) toggleGroup(displayOrder) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("全选", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(4.dp))
                        CircleTriCheckbox(
                            state = when {
                                allSel -> ToggleableState.On
                                someSel -> ToggleableState.Indeterminate
                                else -> ToggleableState.Off
                            },
                            onClick = { if (n > 0) toggleGroup(displayOrder) }
                        )
                    }
                }
                if (running) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * 单个相册的网格页：按日期分段铺照片。
 * 日期标题随内容滚动、不做粘性——粘性标题会被浮动导航整条盖住；
 * 非粘性标题既能清楚分隔每天，又不会永久遮照片。
 */
private data class PickerSection(val key: String, val items: List<MediaItem>)

@Composable
private fun AlbumGridPage(
    sections: List<PickerSection>,
    gridState: LazyGridState,
    selectedIds: Set<Long>,
    selectedIndex: Map<Long, Int>,
    onToggle: (MediaItem) -> Unit,
    onToggleGroup: (List<MediaItem>) -> Unit,
    running: Boolean,
    contentPadding: PaddingValues = PaddingValues()
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        sections.forEach { section ->
            item(
                key = "hdr_${section.key}",
                span = { GridItemSpan(3) },
                contentType = "header"
            ) {
                val allSel = section.items.all { it.id in selectedIds }
                val someSel = section.items.any { it.id in selectedIds }
                DateSectionHeader(
                    label = dateLabel(section.items.first().sortTime),
                    count = section.items.size,
                    checkState = when {
                        allSel -> ToggleableState.On
                        someSel -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    },
                    onToggleAll = { onToggleGroup(section.items) }
                )
            }
            items(section.items, key = { it.key }, contentType = { "photo" }) { item ->
                GridCell(
                    item = item,
                    selected = item.id in selectedIds,
                    selectionIndex = selectedIndex[item.id] ?: -1,
                    onToggle = { onToggle(item) }
                )
            }
        }
        // 扫描中：网格底部加载圈
        if (running) {
            item(span = { GridItemSpan(3) }, contentType = "progress") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                }
            }
        }
    }
}

/** 日期分段标题：日期 + 当天张数，右侧圆圈可整天全选/取消 */
@Composable
private fun DateSectionHeader(
    label: String,
    count: Int,
    checkState: ToggleableState,
    onToggleAll: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("$count 张", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CircleTriCheckbox(
            state = checkState,
            onClick = onToggleAll,
            contentDescription = "全选 $label 的照片"
        )
    }
}

/** 相册 chip */
@Composable
private fun AlbumChip(
    album: AlbumInfo,
    selected: Boolean,
    onClick: () -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                val coverUri = android.net.Uri.parse(
                    android.provider.MediaStore.Images.Media.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL).toString() + "/${album.coverId}"
                )
                MediaThumbnail(coverUri, resolver, Modifier.fillMaxSize())
            }
            Column {
                Text(
                    album.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${album.count} 张",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 网格单元：勾选徽章在右上角；选中以几何中心缩放 + 圆角增大过渡。
 */
@Composable
private fun GridCell(item: MediaItem, selected: Boolean, selectionIndex: Int, onToggle: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val transition = updateTransition(selected, label = "cell")

    // 选中时以几何中心为定点缩小
    val scale by transition.animateFloat(label = "scale") { if (it) 0.86f else 1f }
    // 圆角：未选 6dp、选中 16dp；以 0..1 分数动画映射 Dp
    val cornerFraction by transition.animateFloat(label = "cornerF") { if (it) 1f else 0f }
    val cornerRadius = lerp(10.dp, 16.dp, cornerFraction)

    // 徽章背景色：透明→primary（颜色平滑过渡）
    val badgeBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(200), label = "badgeBg"
    )
    // 徽章以几何中心为基准的弹簧缩放
    val badgeScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "badgeScale"
    )

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
    ) {
        // 内层图片：中心缩放 + 圆角/边框动画
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .clip(RoundedCornerShape(cornerRadius))
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(cornerRadius)
                )
        ) {
            MediaThumbnail(item.uri, resolver, Modifier.fillMaxSize())
        }

        // 实况标记（左下角小圆点），提示这是动态照片
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .size(8.dp)
                .background(Color.White.copy(alpha = 0.9f), CircleShape)
        )

        // 勾选徽章（右上角）：选中时 primary 圆底 + 白色序号，未选中空心圈
        val digits = if (selected) (selectionIndex + 1).toString().length else 1
        val badgeSize = when {
            digits >= 3 -> 28.dp
            digits == 2 -> 24.dp
            else -> 22.dp
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp)
                .size(badgeSize)
                .graphicsLayer {
                    scaleX = badgeScale
                    scaleY = badgeScale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .background(badgeBg, CircleShape)
                .border(
                    width = if (selected) 1.dp else 0.dp,
                    color = Color.White.copy(alpha = 0.55f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = if (selected) selectionIndex else -1,
                transitionSpec = {
                    (scaleIn(
                        initialScale = 0.5f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(tween(200))) togetherWith
                            (scaleOut(
                                targetScale = 0.5f,
                                animationSpec = tween(150)
                            ) + fadeOut(tween(150)))
                },
                label = "badge"
            ) { idx ->
                if (idx >= 0) {
                    Text(
                        text = "${idx + 1}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Box(
                        Modifier
                            .size(16.dp)
                            .graphicsLayer { alpha = 0.8f }
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                }
            }
        }
    }
}
