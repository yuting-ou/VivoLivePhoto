package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vliveconvert.app.service.PerfPreset
import com.vliveconvert.app.service.PerfSettings
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 设置「性能」区 自动挡/手动挡 对比截图（无断言，供人工过目；产物 build/ui-shots/）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1500dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPerfScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, perf: PerfSettings) {
        compose.setContent {
            VLiveConvertTheme(dynamicColor = false) {
                SettingsScreen(
                    appName = "影中影",
                    versionName = "2.8.0-test1",
                    statusText = "",
                    outputRelPath = "DCIM/Camera",
                    moveToCamera = true,
                    deleteOriginal = true,
                    perf = perf,
                    perfAutoDetail = "当前：并发 2 路 · 内存预算约 230 MB",
                    deviceInfo = "本机 8 核 · 应用堆约 512 MB（手动上限可到 358 MB）",
                    onPerfChange = {},
                    crashLogCount = 0,
                    isBusy = false,
                    onBack = {},
                    onEditOutputPath = {},
                    onToggleMoveToCamera = {},
                    onToggleDeleteOriginal = {},
                    onOpenFixTime = {},
                    onOpenBrokenRepair = {},
                    onRepairLocationDisplay = {},
                    onMoveOutputsToCamera = {},
                    onExportCrashLogs = {},
                    onClearCrashLogs = {},
                    dynamicColor = false,
                    onToggleDynamicColor = {},
                    onOpenAbout = {}
                )
            }
        }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/ui-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun shotPerfAuto() {
        shot("settings_perf_auto", PerfSettings(manual = false))
    }

    @Test
    fun shotPerfManual() {
        shot("settings_perf_manual", PerfPreset.Balanced.apply().copy(manual = true))
    }
}