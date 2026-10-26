package com.vliveconvert.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.ui.GlassDialog
import com.vliveconvert.app.ui.LocalReduceMotion
import com.vliveconvert.app.ui.MotionPrefs
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
 * 「减少动效」的语义：开启后弹窗进出场**瞬时完成**（静态分支不建动画帧），
 * 确认动作不再被 140ms 退场延迟；用户偏好持久化，且系统「移除动画」
 * （animator_duration_scale=0）自动等效开启。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReduceMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun dialogAppearsInstantlyAndConfirmsImmediately() {
        compose.mainClock.autoAdvance = false
        var confirmed = false
        val open = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
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
        }
        // 不推进任何帧：入场必须已经瞬时到位（默认分支需要弹簧帧才可见）
        compose.onNodeWithText("正文").assertExists()
        compose.onNodeWithText("开始").performClick()
        // 不推进帧：退场也瞬时，确认动作立即执行
        assertTrue("减少动效时确认必须立即执行", confirmed)
        assertFalse("弹窗必须立即移除", open.value)
    }

    @Test
    fun defaultBranchStillWaitsForExitAnimation() {
        compose.mainClock.autoAdvance = false
        var confirmed = false
        val open = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides false) {
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
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("开始").performClick()
        compose.mainClock.advanceTimeBy(16)
        assertFalse("默认分支仍须播完退场动画再执行确认", confirmed)
        compose.mainClock.advanceTimeBy(200)
        assertTrue(confirmed)
    }

    @Test
    fun prefPersistsAcrossLoad() {
        MotionPrefs.set(app, true)
        MotionPrefs.reduceMotion = false // 模拟新进程的内存态
        MotionPrefs.load(app)
        assertTrue(MotionPrefs.reduceMotion)
        MotionPrefs.set(app, false)
        MotionPrefs.load(app)
        assertFalse(MotionPrefs.reduceMotion)
    }

    @Test
    fun systemAnimatorZeroForcesEffective() {
        MotionPrefs.set(app, false)
        MotionPrefs.systemAnimatorOff = true // MainActivity 在 onResume 刷新该值
        assertTrue("系统移除动画必须自动等效开启", MotionPrefs.effective)
        MotionPrefs.systemAnimatorOff = false
        assertFalse(MotionPrefs.effective)
    }
}
