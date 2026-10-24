package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vliveconvert.app.picker.AlbumInfo
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.PickerScanner
import com.vliveconvert.app.picker.PickerScreen
import com.vliveconvert.app.ui.dateLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 选择器「全选」的规模回归：
 * 旧实现逐张 `selected.add` + 每个格子/日期头线性扫 selected，
 * 真机全选 8550 张时主线程被 O(n²) 打满并 ANR（logcat signal 3）。
 * 这里用 300 张锁定「文字也可点 + 全选/取消全选」的正确性；
 * 性能修复本身（派生 id 集合 + 一次 addAll）由真机验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerSelectAllTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectAllTogglesWholeListAndLabelIsClickable() {
        renderPicker(300)

        compose.onNodeWithText("全选").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已选 300 张").assertExists()

        compose.onNodeWithText("全选").performClick()
        compose.waitForIdle()
        assertTrue(
            "再点一次全选应取消全部",
            compose.onAllNodesWithText("已选 300 张").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun dateCapsuleSelectsWholeDay() {
        val items = renderPicker(6)
        val desc = "全选 ${dateLabel(items.first().sortTime)} 的照片"

        compose.onNodeWithContentDescription(desc).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已选 6 张").assertExists()

        compose.onNodeWithContentDescription(desc).performClick()
        compose.waitForIdle()
        assertTrue(
            "日期胶囊再点一次应取消当天全部",
            compose.onAllNodesWithText("已选 6 张").fetchSemanticsNodes().isEmpty()
        )
    }

    private fun renderPicker(count: Long): List<MediaItem> {
        val bucket = 7L
        val scanner = PickerScanner(MediaRepo(compose.activity.contentResolver))
        val state = scanner.stateOf(bucket)
        val items = (1L..count).map { id ->
            MediaItem(
                id = id, path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
                name = "IMG_$id.jpg", bucketId = bucket,
                dateTaken = 1_754_000_000_000L + id * 60_000L,
                dateModified = 1_754_000_000L + id, size = 1024L
            )
        }
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
        return items
    }
}
