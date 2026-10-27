package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页工具行的「可点性」回归测试（UI 那一半，非 UI 那一半见 [SilentControlsTest]）。
 *
 * `ActionRow` 原先在忙时直接不换 `clickable`，整行变成点不动的死区；文字变淡却没有任何
 * 一处解释为什么按不动——与「开始转换」置灰后点不动是同一类静默缺陷。
 * 现在行仍可变淡作提示，但点击必须照常派发，由处理函数把原因说清楚。
 */
@RunWith(RobolectricTestRunner::class)
// 视口拉高，让「工具」卡片里的行不用滚动就在画面内（默认视口下它会掉到折叠线以下，
// 点击落不到节点上，测出来的「没反应」是量具问题而非产品问题）
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class SettingsActionRowClickTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun clickRowWhileBusy(isBusy: Boolean): Boolean {
        var fired = false
        compose.setContent {
            VLiveConvertTheme {
                SettingsScreen(
                    appName = "影中影",
                    versionName = "1.7.10",
                    outputRelPath = "Pictures/VLiveConvert",
                    moveToCamera = true,
                    deleteOriginal = false,
                    crashLogCount = 0,
                    isBusy = isBusy,
                    onBack = {},
                    onEditOutputPath = {},
                    onToggleMoveToCamera = {},
                    onToggleDeleteOriginal = {},
                    onRepairLocationDisplay = {},
                    onOpenFixTime = {},
                onOpenBrokenRepair = {},
                    onMoveOutputsToCamera = { fired = true },
                    onExportCrashLogs = {},
                onClearCrashLogs = {},
                    dynamicColor = true,
                    onToggleDynamicColor = {},
                    onOpenAbout = {}
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("移动输出到相册").performClick()
        compose.waitForIdle()
        return fired
    }

    /** 忙时点击必须仍然派发，否则就是静默死区 */
    @Test
    fun actionRow_stillClickableWhenBusy() {
        assertTrue("isBusy=true 时设置页工具行不能变成点不动的死区",
            clickRowWhileBusy(isBusy = true))
    }

    /** 对照组：非忙时同样可点——两条一起才能定位差异是否真来自 enabled 分支 */
    @Test
    fun actionRow_clickableWhenIdle() {
        assertTrue("isBusy=false 时应当可点", clickRowWhileBusy(isBusy = false))
    }
}
