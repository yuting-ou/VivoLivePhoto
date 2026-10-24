package com.vliveconvert.app

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 「清空 → 撤销」的真实点击链路：列表、撤销条与落盘队列必须一起回滚。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClearAllUndoUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val queueFile get() = File(compose.activity.filesDir, "convert_queue.json")

    @Before
    fun resetState() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.activity.runOnUiThread {
            ConvertCenter.items.clear()
            ConvertCenter.statusText = ""
        }
        queueFile.delete()
        compose.waitForIdle()
    }

    private fun mediaItem(id: Long) = MediaItem(
        id = id, path = "/storage/emulated/0/DCIM/Camera/IMG_$id.jpg",
        name = "IMG_$id.jpg", bucketId = 1L,
        dateTaken = 1_754_000_000_000L + id, dateModified = 1_754_000_000L + id,
        size = 1024L
    )

    @Test
    fun clearAllRestoresListAndPersistedQueueOnUndo() {
        compose.activity.runOnUiThread {
            ConvertCenter.items.add(ConvertItem(item = mediaItem(1)))
            ConvertCenter.items.add(ConvertItem(item = mediaItem(2)))
            ConvertCenter.persistQueue(app, ConvertCenter.itemsSnapshot())
        }
        compose.waitForIdle()
        assertTrue("前置：队列文件应已落盘", queueFile.exists())

        compose.onNodeWithText("清空").performClick()
        compose.waitForIdle()
        assertTrue("清空后列表必须为空", ConvertCenter.items.isEmpty())
        assertFalse("清空后落盘队列必须一并清掉", queueFile.exists())

        compose.onNodeWithText("撤销").performClick()
        compose.waitForIdle()
        assertEquals("撤销必须恢复全部条目", 2, ConvertCenter.items.size)
        assertEquals(listOf(1L, 2L), ConvertCenter.items.map { it.item.id })
        assertTrue("撤销必须把队列重新落盘", queueFile.exists())
        val json = queueFile.readText()
        assertTrue("落盘内容应包含恢复的条目", json.contains("IMG_1.jpg") && json.contains("IMG_2.jpg"))
    }
}
