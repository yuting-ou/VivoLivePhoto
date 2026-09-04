package com.vliveconvert.app

import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.QueueJson
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转换队列 JSON 序列化的 JVM 回归测试。
 *
 * 背景：v1.0.7~1.0.8 队列持久化在 IO 线程直接迭代 Compose 的 SnapshotStateList，
 * 与主线程条目状态写并发 → ConcurrentModificationException → 转换中闪退。
 * v1.0.9 起序列化收口为纯函数（普通 List 进出），本测试锁定其往返无损与
 * 中断状态迁移语义。
 */
class ConvertQueueJsonTest {

    private fun item(id: Long, name: String) = MediaItem(
        id = id, path = "/storage/emulated/0/DCIM/Camera/$name",
        name = name, bucketId = 1L,
        dateTaken = 1754000000000L + id, dateModified = 1754000000L + id,
        size = 1024L + id
    )

    @Test
    fun roundTripPreservesAllFields() {
        val queue = listOf(
            ConvertItem(item = item(1, "IMG_1001.jpg"), status = "待转换"),
            ConvertItem(item = item(2, "IMG_1002.jpg"), status = "完成：已导出到相册 DCIM/Camera", done = true),
            ConvertItem(item = item(3, "IMG_1003.jpg"), status = "失败：内存不足（文件过大）", failed = true),
        )
        val restored = QueueJson.fromJson(QueueJson.toJson(queue))
        assertNotNull(restored)
        assertEquals(3, restored!!.size)
        // 逐项全字段比对（data class equals）
        assertEquals(queue, restored)
    }

    @Test
    fun interruptedConvertingResetsToPending() {
        val queue = listOf(
            ConvertItem(item = item(5, "IMG_1005.jpg"), status = "转换中…"))
        val restored = QueueJson.fromJson(QueueJson.toJson(queue))!!
        assertEquals("中断的「转换中…」应重置为「待转换」", "待转换", restored[0].status)
    }

    @Test
    fun deferredFinalizeDegradesWithGuidance() {
        // 「移到相机+删原图」中转条目：进程中断后原图可能仍在，同名会冲突 →
        // 恢复时降级为明确指引，不能继续宣称「待原图删除后落地」
        val queue = listOf(
            ConvertItem(item = item(6, "IMG_1006.jpg"),
                status = "完成：待原图删除后以原名移入相机相册", done = true))
        val restored = QueueJson.fromJson(QueueJson.toJson(queue))!!
        assertTrue("中转状态应降级为指引文案",
            restored[0].status.contains("移到相机"))
    }

    @Test
    fun corruptJsonReturnsNull() {
        assertNull(QueueJson.fromJson("not json at all"))
        assertNull(QueueJson.fromJson(""))
        assertNull(QueueJson.fromJson("[{\"broken\":true}"))
    }

    @Test
    fun pendingCountSemanticUnchanged() {
        // 未完成条数 = done/failed 均为 false 的项（恢复提示用）
        val queue = listOf(
            ConvertItem(item = item(1, "a.jpg")),
            ConvertItem(item = item(2, "b.jpg"), done = true),
            ConvertItem(item = item(3, "c.jpg"), failed = true),
            ConvertItem(item = item(4, "d.jpg"), status = "待转换"),
        )
        val restored = QueueJson.fromJson(QueueJson.toJson(queue))!!
        assertEquals(2, restored.count { !it.done && !it.failed })
    }
}
