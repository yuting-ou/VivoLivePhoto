package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
class SettingsCardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val deleteOriginal = mutableStateOf(false)
    private var deleteCalls = 0
    private var repairCalls = 0
    private var moveCalls = 0

    private fun render(fontScale: Float = 1f, busy: Boolean = false, path: String = "Pictures/VLiveConvert") {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                VLiveConvertTheme(darkTheme = false, dynamicColor = false) {
                    SettingsScreen(
                        appName = "影中影", versionName = "", queueCount = 3,
                        outputRelPath = path, moveToCamera = true,
                        deleteOriginal = deleteOriginal.value, crashLogCount = 0, isBusy = busy,
                        onBack = {}, onEditOutputPath = {}, onToggleMoveToCamera = {},
                        onToggleDeleteOriginal = { deleteCalls++; deleteOriginal.value = it },
                        onOpenFixTime = {}, onOpenBrokenRepair = { repairCalls++ },
                        onRepairLocationDisplay = {}, onMoveOutputsToCamera = { moveCalls++ },
                        onExportCrashLogs = {}, onClearCrashLogs = {},
                        dynamicColor = false, onToggleDynamicColor = {}, onOpenAbout = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun action(title: String) = compose.onNode(hasText(title) and hasClickAction())
    private fun textBounds(title: String) =
        compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun toolRowsHaveEqualHeightsAndAlignedSecondRow() {
        render()
        val first = action("修复文件时间").fetchSemanticsNode().boundsInRoot
        val second = action("修复破损实况").fetchSemanticsNode().boundsInRoot
        val third = action("重建位置显示").fetchSemanticsNode().boundsInRoot
        val fourth = action("把输出移到相机相册").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.bottom, second.bottom, 1f)
        assertEquals(third.top, fourth.top, 1f)
        assertEquals(third.bottom, fourth.bottom, 1f)
        assertEquals(first.left, third.left, 1f)
        assertEquals(second.right, fourth.right, 1f)
        assertEquals(textBounds("重建位置显示").bottom,
            textBounds("把输出移到相机相册").bottom, 1f)
    }

    @Test fun summaryAndCardContentsShareLeftAlignment() {
        render()
        assertEquals(textBounds("已添加 3 张照片").left,
            textBounds("修复文件时间").left, 1f)
    }

    @Test fun summaryScrollsAwaySoItCannotShrinkTheSettingsViewport() {
        render(fontScale = 2f)
        val summary = compose.onNodeWithText("已添加 3 张照片").fetchSemanticsNode()
        assertTrue("摘要也必须属于滚动容器", generateSequence(summary.parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) })
    }

    @Test fun switchesUseCardsAndTappingLabelTogglesOnce() {
        render()
        assertEquals(textBounds("移到相机相册").top, textBounds("删除原图").top, 1f)
        assertTrue(textBounds("删除原图").left > textBounds("移到相机相册").right)
        action("删除原图").performClick()
        compose.runOnIdle {
            assertTrue(deleteOriginal.value)
            assertEquals(1, deleteCalls)
        }
    }

    @Test fun busySwitchesDoNotToggleButToolActionsStillExplainBusyState() {
        render(busy = true)
        compose.onNodeWithText("删除原图").performClick()
        action("把输出移到相机相册").performClick()
        compose.runOnIdle {
            assertEquals(0, deleteCalls)
            assertEquals(1, moveCalls)
        }
    }

    @Test fun toolWhitespaceIsClickableNotOnlyTheIcon() {
        render()
        action("修复破损实况").performTouchInput {
            // 落在卡片底部留白（非图标、非文字），验证整卡可点
            click(Offset(width / 2f, height - 6f))
        }
        compose.runOnIdle { assertEquals(1, repairCalls) }
    }

    @Test fun largeFontsUseSingleColumnAndDoNotClipLongContent() {
        val path = "Pictures/旅行与家庭照片/2026/保留原始实况的完整备份目录"
        render(fontScale = 2f, path = path)
        assertEquals(textBounds("移到相机相册").left, textBounds("删除原图").left, 1f)
        assertTrue(textBounds("删除原图").top > textBounds("移到相机相册").bottom)
        listOf("把输出移到相机相册", path).forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            val layout = layouts.single()
            assertFalse("大字号最后一行不能省略：$text",
                layout.isLineEllipsized(layout.lineCount - 1))
        }
    }
}
