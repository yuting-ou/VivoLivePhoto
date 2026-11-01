package com.vliveconvert.app

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.ui.LocalReduceMotion
import com.vliveconvert.app.ui.LocationRecoveryScreen
import com.vliveconvert.app.ui.rememberGlassHazeState
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 「找回位置」页的验收截图（管线同 [V24PolishScreenshotTest]）：
 * 真实跑扫描（Robolectric MediaStore 夹具 + 真实列读校准），渲染到「需修复」
 * 分组出现后截图。视觉重做前后各跑一轮，产出可对比的 before/after 图
 * （输出目录 build/ui-shots/，交付时复制到 build-outputs/screenshots/）。
 *
 * 夹具形态：3 张「需修复」（文件无 EXIF、视频段带坐标——真机丢位置人群形态）、
 * 2 张「无位置」、1 张「已正常」（默认隐藏，仅计数）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h792dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationRecoveryScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    private fun jpegWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg)

    private fun productNoCoords(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun productVideoOnly(): ByteArray {
        val xyzTail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        return productNoCoords() + xyzTail
    }

    private fun putImage(name: String, bytes: ByteArray, coords: Pair<Double, Double>? = null) {
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.MediaColumns.DATE_TAKEN, 1_754_000_000_000L)
            if (coords != null) {
                put("latitude", coords.first)
                put("longitude", coords.second)
            }
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
    }

    private fun seedFixtures() {
        putImage("IMG_20260831_194501.jpg", productVideoOnly()) // 需修复
        putImage("IMG_20260831_102233.jpg", productVideoOnly()) // 需修复
        putImage("IMG_20260830_181205.jpg", productVideoOnly()) // 需修复
        putImage("IMG_20260829_211407.jpg", productNoCoords())  // 无位置
        putImage("IMG_20260828_093012.jpg", productNoCoords())  // 无位置
        putImage("IMG_20260827_170844.jpg",                      // 已正常（隐藏）
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
                com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
                jpegWithGps(22, 108).drop(2).toByteArray(),
            coords = 22.0 to 108.0)
    }

    private fun render(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            VLiveConvertTheme(darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    content()
                }
            }
        }
    }

    private fun waitScanned() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("需修复（3）").fetchSemanticsNodes().isNotEmpty()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300) // 等列表布局稳定
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

    private fun screenShot(tag: String, dark: Boolean) {
        seedFixtures()
        render(dark) {
            LocationRecoveryScreen(
                locationGranted = true,
                onGrantLocation = {},
                onBack = {},
                hazeState = rememberGlassHazeState()
            )
        }
        waitScanned()
        shot(tag)
    }

    @Test
    fun shotRecoveryLight() = screenShot("recovery_light", dark = false)

    @Test
    fun shotRecoveryDark() = screenShot("recovery_dark", dark = true)

    /** 无障碍大字号（1.5×）：验证工具行/分段头/Dock 不撑破、不裁切 */
    @Test
    fun shotRecoveryLargeFont() {
        seedFixtures()
        compose.setContent {
            VLiveConvertTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(
                    LocalReduceMotion provides true,
                    LocalDensity provides Density(density = 3f, fontScale = 1.5f)
                ) {
                    LocationRecoveryScreen(
                        locationGranted = true,
                        onGrantLocation = {},
                        onBack = {},
                        hazeState = rememberGlassHazeState()
                    )
                }
            }
        }
        waitScanned()
        shot("recovery_largefont")
    }
}
