package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 底栏「转换中 ↔ 完成节拍」之间不许有位移的回归测试。
 *
 * 真机上「闪一下」的另一半来源：底栏各态内容高度不同，切换时整块高度跳变。
 * 节拍态用一块与「取消转换」等高的占位，而不是让按钮凭空消失 ——
 * 于是两态下进度条的屏幕位置应当一致。位移可在 Robolectric 下直接量出。
 *
 * 只 setContent 一次（规则不允许重设内容），两态由外部 MutableState 驱动；
 * 切换后推进超过过渡时长再量，避免读到动画中途的位置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomBarLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bottomBar_doesNotJumpBetweenConvertingAndBeat() {
        val converting: MutableState<Boolean> = mutableStateOf(true)
        val beat: MutableState<Boolean> = mutableStateOf(false)

        compose.setContent {
            VLiveConvertTheme {
                MainScreen(
                    items = listOf(ConvertItem(item = MediaItem(
                        1L, "/sdcard/a.jpg", "IMG_a.jpg", 1L, 1L, 1L, 1L))),
                    appName = "影中影",
                    statusText = "",
                    isConverting = converting.value,
                    finishedBeat = beat.value,
                    progress = 1f,
                    progressDetail = "已处理 1/1",
                    pendingRestoreCount = 0,
                    onRestoreOriginals = {},
                    locationMissing = false,
                    onGrantLocation = {},
                    reconvertCount = 0,
                    onReconvertLostGps = {},
                    onReconvertItem = {},
                    outputRelPath = "Pictures/VLiveConvert",
                    moveToCamera = true,
                    deleteOriginal = false,
                    onOpenSettings = {},
                    onShowStatusDetail = {},
                    onCancelConvert = {},
                    onAddMore = {},
                    onStartConvert = {},
                    onClearAll = {},
                    onRemove = {}
                )
            }
        }
        compose.waitForIdle()

        fun progressTop(): Float = compose.onNodeWithText("已处理 1/1")
            .fetchSemanticsNode().boundsInRoot.top

        val duringConvert = progressTop()

        converting.value = false
        beat.value = true
        compose.mainClock.advanceTimeBy(600)   // 让尺寸过渡走完
        compose.waitForIdle()
        val duringBeat = progressTop()

        assertEquals(
            "转换中 ↔ 完成节拍之间进度条不应位移（位移就是肉眼看到的「跳一下」）；" +
                "实测 转换中 top=$duringConvert 节拍 top=$duringBeat",
            duringConvert, duringBeat, 1f)
    }
}
