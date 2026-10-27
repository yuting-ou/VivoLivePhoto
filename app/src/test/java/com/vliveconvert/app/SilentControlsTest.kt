package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「控件点了必须有回应」的回归测试（非 UI 那半）。
 *
 * 这类缺陷的共同形状：`if (busy) return` 把一次点击吞掉，界面上没有任何一处解释
 * 为什么按不动 —— 用户视角就是「点了没反应 / 卡死」。「开始转换」主按钮（v1.7.10 已修）、
 * 修复时间入口与「移动输出到相册」都是同一个病根。UI 那半见 [SettingsActionRowClickTest]。
 *
 * 本类刻意**不挂** compose 测试规则：手工 buildActivity 已经各建一棵 Compose 树，
 * 再加规则会在默认 512m 的测试堆里挤出 OutOfMemoryError（那会被误读成产品缺陷）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilentControlsTest {

    private val openActivities = mutableListOf<ActivityController<MainActivity>>()

    /** 用例结束必须 destroy：只 finish() 会让 Compose 树与 Recomposer 常驻，整套跑下来挤爆测试堆 */
    @After
    fun closeActivities() {
        openActivities.forEach { runCatching { it.pause().stop().destroy() } }
        openActivities.clear()
    }

    private fun launchMain(): MainActivity {
        val app = ApplicationProvider.getApplicationContext<VliveApp>()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        openActivities.add(controller)
        return controller.get()
    }

    /** `by mutableStateOf` 委托字段带 $delegate 后缀 */
    private fun field(a: MainActivity, name: String): Any? {
        val cls = MainActivity::class.java
        return try {
            cls.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(a)
        } catch (_: NoSuchFieldException) {
            cls.getDeclaredField(name).apply { isAccessible = true }.get(a)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(a: MainActivity, name: String, value: Any?) {
        (field(a, name) as MutableState<Any?>).value = value
    }

    private fun readState(a: MainActivity, name: String): Any? =
        (field(a, name) as? State<*>)?.value

    private fun callVoid(a: MainActivity, name: String) {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a)
    }

    private fun callStartFixTimes(a: MainActivity, targets: List<MediaItem>) {
        MainActivity::class.java.getDeclaredMethod("startFixTimes", List::class.java)
            .apply { isAccessible = true }.invoke(a, targets)
    }

    // ---------- 修复时间：忙时不许静默 ----------

    /** 已有批次在修：再点「修复」必须给话，不能静默 return */
    @Test
    fun startFixTimes_whileFixing_speaks() {
        val a = launchMain()
        setState(a, "isFixing", true)

        callStartFixTimes(a, listOf(MediaItem(1L, "/sdcard/x.jpg", "IMG_x.jpg", 1L, 0L, 0L, 1L)))

        assertTrue("上一批还在修时点「修复」必须说明原因，不得静默",
            (readState(a, "fixStatus") as String).isNotEmpty())
        assertTrue("忙时不得叠加新批次", readState(a, "isFixing") == true)
        a.finish()
    }

    /** 一张都没勾：同样要给话，而不是「点了没反应」 */
    @Test
    fun startFixTimes_noneSelected_speaks() {
        val a = launchMain()
        callStartFixTimes(a, emptyList())

        assertTrue("未勾选就点「修复」必须说明原因",
            (readState(a, "fixStatus") as String).isNotEmpty())
        assertFalse("未勾选时不应进入修复态", readState(a, "isFixing") == true)
        a.finish()
    }

    /**
     * 行内「重转」按钮只按条目自身状态渲染（`done && lostGps && !failed`），
     * 与「是否在转换中」无关 —— 所以批量入口那条 `!isConverting` 的隐藏逻辑罩不住它：
     * 转换进行中新加的这张丢了位置的老照片，行内重转照样点得到。必须给话。
     */
    @Test
    fun reconvertOneItem_whileConverting_speaks() {
        val a = launchMain()
        ConvertCenter.isConverting = true
        ConvertCenter.statusText = ""
        val item = MediaItem(7L, "/sdcard/gps.jpg", "IMG_gps.jpg", 1L, 0L, 0L, 1L)
        val ci = com.vliveconvert.app.ui.ConvertItem(
            item = item, status = "完成：无 GPS 位置数据", done = true, lostGps = true)

        MainActivity::class.java
            .getDeclaredMethod("reconvertOneItem", com.vliveconvert.app.ui.ConvertItem::class.java)
            .apply { isAccessible = true }.invoke(a, ci)

        assertTrue("转换中点行内「重转」必须说明原因，不得静默",
            ConvertCenter.statusText.isNotEmpty())
        ConvertCenter.isConverting = false
        a.finish()
    }

    // ---------- 把输出移到相机：忙时不许静默 ----------

    /** 转换进行中点这一行：必须给话（此前是 `if (…) return`，点了就是空气） */
    @Test
    fun moveOutputsToCamera_whileConverting_speaks() {
        val a = launchMain()
        ConvertCenter.isConverting = true
        ConvertCenter.statusText = ""

        callVoid(a, "moveOutputsToCamera")

        assertTrue("转换中点「移动输出到相册」必须说明原因",
            ConvertCenter.statusText.isNotEmpty())
        ConvertCenter.isConverting = false
        a.finish()
    }
}
