package com.vliveconvert.app.ui

import androidx.activity.compose.BackHandler
import dev.chrisbanes.haze.HazeState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.convert.Converter
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.PhotoTime
import com.vliveconvert.app.core.VivoDual
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.service.ConvertedStore
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 修复破损实况（独立入口，议题 #2）：
 * 相机目录里「糊 JPG + 同名 MP4」（前台算法优化被中断的产物）会被扫描出来，
 * 用户手动选择后走与普通实况相同的写出管线合并成单文件实况。
 * 不在普通「转换照片」流程内：选择器仍要求 footer，破损对不会混进正常转换。
 * 安全语义：原文件不删除（含 mp4）；封面无法放大，仍是糊图。
 */
@Composable
fun BrokenRepairScreen(onBack: () -> Unit, hazeState: HazeState = rememberGlassHazeState()) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(true) }
    var scanProgress by remember { mutableStateOf(0 to 0) }
    var candidates by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var fixing by remember { mutableStateOf(false) }
    var fixProgress by remember { mutableStateOf(0f) }
    var summary by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    val startScan: () -> Unit = {
        scanning = true
        scanProgress = 0 to 0
        candidates = emptyList()
        selected = emptySet()
        summary = null
        scope.launch(Dispatchers.IO) {
            val all = MediaRepo(context.contentResolver).queryAllImages()
                .filter { it.path.lowercase().contains("/dcim/camera/") }
            val accepted = mutableListOf<MediaItem>()
            var done = 0
            for (item in all) {
                done++
                val mp4 = VivoDual.siblingMp4(item.path)
                if (mp4 != null && File(mp4).length() > 8L &&
                    VivoDual.isBrokenDualFile(item.path)
                ) accepted.add(item)
                if (done % 200 == 0 || done == all.size) {
                    val d = done
                    withContext(Dispatchers.Main) { scanProgress = d to all.size }
                }
            }
            withContext(Dispatchers.Main) {
                candidates = accepted
                scanning = false
            }
        }
    }

    val startFix: () -> Unit = {
        fixing = true
        fixProgress = 0f
        val targets = candidates.filter { it.path in selected }
        scope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            val tempDir = File(context.cacheDir, "broken_fix").apply { mkdirs() }
            targets.forEachIndexed { index, item ->
                try {
                    val result = Converter.convertToVivoSingle(
                        path = item.path, outDir = tempDir.absolutePath) { _, _, _ -> }
                    val ts = when {
                        item.dateTaken > 0 -> item.dateTaken
                        else -> PhotoTime.parseFromName(item.name)
                            ?: (if (item.dateModified > 0) item.dateModified * 1000L
                            else System.currentTimeMillis())
                    }
                    val uri = MediaExport.exportAndVerify(
                        context, result, ts, "DCIM/Camera", useCameraDir = true)
                    ConvertedStore.record(context, item.path, uri.toString())
                    ok++
                } catch (_: Exception) {
                    fail++
                }
                tempDir.listFiles()?.forEach { f -> try { f.delete() } catch (_: Exception) {} }
                val p = (index + 1).toFloat() / targets.size
                withContext(Dispatchers.Main) { fixProgress = p }
            }
            val msg = "修复成功 $ok 个，失败 $fail 个；原文件未删除，可自行在相册处理。"
            withContext(Dispatchers.Main) {
                fixing = false
                summary = msg
            }
        }
    }

    LaunchedEffect(Unit) { startScan() }
    BackHandler(enabled = !fixing) { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .glassWallpaper()
            .glassBackdropSource(hazeState)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                size = 38.dp
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "修复破损实况",
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onBackground)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(10.dp))
            GlassCard(radius = 24.dp, contentPadding = PaddingValues(16.dp)) {
                Text(
                    "拍摄时连拍或立刻息屏，vivo 来不及做算法优化，会留下「糊 JPG + 同名 MP4」。" +
                        "修复会把 MP4 合并成单文件实况（相册可播放）；封面仍是糊图，分辨率无法放大；" +
                        "原文件不会删除。",
                    style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        scanning -> "扫描中 ${scanProgress.first}/${scanProgress.second}"
                        fixing -> "修复中…"
                        else -> "发现 ${candidates.size} 对破损实况"
                    },
                    style = glassSubtitleStyle(), modifier = Modifier.weight(1f)
                )
            }
            if (scanning) {
                Spacer(Modifier.height(6.dp))
                GlassIndeterminateBar(Modifier.height(3.dp))
            }
            if (fixing) {
                Spacer(Modifier.height(6.dp))
                GlassProgressBar(progress = fixProgress, height = 5.dp, active = true)
            }

            Spacer(Modifier.height(10.dp))
            if (!scanning && candidates.isEmpty()) {
                GlassCard(radius = 24.dp, contentPadding = PaddingValues(16.dp)) {
                    Text(
                        "没有发现破损实况。\n\n只有「糊 JPG + 同名 MP4」的成对文件会出现在这里；" +
                            "如果 MP4 缺失或被截断，无法修复。",
                        style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                GlassCard(radius = 24.dp, contentPadding = PaddingValues(vertical = 6.dp)) {
                    candidates.forEach { item ->
                        val checked = item.path in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(width = 46.dp, height = 46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            ) {
                                MediaThumbnail(item.uri, resolver, Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    "JPG ${item.size / 1024 / 1024}MB · MP4 ${
                                        (VivoDual.siblingMp4(item.path)?.let { File(it).length() }
                                            ?: 0L) / 1024 / 1024}MB",
                                    style = TextStyle(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            CircleTriCheckbox(
                                state = if (checked) androidx.compose.ui.state.ToggleableState.On
                                else androidx.compose.ui.state.ToggleableState.Off,
                                onClick = {
                                    selected = if (checked) selected - item.path
                                    else selected + item.path
                                },
                                contentDescription = "选择 ${item.name}"
                            )
                        }
                        GlassDivider(startInset = 12.dp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassOutlineButton(
                        text = "重新扫描",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = { if (!fixing) startScan() }
                    )
                    GlassOutlineButton(
                        text = "修复所选（${selected.size}）",
                        color = if (selected.isNotEmpty() && !fixing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                        onClick = { if (selected.isNotEmpty() && !fixing) confirm = true }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirm) {
        GlassDialog(
            hazeState = hazeState,
            onDismissRequest = { confirm = false },
            title = "修复所选（${selected.size}）",
            confirmLabel = "开始修复",
            onConfirm = {
                confirm = false
                startFix()
            },
            dismissLabel = "取消",
            onDismissAction = { confirm = false }
        ) {
            Text(
                "会把伴生 MP4 合并成单文件实况（相册可播放）；封面仍是糊图；" +
                    "原 JPG 与 MP4 都不会删除。"
            )
        }
    }
    summary?.let { msg ->
        GlassDialog(
            hazeState = hazeState,
            onDismissRequest = { summary = null },
            title = "修复完成",
            confirmLabel = "知道了",
            onConfirm = { summary = null }
        ) {
            Text(msg)
        }
    }
}
