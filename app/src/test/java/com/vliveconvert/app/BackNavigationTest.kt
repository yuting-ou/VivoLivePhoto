package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.service.ConvertCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 系统返回键的层级语义。
 *
 * 用户报的现象：「关于与开源许可」页按返回，**第一次没反应**，第二次直接回到主页，
 * 而正确行为是第一次就退回二级菜单（设置页）。
 *
 * 根因是 BackHandler 的注册顺序与渲染层级不一致：OnBackPressedDispatcher 反向遍历回调，
 * 后注册的优先级更高；旧顺序下从设置页进关于页时，第一次返回关掉的是**看不见的设置页**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp",
    shadows = [ShadowExternalStorageAccess::class])
class BackNavigationTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                shadowOf(ApplicationProvider.getApplicationContext<VliveApp>()).grantPermissions(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
                base.evaluate()
            }
        }
    }).around(compose)

    private val activity get() = compose.activity

    @Suppress("UNCHECKED_CAST")
    private fun flag(name: String): Boolean {
        val f = MainActivity::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }
        return (f.get(activity) as MutableState<Boolean>).value
    }

    @Before
    fun reset() {
        ShadowExternalStorageAccess.granted = true
        activity.runOnUiThread {
            ConvertCenter.items.clear()
            ConvertCenter.isConverting = false
            ConvertCenter.statusText = ""
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        activity.onBackPressedDispatcher.onBackPressed()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /**
     * 等待一个由 IO 协程回主线程置上的标志（如进入选择器：先查相册再 showPicker=true）。
     * Robolectric 的 Dispatchers.IO 跑在真实线程上，mainClock 推不动它，只能真等。
     */
    private fun awaitFlag(desc: String, timeoutMs: Long = 5000, probe: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (probe()) return
            Thread.sleep(50)
        }
        throw AssertionError("等待超时：$desc")
    }

    /** 走真实点击到关于页：主页 → 设置 › → 关于与开源许可 */
    private fun openAboutFromSettings() {
        compose.onNodeWithText("设置 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("关于与开源许可").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue("前置条件：应已在关于页", flag("showAbout") && flag("showSettings"))
    }

    @Test
    fun backFromAbout_landsOnSettingsInOnePress() {
        openAboutFromSettings()

        pressBack()

        assertEquals("第一次返回必须关掉关于页", false, flag("showAbout"))
        assertTrue("第一次返回后应停在二级菜单（设置页），而不是继续往下跳",
            flag("showSettings"))
        compose.onNodeWithText("转换后处理").assertExists()

        pressBack()
        assertEquals("第二次返回才退出设置页", false, flag("showSettings"))
        compose.onNodeWithText("还没有选择照片").assertExists()
    }

    /** 同一类层级错误在「修复文件时间」上的对照：一次返回回设置页 */
    @Test
    fun backFromFixTime_landsOnSettingsInOnePress() {
        compose.onNodeWithText("设置 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("修复文件时间").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue("前置条件：应已在修复时间页", flag("showFixTime") && flag("showSettings"))

        pressBack()

        assertEquals("第一次返回必须关掉修复时间页", false, flag("showFixTime"))
        assertTrue("且应停在设置页", flag("showSettings"))
        compose.onNodeWithText("转换后处理").assertExists()
    }

    /** 选择器页：一次返回回主页（走的是新的 PredictiveBackHandler 弹层路径） */
    @Test
    fun backFromPicker_landsOnMainInOnePress() {
        compose.onNodeWithContentDescription("添加照片").performClick()
        // 进入选择器是「IO 查相册 → 回主线程置标志」，推不动虚拟时钟，只能真等
        awaitFlag("点击 ＋ 后应进入选择器页") { flag("showPicker") }

        pressBack()

        assertEquals("第一次返回必须关掉选择器页", false, flag("showPicker"))
        compose.onNodeWithText("还没有选择照片").assertExists()
    }

    /** 设置页：一次返回回主页 */
    @Test
    fun backFromSettings_landsOnMainInOnePress() {
        compose.onNodeWithText("设置 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue("前置条件：应已在设置页", flag("showSettings"))

        pressBack()

        assertEquals("第一次返回必须关掉设置页", false, flag("showSettings"))
        compose.onNodeWithText("还没有选择照片").assertExists()
    }
}
