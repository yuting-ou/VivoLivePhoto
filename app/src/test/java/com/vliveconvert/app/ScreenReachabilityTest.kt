package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
 * 界面可达性的回归测试：嵌套页面必须能盖过它的父页面。
 *
 * 「修复文件时间」的入口只在设置页里，而 `targetScreen` 早先把 `showSettings` 排在
 * `showFixTime` 前面 —— 于是从设置页点它时，`showFixTime` 置上了、界面却仍停在设置页，
 * 用户视角就是「点了没反应」。这个顺序错误让该功能自 v1.7.3 起完全不可达。
 *
 * 两层都测：`pickScreen` 真值表（纯函数，防排错）+ 真实组合渲染（防"函数对了但没接上"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenReachabilityTest {

    // ---------- 1) 纯函数真值表 ----------

    @Test
    fun pickScreen_subpagesBeatTheirParentSettings() {
        // 设置页里点「修复文件时间」：两个标志同时为真，必须渲染 FixTime
        assertEquals(Screen.FixTime,
            pickScreen(true, showAbout = false, showFixTime = true,
                showPicker = false, showSettings = true))
        // 设置页里点「关于」
        assertEquals(Screen.About,
            pickScreen(true, showAbout = true, showFixTime = false,
                showPicker = false, showSettings = true))
        // 谁都没开 → 主页；开了选择器（从主页进）→ 选择器
        assertEquals(Screen.Main,
            pickScreen(true, false, false, false, false))
        assertEquals(Screen.Picker,
            pickScreen(true, false, false, true, false))
        // 未授予媒体权限时权限页最高优先
        assertEquals(Screen.Permission,
            pickScreen(false, true, true, true, true))
    }

    // ---------- 2) 真实组合：两个标志同时置上时，画面上是谁 ----------

    private val compose = createAndroidComposeRule<MainActivity>()

    /** 权限先于首次组合生效，否则界面停在授权页，测不到设置页 */
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

    private val FIXTIME_MARKER = "按文件名中的拍摄时间修正「修改时间」"
    private val SETTINGS_MARKER = "转换后处理"

    @Suppress("UNCHECKED_CAST")
    private fun setShow(name: String, value: Boolean) {
        val f = MainActivity::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }
        (f.get(compose.activity) as MutableState<Boolean>).value = value
    }

    @Test
    fun settingsPlusFixTime_rendersFixTimeScreen() {
        compose.waitForIdle()
        setShow("showSettings", true)
        compose.waitForIdle()
        // 先确认设置页确实在画面上，否则后面的断言等于什么都没测
        compose.onNodeWithText(SETTINGS_MARKER).assertExists()

        setShow("showFixTime", true)
        compose.mainClock.advanceTimeBy(600)   // 让转场走完
        compose.waitForIdle()

        assertEquals(
            "从设置页打开「修复文件时间」必须真的切过去（两个标志同时为真时设置页不能赢）",
            1, countNodes(FIXTIME_MARKER))
        assertEquals("切过去后设置页的内容不应还在画面上",
            0, countNodes(SETTINGS_MARKER))
    }

    /** 语义节点计数（不存在时为 0，不抛） */
    private fun countNodes(text: String): Int = try {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size
    } catch (_: Throwable) {
        0
    }
}
