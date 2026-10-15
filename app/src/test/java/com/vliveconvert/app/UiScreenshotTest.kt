package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertTrue
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
                appName = "影中影",
                statusText = statusText,
                isConverting = isConverting,
                finishedBeat = false,
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
            mk(13, "IMG_20260831_150200.jpg", 1_754_200_000_000L),
            mk(14, "IMG_20260831_161500.jpg", 1_754_300_000_000L)
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
                appName = "影中影",
                versionName = "1.7.7",
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
            AboutScreen(appName = "影中影", versionName = "1.7.7", onBack = {})
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
                appName = "影中影"
            )
        }
        shot("permission")
    }

    /**
     * 桌面图标（adaptive icon：底色 + 前景两层）。
     * 直接把 mipmap 画到位图上，验证「两张叠照 + 播放三角」构图；
     * 再用圆形遮罩出一版圆预览——应用圆形启动器的桌面（多数国产 ROM）即此观感。
     */
    @Test
    fun shotLauncherIcon() {
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFEEEEEE.toInt())
        val d = compose.activity.getDrawable(com.vliveconvert.app.R.mipmap.ic_launcher)!!
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        // 圆形遮罩预览：位图内切圆以外的区域清透
        val round = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rc = Canvas(round)
        val p = android.graphics.Paint().apply { isAntiAlias = true }
        rc.drawBitmap(bmp, 0f, 0f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = size * 2f
        rc.drawCircle(size / 2f, size / 2f, size / 2f, p)
        val dir = File("build/ui-shots").apply { mkdirs() }
        File(dir, "icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "icon_round.png").outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // 小尺寸实测：图标成不成立，最终看桌面（~48–72px）与通知栏（~26px）那么大时
        // 还认不认得出「一张照片里挖出一个播放形」。只出 432px 大图等于没验。
        for (px in listOf(108, 48, 26)) {
            val small = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val sc = Canvas(small)
            sc.drawColor(0xFFEEEEEE.toInt())
            val sd = compose.activity.getDrawable(com.vliveconvert.app.R.mipmap.ic_launcher)!!
            sd.setBounds(0, 0, px, px)
            sd.draw(sc)
            File(dir, "icon_${px}px.png").outputStream().use {
                small.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }

        // 单色层单独出图：Android 13+ 主题图标只取它的 alpha，彩色层的全部渐变会被抹平。
        // 玻璃塌成剪影之后还认不认得出「一张卡 + 一个挖空三角」，只能这样看见。
        for (px in listOf(432, 48)) {
            val mono = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val mc = Canvas(mono)
            mc.drawColor(0xFF2B3A4A.toInt())
            val md = compose.activity.getDrawable(com.vliveconvert.app.R.drawable.ic_launcher_monochrome)!!
            md.setBounds(0, 0, px, px)
            md.draw(mc)
            File(dir, "icon_monochrome_${px}px.png").outputStream().use {
                mono.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /**
     * 玻璃材质真正的风险不在大图，在桌面/通知栏那么小的时候被抗锯齿洗平。
     *
     * 亮面版把这条断言的**逻辑整个翻了过来**：暗面版靠「卡面比底色亮」立形，
     * 白纸底上这一条根本不成立。原本想改成钉「卡缘那道折射暗线」，但实测把它否了：
     * 2dp 的边在 48px 下只有约 0.9px，3×3 邻域一平均就带不动轮廓。
     * 所以亮版的分离由**玻璃比纸面沉一档的调子差**承担，边线只服务大尺寸观感。
     *
     * 采样系必须写清楚，这里踩过一次坑：**下面全是"显示坐标"，不是图层坐标**。
     * AdaptiveIconDrawable 会把 108dp 图层**放大 1.5 倍**绘制，只有图层中央 72dp
     * （图层坐标 18..90）落在显示范围内，其余被裁掉。拿 XML 里的图层坐标直接采样，
     * 会整批落到卡缘甚至卡外——亮版第一版断言就是这么"量"出「化掉」的假结论的。
     * 换算：显示 = 54 + (图层 − 54) × 1.5。
     *
     * 钉三个关系（E4 苹果式清透玻璃）：
     *   1) 播放形必须显著暗于玻璃身（主符号锚点）
     *   2) 玻璃身必须与底色可辨（清透 ≠ 隐形）
     *   3) 后卡必须与底色可辨（"两张卡"的构图还在）
     * 每个采样点取 3×3 邻域均值——单像素会被抗锯齿边缘骗过去。
     */
    @Test
    fun iconSmallSizeContrast() {
        val px = 48
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFEEEEEE.toInt())
        val d = compose.activity.getDrawable(com.vliveconvert.app.R.mipmap.ic_launcher)!!
        d.setBounds(0, 0, px, px)
        d.draw(canvas)

        val scale = px / 108f
        fun luminance(xDp: Float, yDp: Float): Double {
            var sum = 0.0
            for (dx in -1..1) for (dy in -1..1) {
                val x = (xDp * scale + dx).toInt().coerceIn(0, px - 1)
                val y = (yDp * scale + dy).toInt().coerceIn(0, px - 1)
                val c = bmp.getPixel(x, y)
                sum += 0.2126 * android.graphics.Color.red(c) +
                        0.7152 * android.graphics.Color.green(c) +
                        0.0722 * android.graphics.Color.blue(c)
            }
            return sum / 9.0
        }

        val ground = luminance(54f, 12f)     // 底色：主符号上方（图层约 54,26）
        val body = luminance(45f, 50f)       // 玻璃身左半（图层 48,50，避开三角与 rim 光）
        val mark = luminance(49.5f, 60f)     // 播放形质心（图层 50.8,57.7；(55,54) 在三角上缘之外）
        val back = luminance(86f, 38f)       // 后卡右上角（图层 75.3,42.7）

        println("ICON-CONTRAST ground=$ground body=$body mark=$mark back=$back")

        // E4（苹果式清透玻璃）的三个对比关系，阈值实测后各留 ~40% 余量：
        assertTrue("48px 时播放形必须暗于玻璃身至少 25（实际 body=$body mark=$mark），否则主符号糊掉",
            body > mark + 25)
        assertTrue("48px 时玻璃身必须与底色可辨（实际 body=$body ground=$ground）",
            kotlin.math.abs(body - ground) > 12)
        assertTrue("48px 时后卡必须可辨（实际 back=$back ground=$ground）",
            kotlin.math.abs(back - ground) > 10)
    }

    /**
     * 预测性返回的**中途帧**：两层结构（顶层让位 + 底层相向迎上）用与生产完全相同的
     * predictiveOutgoing / predictiveIncoming 数学，摆出假页面在四个进度下逐帧出图。
     *
     * 为什么不直接驱动真实 Activity：系统手势事件在 Robolectric 里无法合成；
     * 而 Animatable.snapTo 是 suspend，从测试线程碰它会撞主线程模型。
     * 数学是同一个函数，几何手感看帧即可验收（提交/取消的续接逻辑由
     * BackNavigationTest 端到端保证）。
     */
    @Test
    fun shotPredictiveBackFrames() {
        val progress = mutableFloatStateOf(0f)
        render {
            val p = progress.floatValue
            val outgoing = predictiveOutgoing(p, SELF_DRIVE_AMP)
            val incoming = predictiveIncoming(p, SELF_DRIVE_AMP)
            val edge = 1f // 左缘手势：顶层向右让位，揭底层从左迎上
            Box(Modifier.fillMaxSize().background(Color(0xFF0E141B))) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = incoming.scale
                            scaleY = incoming.scale
                            alpha = incoming.alpha
                            translationX = -edge * incoming.shiftFraction * size.width
                        }
                ) { fakePage("返回目标页", Color(0xFF223A5E), Color.White) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = outgoing.scale
                            scaleY = outgoing.scale
                            alpha = outgoing.alpha
                            translationX = edge * outgoing.shiftFraction * size.width
                            translationY = -outgoing.liftFraction * size.height
                            shape = RoundedCornerShape(32.dp * outgoing.cornerFraction)
                            clip = true
                            shadowElevation = 36f * outgoing.cornerFraction
                        }
                ) { fakePage("当前页", Color(0xFF2A41B8), Color.White) }
            }
        }
        for (f in listOf(0f, 0.33f, 0.66f, 1f)) {
            progress.floatValue = f
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            shot("pb_frame_${(f * 100).toInt()}")
        }
        // 自驱动回放帧（amplitude 1.35×）：ROM 无进度事件时的实际观感
        for (f in listOf(0f, 0.5f, 1f)) {
            progress.floatValue = f
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            shot("pb_frame_self_${(f * 100).toInt()}")
        }
    }

    /** 假页面：一个铺满的色块 + 居中标签（几何验收用，内容无关） */
    @Composable
    private fun fakePage(label: String, bg: Color, fg: Color) {
        Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
            Text(label, color = fg, style = MaterialTheme.typography.headlineMedium)
        }
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
                    status = "完成：已导出到 DCIM/Camera", done = true),
                ConvertItem(item = item(4, "IMG_20260831_150200.jpg"),
                    status = "完成：已导出到 DCIM/Camera",
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
