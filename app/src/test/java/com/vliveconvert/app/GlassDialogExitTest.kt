package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vliveconvert.app.ui.GlassDialog
import com.vliveconvert.app.ui.rememberGlassHazeState
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 弹窗退场动效：确认 / 返回都必须先播完收场动画再执行动作。
 * 此前只有入场，父层状态一翻弹窗就瞬间消失，与「弹出」的手感不对称。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassDialogExitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun confirm_waitsForExitAnimationBeforeRunningAction() {
        compose.mainClock.autoAdvance = false
        var confirmed = false
        val open = mutableStateOf(true)
        compose.setContent {
            VLiveConvertTheme(dynamicColor = false) {
                if (open.value) {
                    GlassDialog(
                        hazeState = rememberGlassHazeState(),
                        onDismissRequest = { open.value = false },
                        title = "准备转换",
                        confirmLabel = "开始",
                        onConfirm = {
                            confirmed = true
                            open.value = false
                        }
                    ) {
                        Text("正文")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("开始").performClick()
        compose.mainClock.advanceTimeBy(16)
        assertFalse("退场动画期间不得提前执行确认动作", confirmed)
        assertTrue("退场动画期间弹窗仍需留在画面上", open.value)
        compose.mainClock.advanceTimeBy(200)
        assertTrue("退场结束后必须执行确认动作", confirmed)
        assertFalse("动作执行后弹窗必须关闭", open.value)
    }

    @Test
    fun confirm_thatKeepsDialogOpen_fadesBackInAndStaysUsable() {
        compose.mainClock.autoAdvance = false
        var confirmed = 0
        val open = mutableStateOf(true)
        compose.setContent {
            VLiveConvertTheme(dynamicColor = false) {
                if (open.value) {
                    GlassDialog(
                        hazeState = rememberGlassHazeState(),
                        onDismissRequest = { open.value = false },
                        title = "输出目录",
                        confirmLabel = "确定",
                        // 模拟校验失败的确认：只报错，不关闭弹窗
                        onConfirm = { confirmed++ }
                    ) {
                        Text("正文")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("确定").performClick()
        compose.mainClock.advanceTimeBy(400)
        assertEquals("确认动作必须已执行", 1, confirmed)
        assertTrue("动作没有关闭弹窗时，弹窗必须还在", open.value)
        compose.onNodeWithText("正文").assertExists()
        // 收场动画必须复位，弹窗仍然可再次操作
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("确定").performClick()
        compose.mainClock.advanceTimeBy(400)
        assertEquals("再次点击必须还能触发确认", 2, confirmed)
    }

    @Test
    fun back_waitsForExitAnimationBeforeDismissing() {
        compose.mainClock.autoAdvance = false
        var dismissed = false
        val open = mutableStateOf(true)
        compose.setContent {
            VLiveConvertTheme(dynamicColor = false) {
                if (open.value) {
                    GlassDialog(
                        hazeState = rememberGlassHazeState(),
                        onDismissRequest = {
                            dismissed = true
                            open.value = false
                        },
                        title = "准备转换",
                        confirmLabel = "开始",
                        onConfirm = {}
                    ) {
                        Text("正文")
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(16)
        assertFalse("退场动画期间不得提前关闭", dismissed)
        compose.mainClock.advanceTimeBy(200)
        assertTrue("退场结束后必须关闭弹窗", dismissed)
        assertFalse("关闭后弹窗必须从画面移除", open.value)
    }
}
