package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vliveconvert.app.picker.AlbumInfo
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.PickerScanner
import com.vliveconvert.app.picker.PickerScreen
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

/** 选择器玻璃化后的真机同宽截图（无断言，供人工验收） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h792dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shotPickerSelected() {
        val bucket = 7L
        val scanner = PickerScanner(MediaRepo(compose.activity.contentResolver))
        val state = scanner.stateOf(bucket)
        val items = (1L..24L).map { id ->
            MediaItem(
                id = id, path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
                name = "IMG_$id.jpg", bucketId = bucket,
                dateTaken = 1_754_000_000_000L + id * 60_000L,
                dateModified = 1_754_000_000L + id, size = 1024L
            )
        }
        state.results.addAll(items)
        state.total = items.size
        state.doneCount.set(items.size)
        state.completed = true

        compose.setContent {
            VLiveConvertTheme(dynamicColor = false) {
                PickerScreen(
                    albums = listOf(AlbumInfo(bucket, "Camera", items.size, items.first().id, 0L)),
                    scanner = scanner,
                    scannerScope = CoroutineScope(Dispatchers.Unconfined),
                    onBack = {},
                    onConfirm = {}
                )
            }
        }
        compose.onNodeWithText("全选").performClick()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        val view = compose.activity.window.decorView
        val w = if (view.width > 0) view.width else 1080
        val h = if (view.height > 0) view.height else 2376
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/ui-shots").apply { mkdirs() }
        File(dir, "picker_glass.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
