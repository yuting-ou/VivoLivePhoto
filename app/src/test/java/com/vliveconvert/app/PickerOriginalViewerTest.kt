package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vliveconvert.app.picker.AlbumInfo
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.PickerScanner
import com.vliveconvert.app.picker.PickerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 放大镜入口：只看原图，不改勾选；查看器里可以连续勾选再返回。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerOriginalViewerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun media(id: Long) = MediaItem(
        id = id,
        path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        name = "IMG_$id.jpg",
        bucketId = 7L,
        dateTaken = 1_754_000_000_000L + id * 60_000L,
        dateModified = 1_754_000_000_000L + id,
        size = 1024L
    )

    private fun render(items: List<MediaItem>) {
        val bucket = 7L
        val scanner = PickerScanner(MediaRepo(compose.activity.contentResolver))
        val state = scanner.stateOf(bucket)
        state.results.addAll(items)
        state.total = items.size
        state.doneCount.set(items.size)
        state.completed = true

        compose.setContent {
            PickerScreen(
                albums = listOf(AlbumInfo(bucket, "Camera", items.size, items.first().id, 0L)),
                scanner = scanner,
                scannerScope = CoroutineScope(Dispatchers.Unconfined),
                onBack = {},
                onConfirm = {}
            )
        }
    }

    @Test
    fun magnifierOpensViewerWithoutSelectingAndViewerCanToggle() {
        render(listOf(media(1), media(2)))

        compose.onNodeWithContentDescription("查看 IMG_1.jpg 原图").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("IMG_1.jpg").assertExists()
        // 网格按拍摄时间倒序，IMG_2 在前：点 IMG_1 是第 2 张
        compose.onNodeWithText("2 / 2").assertExists()
        compose.onNodeWithText("已选 0 张").assertExists()

        compose.onNodeWithText("选择这张").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已选 · 取消选择").assertExists()

        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已选 1 张").assertExists()
        assertTrue(
            "查看器应已关闭",
            compose.onAllNodesWithContentDescription("关闭预览").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun opensAtTappedPhoto() {
        render(listOf(media(1), media(2)))

        compose.onNodeWithContentDescription("查看 IMG_2.jpg 原图").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("IMG_2.jpg").assertExists()
        compose.onNodeWithText("1 / 2").assertExists()
    }
}
