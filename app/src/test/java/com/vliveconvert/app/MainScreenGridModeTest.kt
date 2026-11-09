package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主队列 列表/网格 双模式：切换不丢队列内容、偏好持久化、
 * 网格卡片保留状态语义（名称+状态可被读屏读到）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenGridModeTest {

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

    private fun prefs() = ApplicationProvider.getApplicationContext<VliveApp>()
        .getSharedPreferences("vliveconvert", 0)

    @Test
    fun toggleToGridKeepsItemsAndPersistsPreference() {
        prefs().edit().remove("main_view_mode").commit()
        setContent(5)
        // 默认列表：行名文本可见，网格卡片语义不存在
        compose.onNodeWithText("IMG_1.jpg").assertExists()

        compose.onNodeWithText("网格").performClick()
        compose.waitForIdle()
        assertEquals("grid", prefs().getString("main_view_mode", null))
        // 网格卡片语义 =「名称，状态」
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("IMG_1.jpg，待转换")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithContentDescription("IMG_1.jpg，待转换")
            .fetchSemanticsNodes().isNotEmpty())
        // 队列内容不丢：5 张都在（语义计数）
        assertEquals(5, compose.onAllNodesWithContentDescription("IMG_", substring = true)
            .fetchSemanticsNodes().size)

        // 切回列表：行名文本回来，网格卡片语义消失
        compose.onNodeWithText("列表").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("IMG_1.jpg").assertExists()
        assertTrue(compose.onAllNodesWithContentDescription("IMG_1.jpg，待转换")
            .fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun gridPreferenceSurvivesRecomposition() {
        prefs().edit().putString("main_view_mode", "grid").commit()
        setContent(3)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("IMG_1.jpg，待转换")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("偏好为 grid 时应直接以网格呈现",
            compose.onAllNodesWithContentDescription("IMG_2.jpg，待转换")
                .fetchSemanticsNodes().isNotEmpty())
        // 清理，避免污染其它用例的默认视图
        prefs().edit().remove("main_view_mode").commit()
    }
}