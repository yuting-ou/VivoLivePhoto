package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vliveconvert.app.service.BatchLogEntry
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「转换记录」里修复批次必须能被认出来，并且能打开系统相册里的产物 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class BatchLogRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun entry(repair: Boolean) = BatchLogEntry(
        timeMs = 1_700_000_000_000L, ok = 1, fail = 0, noGps = 0,
        reconvert = false, deletedOriginals = 0, movedToCamera = 0,
        dest = "DCIM/Camera",
        firstOutUri = "content://media/external/images/media/42",
        repair = repair
    )

    @Test
    fun repairBatchShowsMarkerAndOpensTheRepairedPhoto() {
        var opened: String? = null
        compose.setContent {
            VLiveConvertTheme {
                BatchLogRow(entry(repair = true)) { opened = it }
            }
        }
        compose.onNodeWithText("修复").assertExists()
        compose.onNodeWithText("查看产物 ›").performClick()
        compose.waitForIdle()
        assertEquals("content://media/external/images/media/42", opened)
    }

    @Test
    fun normalBatchHasNoRepairMarker() {
        compose.setContent {
            VLiveConvertTheme {
                BatchLogRow(entry(repair = false))
            }
        }
        compose.onNodeWithText("修复").assertDoesNotExist()
    }
}
