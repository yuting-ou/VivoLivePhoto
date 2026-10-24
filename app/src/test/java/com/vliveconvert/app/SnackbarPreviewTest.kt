package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.ThemedSnackbar
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 撤销条主题化的目视预览（360dp，与真机同宽）。
 * 默认 M3 Snackbar 是反相近黑色，与莫奈浅色卡片体系割裂；
 * 本用例把主题化后的浮层渲染成 PNG，供人工确认（无断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h792dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SnackbarPreviewTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeData(message: String) : SnackbarData {
        override val visuals: SnackbarVisuals = object : SnackbarVisuals {
            override val message = message
            override val actionLabel = "撤销"
            override val withDismissAction = true
            override val duration = SnackbarDuration.Long
        }
        override fun performAction() {}
        override fun dismiss() {}
    }

    private fun item(id: Long, name: String) = MediaItem(
        id = id, path = "/storage/emulated/0/DCIM/Camera/$name", name = name,
        bucketId = 1L, dateTaken = 1_754_000_000_000L + id,
        dateModified = 1_754_000_000L + id, size = 1024L * 1024 * 3
    )

    private fun render(content: @Composable () -> Unit) {
        compose.setContent { VLiveConvertTheme(dynamicColor = false) { content() } }
    }

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

    @Test
    fun previewClearUndo() {
        render {
            Box(Modifier.fillMaxSize()) {
                MainScreen(
                    items = listOf(
                        ConvertItem(item = item(1, "IMG_20261025_012656.jpg"), status = "待转换"),
                        ConvertItem(item = item(2, "IMG_20260422_210246.jpg"), status = "待转换"),
                        ConvertItem(item = item(3, "IMG_20260111_194131.jpg"), status = "待转换")
                    ),
                    appName = "影中影",
                    statusText = "已清空",
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
                    onRemove = {}
                )
                ThemedSnackbar(
                    snackbarData = FakeData("已清空 8550 张"),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 160.dp, start = 16.dp, end = 16.dp)
                )
            }
        }
        shot("snackbar_themed")
    }
}
