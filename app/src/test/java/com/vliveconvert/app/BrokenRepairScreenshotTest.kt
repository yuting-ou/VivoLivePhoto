package com.vliveconvert.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.ui.BrokenRepairScreen
import com.vliveconvert.app.ui.LocalReduceMotion
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
 * 「修复破损实况」页验收截图（管线同 [V24PolishScreenshotTest]）：
 * 真实跑扫描（MediaStore 夹具：两对可修破损实况），渲染到候选列表出现后截图。
 * 边距体系统一（20→16dp）前后各跑一轮，产出可对比的 before/after 图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h792dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrokenRepairScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArray(8 + payload.size)
        BinaryUtils.writeU32BE(b, 0, (payload.size + 8).toLong())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(t, 0, b, 4, 4)
        System.arraycopy(payload, 0, b, 8, payload.size)
        return b
    }

    private fun minimalJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val payload = byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0)
        val seg = ByteArray(2 + 2 + payload.size)
        seg[0] = 0xFF.toByte(); seg[1] = 0xC0.toByte()
        BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
        System.arraycopy(payload, 0, seg, 4, payload.size)
        out.write(seg)
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0))
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x12, 0x34, 0x56))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    /** EXIF APP1（"Exif\0\0" + TIFF(MM) IFD0[Make="vivo"] 内联） */
    private fun vivoExifApp1(): ByteArray {
        val tiff = java.io.ByteArrayOutputStream()
        tiff.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08))
        tiff.write(byteArrayOf(0x00, 0x01))                                  // IFD0 1 项
        tiff.write(byteArrayOf(0x01, 0x0F, 0x00, 0x02, 0x00, 0x00, 0x00, 0x04))
        tiff.write("vivo".toByteArray(Charsets.US_ASCII))                    // 4B 内联
        tiff.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))                      // next IFD
        val payload = JpegUtil.exifPrefix + tiff.toByteArray()
        val seg = ByteArray(4 + payload.size)
        seg[0] = 0xFF.toByte(); seg[1] = 0xE1.toByte()
        BinaryUtils.writeU16BE(seg, 2, payload.size + 2)
        System.arraycopy(payload, 0, seg, 4, payload.size)
        return seg
    }

    /** 破损实况 JPG：JPEG（vivo EXIF）+ streamdata，无 cameralbum! footer */
    private fun brokenJpeg(): ByteArray {
        val base = minimalJpeg()
        val withExif = base.copyOfRange(0, 2) + vivoExifApp1() + base.copyOfRange(2, base.size)
        return withExif + "streamdata".toByteArray(Charsets.US_ASCII) +
            "DEGS".toByteArray(Charsets.US_ASCII) + ByteArray(64) { 0x5A }
    }

    /** 完整实况 MP4：ftyp + mdat + moov（无 vivo uuid；对 stripVivoUuid 安全） */
    private fun liveMp4(): ByteArray =
        box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) +
            "isom".toByteArray() + "mp41".toByteArray()) +
            box("mdat", ByteArray(2048) { 0x33 }) +
            box("moov", ByteArray(0))

    private fun seedFixtures() {
        for (stem in listOf("IMG_20260901_101501", "IMG_20260901_101502")) {
            val jpg = resolver.insert(images, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.MediaColumns.DATE_TAKEN, 1_756_700_000_000L)
            })!!
            resolver.openOutputStream(jpg, "w")?.use { it.write(brokenJpeg()) }
            val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val mp4 = resolver.insert(videos, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            })!!
            resolver.openOutputStream(mp4, "w")?.use { it.write(liveMp4()) }
        }
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
        // 等「发现 N 对破损实况」结果行出现（「重新扫描」按钮常显，不能当扫描完成信号）
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("对破损实况", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(600) // 等扫描收尾与列表布局稳定
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
            BrokenRepairScreen(
                onBack = {},
                hazeState = rememberGlassHazeState()
            )
        }
        waitScanned()
        shot(tag)
    }

    @Test
    fun shotBrokenRepairLight() = screenShot("broken_repair_light", dark = false)

    @Test
    fun shotBrokenRepairDark() = screenShot("broken_repair_dark", dark = true)
}
