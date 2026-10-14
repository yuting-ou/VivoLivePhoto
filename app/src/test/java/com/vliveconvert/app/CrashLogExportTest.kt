package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.io.File

/**
 * 崩溃日志导出：真实点击 + 就地反馈。
 *
 * 两个缺陷在这里收口：
 * 1. 【闪退】导出跑在 IO 协程里但没有任何兜底，任何未捕获异常 = 应用直接崩溃
 *    （用户点的恰恰是「崩溃日志」入口——闪退后想反馈，结果又闪退，死循环）
 * 2. 【点了没反应】动作反馈写主页状态栏，用户人在设置页 → 什么也看不见。
 *    v1.7.21 起设置页就地显示 statusText，本用例断言反馈节点出现在**设置页内**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp",
    shadows = [ShadowExternalStorageAccess::class])
class CrashLogExportTest {

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

    /** 等待 IO 协程回主线程置上的状态文案（推不动虚拟时钟，只能真等） */
    private fun awaitStatus(timeoutMs: Long = 5000, probe: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var current = ""
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            activity.runOnUiThread { current = ConvertCenter.statusText }
            Thread.sleep(50)
            if (probe(current)) return current
        }
        throw AssertionError("等待超时：statusText=「$current」")
    }

    private fun openSettings() {
        compose.onNodeWithText("设置 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun exportRowSpeaksInPlace_withLogs() {
        // 造一份假崩溃日志（正是应用闪退后会留下的东西）
        val dir = File(activity.filesDir, "crash").apply { mkdirs() }
        File(dir, "crash_123.txt").writeText("app: test\nfake stack")
        assertEquals(1, com.vliveconvert.app.CrashLog.count(activity))

        openSettings()
        assertTrue("前置：应已在设置页", flag("showSettings"))
        compose.onNodeWithText("导出崩溃日志", substring = true).assertExists()
        compose.onNodeWithText("导出崩溃日志", substring = true).performClick()

        val status = awaitStatus { it.contains("导出") }
        assertTrue(
            "导出反馈必须出现在设置页内（写在主页 = 用户看不见 = 点了没反应）: $status",
            status.contains("已导出") || status.contains("导出失败")
        )
        compose.onNodeWithText(status).assertExists()
    }

    @Test
    fun exportRowSpeaksInPlace_withoutLogs() {
        openSettings()
        assertTrue("前置：应已在设置页", flag("showSettings"))
        compose.onNodeWithText("导出崩溃日志", substring = true).assertExists()
        compose.onNodeWithText("导出崩溃日志", substring = true).performClick()

        val status = awaitStatus { it.contains("导出") }
        assertEquals("没有可导出的崩溃日志", status)
        compose.onNodeWithText(status).assertExists()
    }
}
