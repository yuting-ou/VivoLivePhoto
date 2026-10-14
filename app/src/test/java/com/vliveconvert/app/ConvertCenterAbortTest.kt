package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.service.QueueJson
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Android 15+ 对 dataSync 前台服务有 6 小时/日配额；超时系统回调 onTimeout 后只给几秒宽限。
 * 兜底动作（ConvertService.onTimeout → ConvertCenter.abortForSystemLimit）必须：
 * 状态复位 + **队列落盘**——队列是"进程被杀可续转"的唯一凭据，不落盘用户就只能从零再来
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertCenterAbortTest {

    private val context get() = ApplicationProvider.getApplicationContext<VliveApp>()

    private fun item(seq: Int) = ConvertItem(
        item = MediaItem(
            id = seq.toLong(), path = "/sdcard/DCIM/Camera/IMG_$seq.jpg",
            name = "IMG_$seq.jpg", bucketId = 1L,
            dateTaken = 1_700_000_000_000L, dateModified = 1_700_000_000L, size = 1024L,
        ),
        status = "转换中…",
    )

    @Before
    fun reset() {
        ConvertCenter.items.clear()
        ConvertCenter.isConverting = false
        ConvertCenter.progress = 0f
        ConvertCenter.progressDetail = ""
        ConvertCenter.statusText = ""
        File(context.filesDir, "convert_queue.json").delete()
    }

    @Test
    fun abortResetsStateAndPersistsQueue() {
        ConvertCenter.items.add(item(1))
        ConvertCenter.items.add(item(2))
        ConvertCenter.isConverting = true
        ConvertCenter.progress = 0.5f

        ConvertCenter.abortForSystemLimit(context)

        assertFalse("中止后不得再显示「转换中」", ConvertCenter.isConverting)
        assertEquals(0f, ConvertCenter.progress)
        assertTrue("必须明确告诉用户发生了什么、怎么办",
            ConvertCenter.statusText.contains("已中止并保存队列"))

        // 队列落盘：回到应用点「开始转换」可从断点继续
        val restored = QueueJson.fromJson(
            File(context.filesDir, "convert_queue.json").readText())
        assertEquals("未完成的两项必须留在队列里", 2, restored?.size)
    }

    @Test
    fun abortIsNoOpWhenNotConverting() {
        val before = ConvertCenter.statusText
        ConvertCenter.abortForSystemLimit(context)
        assertEquals("未在转换时不得动状态文案", before, ConvertCenter.statusText)
    }
}
