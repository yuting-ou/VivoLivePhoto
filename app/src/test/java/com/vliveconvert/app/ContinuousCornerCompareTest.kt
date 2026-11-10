package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.vliveconvert.app.ui.continuousShape
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 圆角几何对照：标准圆弧（G1 切点突变）vs 苹果连续曲率（G2 平滑），供人工过目。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h360dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuousCornerCompareTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, shape: Shape) {
        compose.setContent {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFFFF3B8B), Color(0xFF3B6BFF))))
            ) {
                Box(
                    Modifier.align(Alignment.TopStart).size(300.dp)
                        .clip(shape).background(Color.White)
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
    fun shotCornerOld() = shot("corner_circular", RoundedCornerShape(64.dp))

    @Test
    fun shotCornerContinuous() = shot("corner_continuous", continuousShape(64.dp))
}