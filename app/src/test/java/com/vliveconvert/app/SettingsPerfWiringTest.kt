package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.vliveconvert.app.service.PerfPreset
import com.vliveconvert.app.service.PerfSettings
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 性能面板接线：预设一键落定（CONFIRM 触觉 + 旋钮滑移是观感，落定值这里钉死）、
 * 手动开关切换模式。动效本身由真机手感验收，这里钉「点击产生正确的值」。
 * 注意：性能区在设置页折叠线以下——点击前必须先滚动到节点，否则坐标落在视口外、
 * 点击静默落空（本项目已踩过：不滚动直接点 = 回调永不触发）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPerfWiringTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val changes = mutableListOf<PerfSettings>()

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private fun setContent(initial: PerfSettings) {
        changes.clear()
        compose.setContent {
            VLiveConvertTheme(dynamicColor = false) {
                SettingsScreen(
                    appName = "影中影",
                    versionName = "test",
                    statusText = "",
                    outputRelPath = "DCIM/Camera",
                    moveToCamera = true,
                    deleteOriginal = true,
                    perf = initial,
                    perfAutoDetail = "当前：并发 2 路",
                    deviceInfo = "本机 8 核",
                    onPerfChange = { changes.add(it) },
                    crashLogCount = 0,
                    isBusy = false,
                    onBack = {},
                    onEditOutputPath = {},
                    onToggleMoveToCamera = {},
                    onToggleDeleteOriginal = {},
                    onOpenFixTime = {},
                    onOpenBrokenRepair = {},
                    onRepairLocationDisplay = {},
                    onMoveOutputsToCamera = {},
                    onExportCrashLogs = {},
                    onClearCrashLogs = {},
                    dynamicColor = false,
                    onToggleDynamicColor = {},
                    onOpenAbout = {}
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun presetTapEmitsPresetValues() {
        setContent(PerfPreset.Balanced.apply().copy(manual = true))
        scrollTo("省电")
        compose.onNodeWithText("省电").performClick()
        compose.waitForIdle()
        val saver = changes.single()
        assertTrue(saver.manual)
        assertEquals(1, saver.concurrency)
        assertEquals(2, saver.scanThreads)
        assertEquals(2, saver.thumbThreads)

        scrollTo("省电")
        // 「性能」同时命中分区标题与按钮，按钮才有 OnClick 动作
        compose.onNode(hasText("性能") and hasClickAction()).performClick()
        compose.waitForIdle()
        val perf = changes.last()
        assertEquals(4, perf.concurrency)
        assertEquals(6, perf.scanThreads)
        assertEquals(6, perf.thumbThreads)
    }

    @Test
    fun modeSwitchToManualEmitsBalanced() {
        setContent(PerfSettings(manual = false))
        scrollTo("手动调节性能")
        compose.onNodeWithText("手动调节性能").performClick()
        compose.waitForIdle()
        assertTrue("打开开关=进手动挡（预填均衡值）", changes.last().manual)
        assertEquals(2, changes.last().concurrency)
    }

    @Test
    fun modeSwitchToAutoEmitsAuto() {
        setContent(PerfPreset.Balanced.apply().copy(manual = true))
        scrollTo("手动调节性能")
        compose.onNodeWithText("手动调节性能").performClick()
        compose.waitForIdle()
        assertEquals("关回开关=回自动挡", false, changes.last().manual)
    }
}