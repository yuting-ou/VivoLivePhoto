package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.service.ConvertCenter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 全工具入口的可达性与反馈扫查。
 *
 * 动因：「修复文件时间」因为 targetScreen 的一条 if/else 顺序写反，自 v1.7.3 起完全打不开。
 * 入口不可达意味着页面内部永远没人走——一个没人走的页面可以带着任意缺陷发布十几个版本。
 * 本类用**真实点击**逐个走一遍所有入口，断言每一击都产生可观察结果：界面切换、
 * 弹窗状态置上，或状态行/页内提示说话。不允许任何一处「什么都不发生」。
 *
 * 视口加高只为让设置页各行落在折叠线以上。含文本框的对话框在自定义视口下会让
 * Compose 测试进不了 idle（已用裸 Activity 探针证实是量具限制，不是产品缺陷），
 * 故「输出目录」那条用例单独放在默认视口的 OutputPathDialogTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp",
    shadows = [ShadowExternalStorageAccess::class])
class ToolReachabilityTest {

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

    @Before
    fun reset() {
        ShadowExternalStorageAccess.granted = false
        activity.runOnUiThread {
            ConvertCenter.items.clear()
            ConvertCenter.isConverting = false
            ConvertCenter.finishedBeat = false
            ConvertCenter.statusText = ""
        }
        compose.waitForIdle()
    }

    @After
    fun clearGrant() { ShadowExternalStorageAccess.granted = false }

