package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * 崩溃日志导出：真实点击 + 弹窗反馈 + 去重。
 *
 * 用户实测踩到的三个坑在这里收口：
 * 1. 【闪退】导出在 IO 协程里没有任何兜底 → 未捕获异常 = 再次闪退（死循环）
 * 2. 【点了没反应 ×2】反馈写在顶栏小字里看半天注意不到；行本身没有涟漪反馈感
 * 3. 【几十份重复】点一次导出全部、再点又全量导出——MediaStore 对同名文件
 *    自动追加 "(1)" "(2)" 序号，连点 N 次攒几十份。现在按文件名去重：
 *    已导过的不再写，弹窗明说「没有新日志」
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

    /** 等弹窗出现（IO 协程回主线程置状态，推不动虚拟时钟，只能真等） */
    private fun awaitDialog(part: String, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            val hits = compose.onAllNodesWithText(part, substring = true).fetchSemanticsNodes()
            if (hits.isNotEmpty()) return
            Thread.sleep(50)
        }
        throw AssertionError("等待弹窗超时：未出现包含「$part」的文案")
    }

    private fun openSettings() {
        compose.onNodeWithText("设置 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue("前置：应已在设置页", flag("showSettings"))
    }

    private fun tapExportRow() {
        compose.onNodeWithText("导出崩溃日志", substring = true).performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test
    fun exportSpeaksInDialog_andDeduplicatesOnSecondTap() {
        val dir = File(activity.filesDir, "crash").apply { mkdirs() }
        File(dir, "crash_123.txt").writeText("app: test\nfake stack")

        openSettings()
        tapExportRow()
        awaitDialog("已导出 1 份新日志")

        // 关掉弹窗再点一次——正是用户「连点 N 次」的动作；
        // 期望明说没有新日志，而不是再写一份 (1) 重复文件
        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()
        tapExportRow()
        awaitDialog("没有新日志：1 份此前已导出过")

        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()
    }

    @Test
    fun exportSpeaksInDialog_withoutLogs() {
        openSettings()
        tapExportRow()
        awaitDialog("还没有崩溃日志可导出")
        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()
    }
}
