package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vliveconvert.app.service.BatchLogEntry
import com.vliveconvert.app.service.Preflight
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.GlassDialog
import com.vliveconvert.app.ui.LocalReduceMotion
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.rememberGlassHazeState
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * v2.4.0 新 UI 的验收截图（Robolectric 真实渲染，管线同 [UiScreenshotTest]）：
 * 1. 主界面「重试失败 N」按钮 + 完成行的分享磁贴 + 失败行
 * 2. 设置页新开关：减少动效 / 转换记录入口
 * 3. 「转换记录」弹窗（含批次摘要行）
 * 4. 预检弹窗（v2.5.2 重排版）：拦截态（删原图警示 + 空间不足面板）与
 *    常态（保留原图 + 以后不再提示勾选行）
 * 深浅色/字号等全局参数显式钉死，保证截图确定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h792dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V24PolishScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            VLiveConvertTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    content()
                }
            }
        }
    }

    private fun shot(name: String) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val view = compose.activity.window.decorView
        val w = if (view.width > 0) view.width else 1080
        val h = if (view.height > 0) view.height else 2280
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/ui-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun item(id: Long, name: String) = com.vliveconvert.app.picker.MediaItem(
        id = id,
        path = "/storage/emulated/0/DCIM/Camera/$name",
        name = name,
        bucketId = 1L,
        dateTaken = 1_754_000_000_000L + id,
        dateModified = 1_754_000_000L + id,
        size = 1024L * 1024 * 3
    )

    @Test
    fun shotV24Main() {
        val items = listOf(
            ConvertItem(
                item = item(11, "IMG_20260831_194501.jpg"),
                status = "完成：已以原名移入相机相册", done = true,
                outUri = "content://media/external/images/media/101"
            ),
            ConvertItem(
                item = item(12, "IMG_20260831_102233.jpg"),
                status = "失败：源文件已丢失", failed = true
            ),
            ConvertItem(
                item = item(13, "IMG_20260830_181205.jpg"),
                status = "待转换"
            )
        )
        render {
            MainScreen(
                items = items,
                appName = "影中影",
                statusText = "",
                isConverting = false,
                finishedBeat = false,
                progress = 0f,
                progressDetail = "",
                pendingRestoreCount = 0,
                onRestoreOriginals = {},
                locationMissing = false,
                onGrantLocation = {},
                reconvertCount = 0,
                onReconvertLostGps = {},
                onReconvertItem = {},
                outputRelPath = "Pictures/VLiveConvert",
                moveToCamera = true,
                deleteOriginal = true,
                onOpenSettings = {},
                onShowStatusDetail = {},
                onCancelConvert = {},
                onAddMore = {},
                onStartConvert = {},
                onClearAll = {},
                failedCount = 1,
                onRetryFailed = {},
                onShareItem = {},
                onRemove = {}
            )
        }
        shot("v24_main_retry_share")
    }

    @Test
    fun shotV24Settings() {
        render {
            SettingsScreen(
                appName = "影中影",
                versionName = "2.4.0",
                outputRelPath = "Pictures/VLiveConvert",
                moveToCamera = true,
                deleteOriginal = true,
                crashLogCount = 0,
                isBusy = false,
                onBack = {},
                onEditOutputPath = {},
                onToggleMoveToCamera = {},
                onToggleDeleteOriginal = {},
                onOpenFixTime = {},
                onOpenBrokenRepair = {},
                batchLogCount = 3,
                onOpenBatchLog = {},
                onRepairLocationDisplay = {},
                onMoveOutputsToCamera = {},
                onExportCrashLogs = {},
                onClearCrashLogs = {},
                dynamicColor = false,
                onToggleDynamicColor = {},
                reduceMotion = true,
                onToggleReduceMotion = {},
                onOpenAbout = {}
            )
        }
        shot("v24_settings_switches")
    }

    @Test
    fun shotV24BatchLog() {
        val entries = listOf(
            BatchLogEntry(
                timeMs = 1_798_200_000_000L, ok = 12, fail = 0, noGps = 0,
                reconvert = false, deletedOriginals = 12, movedToCamera = 12,
                dest = "DCIM/Camera"
            ),
            BatchLogEntry(
                timeMs = 1_798_100_000_000L, ok = 4, fail = 1, noGps = 2,
                reconvert = false, deletedOriginals = 0, movedToCamera = 0,
                dest = "Pictures/VLiveConvert"
            )
        )
        render {
            GlassDialog(
                hazeState = rememberGlassHazeState(),
                onDismissRequest = {},
                title = "转换记录",
                confirmLabel = "知道了",
                onConfirm = {}
            ) {
                Column {
                    entries.forEachIndexed { i, e ->
                        BatchLogRow(e)
                        if (i < entries.lastIndex) {
                            HorizontalDivider(
                                Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        }
        shot("v24_batch_log")
    }

    @Test
    fun shotV25PreflightGuard() {
        render {
            GlassDialog(
                hazeState = rememberGlassHazeState(),
                onDismissRequest = {},
                title = "转换前确认",
                confirmLabel = "开始转换",
                onConfirm = {},
                dismissLabel = "取消",
                onDismissAction = {}
            ) {
                PreflightContent(
                    info = Preflight.Info(
                        pendingCount = 12, missingSources = 0,
                        totalSourceBytes = 48L * 1024 * 1024, companionVideoCount = 12),
                    usableBytes = 500L * 1024 * 1024, // 500MB < 48MB + 1GB 预留 → 拦截态
                    spaceGuard = Preflight.SpaceGuard.INSUFFICIENT,
                    moveToCamera = true, outputRelPath = "", deleteOriginal = true,
                    hasLocationPermission = true, spaceError = true
                )
            }
        }
        shot("v25_preflight_guard")
    }

    @Test
    fun shotV25PreflightNormal() {
        render {
            GlassDialog(
                hazeState = rememberGlassHazeState(),
                onDismissRequest = {},
                title = "转换前确认",
                confirmLabel = "开始转换",
                onConfirm = {},
                dismissLabel = "取消",
                onDismissAction = {}
            ) {
                PreflightContent(
                    info = Preflight.Info(
                        pendingCount = 8, missingSources = 1,
                        totalSourceBytes = 96L * 1024 * 1024, companionVideoCount = 7),
                    usableBytes = 120L * 1024 * 1024 * 1024,
                    spaceGuard = Preflight.SpaceGuard.OK,
                    moveToCamera = false, outputRelPath = "Pictures/VLiveConvert",
                    deleteOriginal = false, hasLocationPermission = false
                )
            }
        }
        shot("v25_preflight_normal")
    }
}