    @Suppress("UNCHECKED_CAST")
    private fun flag(name: String): Boolean = try {
        val f = MainActivity::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }
        (f.get(activity) as MutableState<Boolean>).value
    } catch (_: NoSuchFieldException) {
        val f = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        f.get(activity) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun setFlag(name: String, value: Boolean) {
        val f = MainActivity::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }
        (f.get(activity) as MutableState<Boolean>).value = value
    }

    private fun readStr(name: String): String {
        val f = MainActivity::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }
        return ((f.get(activity) as? androidx.compose.runtime.State<*>)?.value as? String) ?: ""
    }

    private fun countNodes(text: String): Int = try {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size
    } catch (_: Throwable) { 0 }

    /** 异步协程写的状态等不到一次 waitForIdle 就够，必须轮询主线程 */
    private fun awaitUntil(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue("等待超时：$what", cond())
    }

    private fun openSettings() {
        compose.onNodeWithText("设置 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("转换后处理").assertExists()
    }

    // ---------- 设置页：每一行都要有可观察结果 ----------

    @Test
    fun settingsRow_about_opensAboutPage() {
        openSettings()
        compose.onNodeWithText("关于与开源许可").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue("点「关于与开源许可」必须进入关于页", flag("showAbout"))
    }

    /** 未授予「所有文件访问权限」：必须弹授权引导，而不是停在设置页 */
    @Test
    fun settingsRow_fixTime_withoutAllFilesAccess_guidesToGrant() {
        ShadowExternalStorageAccess.granted = false
        openSettings()
        compose.onNodeWithText("修复文件时间").performClick()
        compose.waitForIdle()
        assertTrue("缺权限时必须弹授权引导", flag("showAllFilesDialog"))
    }

    /** 已授予：必须真的切进修复时间页（v1.7.3~v1.7.12 的实际行为是停在设置页） */
    @Test
    fun settingsRow_fixTime_withAllFilesAccess_entersThePage() {
        ShadowExternalStorageAccess.granted = true
        openSettings()
        compose.onNodeWithText("修复文件时间").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        assertTrue("点「修复文件时间」必须置上进入标志", flag("showFixTime"))
        assertEquals("画面上必须是修复时间页", 1,
            countNodes("按文件名中的拍摄时间修正「修改时间」"))
        assertEquals("设置页内容不应还留在画面上", 0, countNodes("转换后处理"))
    }

    /** 修复破损实况入口自 test2 加入起没接线、点击静默无反应；用真实点击钉住 */
    @Test
    fun settingsRow_brokenRepair_entersThePage() {
        openSettings()
        compose.onNodeWithText("修复破损实况").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue("点「修复破损实况」必须进入修复页", flag("showBrokenRepair"))
        compose.onNodeWithText("拍摄时连拍或立刻息屏", substring = true).assertExists()
    }

    @Test
    fun settingsRow_moveOutputs_speaksInsteadOfSwallowingTheTap() {        openSettings()
        ConvertCenter.statusText = ""
        compose.onNodeWithText("把输出移到相机相册").performClick()
        awaitUntil("点「把输出移到相机相册」后状态行应给出可读结果") {
            ConvertCenter.statusText.isNotEmpty()
        }
    }

    /**
     * 忙时的守卫必须"说话"。上一条走的是正常分支，够不到 `isConverting` 那道判断——
     * 变异检查时发现把守卫退回静默 `return` 它照样绿，所以补这一条正面打那条分支。
     */
    @Test
    fun settingsRow_moveOutputs_whileConverting_explainsWhyItRefused() {
        openSettings()
        ConvertCenter.isConverting = true
        ConvertCenter.statusText = ""
        compose.waitForIdle()

        compose.onNodeWithText("把输出移到相机相册").performClick()
        compose.waitForIdle()
        assertEquals("转换中点这一行必须给出拒绝原因，而不是把点击吞掉",
            "转换进行中，等本轮完成后再移动输出文件", ConvertCenter.statusText)
        ConvertCenter.isConverting = false
    }

    // ---------- 修复时间页内部（此前从未被真实走过） ----------

    @Test
    fun fixTimeScreen_unselectedFixSpeaks_andBackReturnsToSettings() {
        ShadowExternalStorageAccess.granted = true
        openSettings()
        compose.onNodeWithText("修复文件时间").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        compose.onNodeWithText("修复所选时间").performClick()
        compose.waitForIdle()
        assertTrue("未勾选点「修复」必须说明原因", readStr("fixStatus").isNotEmpty())

        compose.onNodeWithContentDescription("返回").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("返回后不应还停在修复时间页", false, flag("showFixTime"))
        compose.onNodeWithText("转换后处理").assertExists()
    }

    // ---------- 选择器与主页 ----------

    /**
     * 「添加」不再置灰后，一张没勾点它必须说明原因。
     *
     * 注意这里**不留任何提前 return**：早先版本写了 `if (!showPicker) return`，
     * 结果选择器没打开时整条用例静默通过 —— 把「添加」退回置灰版本它照样绿。
     * 断言前置条件失败就该红，不许空过。
     */
    @Test
    fun picker_addWithNothingSelected_speaks() {
        ConvertCenter.statusText = ""
        compose.onNodeWithContentDescription("添加照片").performClick()
        awaitUntil("点 ＋ 应进入内置选择器") { flag("showPicker") }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        compose.onNodeWithText("添加").performClick()
        compose.waitForIdle()
        assertEquals("一张没勾就点「添加」必须说明原因",
            "先勾选要添加的照片", ConvertCenter.statusText)
    }

    @Test
    fun main_statusDetailEntry_opensDialog() {
        activity.runOnUiThread { ConvertCenter.statusText = "已添加 1 张，共 1 张待转换" }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("详情 ›").performClick()
        compose.waitForIdle()
        assertTrue("点「详情 ›」必须展开状态详情", flag("showStatusDetail"))
        // 玻璃弹窗改为应用内浮层（不再是独立 window），语义树必须能直接读到
        compose.onNodeWithText("状态详情").assertExists()

        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()
        assertFalse("「知道了」必须关闭弹窗", flag("showStatusDetail"))

        // 返回键也必须只关弹窗、不退出页面
        compose.onNodeWithText("详情 ›").performClick()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertFalse("返回键应先关闭弹窗", flag("showStatusDetail"))
    }
}
