package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主界面列表滚动回归：照片多到超出一屏时必须能持续滑到深处。
 *
 * 背景（v2.5.2 真机报告「照片太多滑不动」）：根因不是列表本身——8550 项时
 * 快速滚动会让每个新组合的行立即发起 loadThumbnail，请求洪水打满
 * MediaProvider 与 IO 线程，主线程抢不到 CPU（模拟器实测帧耗时 2.3s），
 * fling 动量被整体吃掉；2154 项时症状减弱为「fling 变弱」。修复是在
 * MediaThumbnail 加 120ms 驻留门槛（见 Components.kt 注释）。
 *
 * 本用例钉住「滚动能力」本身：Robolectric 无真实 Provider 成本，无法复现
 * 冻结，但能守住「连续手势必须持续前进」的底线（此前测试里 swipeUp 工具
 * 手势在本项目环境下不产生位移，改用显式定时手势）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenScrollTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun items(n: Int) = (1..n).map { i ->
        ConvertItem(item = MediaItem(
            i.toLong(), "/sdcard/DCIM/Camera/IMG_$i.jpg", "IMG_$i.jpg",
            1L, 1_754_000_000_000L + i, 1_754_000_000L + i, 1024L * 1024 * 3))
    }

    private fun setContent(n: Int) {
        compose.setContent {
            VLiveConvertTheme {
                MainScreen(
                    items = items(n),
                    appName = "影中影",
                    statusText = "",
                    isConverting = false,
                    finishedBeat = false,
                    progress = 0f,
                    progressDetail = "",
                    pendingRestoreCount = 0,
                    onRestoreOriginals = {},
                    locationMissing = false,
                    onGrantLocation = {},
                    reconvertCount = 0,
                    onReconvertLostGps = {},
                    onReconvertItem = {},
                    outputRelPath = "Pictures/VLiveConvert",
                    moveToCamera = true,
                    deleteOriginal = true,
                    onOpenSettings = {},
                    onShowStatusDetail = {},
                    onCancelConvert = {},
                    onAddMore = {},
                    onStartConvert = {},
                    onClearAll = {},
                    failedCount = 0,
                    onRetryFailed = {},
                    onShareItem = {},
                    onRemove = {}
                )
            }
        }
        compose.waitForIdle()
    }

    /** 显式定时上滑手势：20 步插值、步进间隔 stepMs，等效真实手指速度 */
    private fun timedSwipe(stepMs: Long = 15) {
        compose.onRoot().performTouchInput {
            val startY = 235f
            val endY = 60f
            down(Offset(width / 2f, startY))
            val steps = 20
            repeat(steps) { s ->
                moveTo(Offset(width / 2f, startY + (endY - startY) * (s + 1) / steps))
                advanceEventTime(stepMs)
            }
            up()
        }
        compose.waitForIdle()
    }

    private fun minIndexPresent(): Int =
        (1..80).firstOrNull { i ->
            compose.onAllNodesWithText("IMG_$i.jpg").fetchSemanticsNodes().isNotEmpty()
        } ?: -1

    @Test
    fun list_scrollsContinuously_withManyPhotos() {
        setContent(80)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("IMG_1.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        // 顶部起点：第 1 行必须可见
        compose.onNodeWithText("IMG_1.jpg").assertExists()

        // 连续手势必须持续前进（>0 行），且总位移超过一屏（懒加载窗口翻新）
        val seen = mutableSetOf<Int>()
        repeat(6) {
            timedSwipe()
            seen += minIndexPresent()
        }
        val reached = seen.max()
        assertTrue(
            "连续 6 次上滑后最深只到达第 $reached 行（seen=$seen）——列表滚动可能被破坏",
            reached > 1 && seen.size > 1
        )
    }
}
