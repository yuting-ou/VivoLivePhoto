package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 主队列 列表/网格 双模式对比截图（无断言，供人工过目；产物 build/ui-shots/）。
 *  NATIVE 图形的 zipfs/native 加载竞态在全量 fork 顺序下偶发、单跑全绿——
 *  环境性闪红由 Gradle test-retry 插件复跑一次兜底（每次 attempt 重建 rule，
 *  不能在 rule 内重放 Statement：compose rule 的 runTest 只允许调一次）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h792dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenViewModeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = compose.activity.window.decorView
        val w = if (view.width > 0) view.width else 1080
        val h = if (view.height > 0) view.height else 2280
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/ui-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun items(n: Int) = (1..n).map { i ->
        ConvertItem(item = MediaItem(
            i.toLong(), "/sdcard/DCIM/Camera/IMG_$i.jpg", "IMG_$i.jpg",
            1L, 1_754_000_000_000L + i, 1_754_000_000L + i, 1024L * 1024 * 3),
            status = if (i == 2) "完成：已导出到 DCIM/Camera" else "待转换",
            done = i == 2)
    }

    private fun setContent() {
        compose.setContent {
            VLiveConvertTheme(dynamicColor = false) {
                MainScreen(
                    items = items(9),
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
                    failedCount = 0,
                    onRetryFailed = {},
                    onShareItem = {},
                    onRemove = {}
                )
            }
        }
        compose.waitForIdle()
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<VliveApp>()
        .getSharedPreferences("vliveconvert", 0)

    @Test
    fun shotList() {
        prefs().edit().remove("main_view_mode").commit()
        setContent()
        shot("main_view_list")
        prefs().edit().remove("main_view_mode").commit()
    }

    @Test
    fun shotGrid() {
        prefs().edit().putString("main_view_mode", "grid").commit()
        setContent()
        shot("main_view_grid")
        prefs().edit().remove("main_view_mode").commit()
    }
}