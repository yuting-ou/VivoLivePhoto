package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vliveconvert.app.picker.AlbumInfo
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.SingleLiveScanner
import com.vliveconvert.app.ui.AboutScreen
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.FixTimeScreen
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.PermissionScreen
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * UI 截图（仅用于人工目视检查，不做断言）。
 *
 * 本机没有真机、也没有可硬件加速的模拟器（无 /dev/kvm），故用 Robolectric 的**原生图形**
 * 把 Compose 真实渲染成 PNG 再逐张看——这是唯一能确认「看起来对不对」的手段
 * （此前所有 UI 改动都只靠推理，未经目视）。
 *
 * 产物：app/build/ui-shots/ 目录下的 png 文件
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun render(content: @Composable () -> Unit) {
        compose.setContent { VLiveConvertTheme { content() } }
    }

    /**
     * 直接绘制 Activity 的视图树到 Bitmap。
     * 不用 `captureToImage()`：它走窗口 PixelCopy，在 Robolectric 下会卡在
     * 「等待重绘」并抛 ComposeTimeoutException；原生图形模式下 `view.draw(Canvas)`
     * 能拿到真实的 Skia 渲染结果。
     */
    private fun shot(name: String) {
        shadowOf(Looper.getMainLooper()).idle()
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

    private fun item(id: Long, name: String) = MediaItem(
        id = id,
        path = "/storage/emulated/0/DCIM/Camera/$name",
        name = name,
        bucketId = 1L,
        dateTaken = 1_754_000_000_000L + id,
        dateModified = 1_754_000_000L + id,
        size = 1024L * 1024 * 3
    )

    @Suppress("LongParameterList")
    private fun mainScreen(
        items: List<ConvertItem>,
        statusText: String = "",
        locationMissing: Boolean = false,
        reconvertCount: Int = 0,
        pendingRestoreCount: Int = 0,
        isConverting: Boolean = false
    ) {
        render {
            MainScreen(
                items = items,
                appName = "Vivo Live Photo",
                statusText = statusText,
                isConverting = isConverting,
                progress = 0.42f,
                progressDetail = "已处理 5/12",
                pendingRestoreCount = pendingRestoreCount,
                onRestoreOriginals = {},
                locationMissing = locationMissing,
                onGrantLocation = {},
                reconvertCount = reconvertCount,
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
                onRemove = {}
            )
        }
    }

    /**
     * 修复时间页：验证 v1.6 加过、但一直没目视过的「现 MM-dd HH:mm → MM-dd HH:mm」格子。
     * 用真实临时文件保证「现」列有值（该列取物理 mtime）。
     */
    @Test
    fun shotFixTime() {
        val bucket = 1L
        val dir = File("build/ui-shots/fixtime-src").apply { mkdirs() }
        fun mk(id: Long, name: String, mtime: Long): MediaItem {
            val f = File(dir, name).apply { writeBytes(ByteArray(32)) }
            f.setLastModified(mtime)
            return MediaItem(id, f.absolutePath, name, bucket, 0L, mtime / 1000, 32L)
        }
        // 文件名里的时间 = 目标；mtime = 当前（两者不一致才会被收录）
        val items = listOf(
            mk(11, "IMG_20260831_134432.jpg", 1_754_000_000_000L),
            mk(12, "IMG_20260831_140512.jpg", 1_754_100_000_000L),
            mk(13, "IMG_20260831_150200.jpg", 1_754_200_000_000L)
        )
        val scanner = SingleLiveScanner(MediaRepo(compose.activity.contentResolver))
        val st = scanner.stateOf(bucket)
        st.results.addAll(items)
        st.total = items.size
        st.doneCount.set(items.size)
        st.completed = true // 让 enter() 提前返回，保留预置结果

        render {
            FixTimeScreen(
                albums = listOf(AlbumInfo(bucket, "Camera", items.size, items.first().id, 0L)),
                scanner = scanner,
                scannerScope = CoroutineScope(Dispatchers.Unconfined),
                isFixing = false,
                progress = 0f,
                statusText = "",
                selectionReset = 0,
                onBack = {},
                onFix = {}
            )
        }
        shot("fixtime")
    }

    /** 设置页（含输出目录取值、两个开关、工具与关于入口） */
    @Test
    fun shotSettings() {
        render {
            SettingsScreen(
                appName = "Vivo Live Photo",
                versionName = "1.7.2",
                outputRelPath = "Pictures/VLiveConvert",
                moveToCamera = true,
                deleteOriginal = true,
                crashLogCount = 2,
                isBusy = false,
                onBack = {},
                onEditOutputPath = {},
                onToggleMoveToCamera = {},
                onToggleDeleteOriginal = {},
                onOpenFixTime = {},
                onMoveOutputsToCamera = {},
                onExportCrashLogs = {},
                onOpenAbout = {}
            )
        }
        shot("settings")
    }

    /** 关于页（GPL 许可） */
    @Test
    fun shotAbout() {
        render {
            AboutScreen(appName = "Vivo Live Photo", versionName = "1.7.2", onBack = {})
        }
        shot("about")
    }

    /** 权限引导页（无动画，用于验证截图管线本身是否可用） */
    @Test
    fun shotPermission() {
        render {
            PermissionScreen(
                onRequest = {},
                statusText = "",
                appName = "Vivo Live Photo"
            )
        }
        shot("permission")
    }

    /** 空状态 */
    @Test
    fun shotMainEmpty() {
        mainScreen(items = emptyList())
        shot("main_empty")
    }

    /** 有内容：正常 / 完成 / 丢位置 / 失败 / 转换中 五种状态 + 两条横幅 */
    @Test
    fun shotMainWithItems() {
        mainScreen(
            items = listOf(
                ConvertItem(item = item(1, "IMG_20260831_134432.jpg"), status = "待转换"),
                ConvertItem(item = item(2, "IMG_20260831_140512.jpg"),
                    status = "转换中…"),
                ConvertItem(item = item(3, "IMG_20260831_141030.jpg"),
                    status = "完成：已导出到相册 DCIM/Camera", done = true),
                ConvertItem(item = item(4, "IMG_20260831_150200.jpg"),
                    status = "完成：已导出到相册 DCIM/Camera",
                    done = true, lostGps = true, outUri = "content://media/external/images/media/9"),
                ConvertItem(item = item(5, "IMG_20260831_161500.jpg"),
                    status = "失败：内存不足（文件过大），请减少单批数量后重试", failed = true)
            ),
            statusText = "转换完成：成功 2 个，失败 1 个（输出目录：DCIM/Camera）",
            locationMissing = true,
            reconvertCount = 1,
            pendingRestoreCount = 3
        )
        shot("main_items")
    }

    /** 转换中 */
    @Test
    fun shotMainConverting() {
        mainScreen(
            items = listOf(
                ConvertItem(item = item(1, "IMG_20260831_134432.jpg"), status = "转换中…"),
                ConvertItem(item = item(2, "IMG_20260831_140512.jpg"), status = "待转换")
            ),
            statusText = "正在转换实况照片 1/2",
            isConverting = true
        )
        shot("main_converting")
    }
}
