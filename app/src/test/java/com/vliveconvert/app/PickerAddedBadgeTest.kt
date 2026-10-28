package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.vliveconvert.app.picker.AlbumInfo
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.PickerScanner
import com.vliveconvert.app.picker.PickerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「已添加」角标：在当前待转换列表里的照片要能一眼看出来，
 * 否则用户不知道加过没有，会反复添加同一批。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerAddedBadgeTest {

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

    private fun render(
        items: List<MediaItem>,
        addedIds: Set<Long>,
        convertedPaths: Map<String, String> = emptyMap()
    ) {
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
                convertedPaths = convertedPaths,
                addedIds = addedIds,
                onBack = {},
                onConfirm = {}
            )
        }
    }

    private fun count(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun addedPhotoShowsBadgeAndOthersDoNot() {
        render(listOf(media(1), media(2)), addedIds = setOf(1L))
        compose.waitForIdle()
        assertEquals("只有已添加的那张有角标", 1, count("已添加"))
        assertEquals("未添加的不该出现角标", 0, count("已转"))
    }

    @Test
    fun addedBadgeWinsOverConverted() {
        val converted = mapOf(
            media(1).path.lowercase() to "content://media/external/images/media/99",
            media(2).path.lowercase() to "content://media/external/images/media/98"
        )
        render(listOf(media(1), media(2)), addedIds = setOf(1L), convertedPaths = converted)
        compose.waitForIdle()
        assertEquals("两张都转过了，但只有一张在列表里", 1, count("已添加"))
        assertEquals("已添加压掉已转", 1, count("已转"))
    }

    @Test
    fun withoutAddedSetKeepsConvertedBadge() {
        val converted = mapOf(
            media(1).path.lowercase() to "content://media/external/images/media/99"
        )
        render(listOf(media(1)), addedIds = emptySet(), convertedPaths = converted)
        compose.waitForIdle()
        assertEquals(0, count("已添加"))
        assertTrue("历史角标行为不变", count("已转") == 1)
    }
}
