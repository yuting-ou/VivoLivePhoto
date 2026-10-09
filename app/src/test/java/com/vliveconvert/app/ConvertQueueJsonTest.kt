package com.vliveconvert.app

import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.QueueJson
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun roundTripPreservesGpsFields() {
        // v1.2.0 新字段：lostGps / outUri / reconvert 的往返无损
        val queue = listOf(
            ConvertItem(item = item(7, "IMG_1007.jpg"),
                status = "完成：已导出到相册 DCIM/Camera（源文件无 GPS 位置数据）",
                done = true, lostGps = true,
                outUri = "content://media/external/images/media/123"),
            ConvertItem(item = item(8, "IMG_1008.jpg"),
                status = "重新转换中…", reconvert = true, lostGps = true,
                outUri = "content://media/external/images/media/124"),
        )
        val restored = QueueJson.fromJson(QueueJson.toJson(queue))!!
        // 完成态条目全字段无损往返
        assertEquals(queue[0], restored[0])
        // 执行中条目恢复为「待转换」，重转语义字段保留（重转中断续转仍覆盖旧产物）
        assertEquals("待转换", restored[1].status)
        assertTrue(restored[1].reconvert)
        assertTrue(restored[1].lostGps)
        assertEquals("content://media/external/images/media/124", restored[1].outUri)
    }

    @Test
    fun legacyQueueDerivesLostGpsFromStatus() {
        // 旧版本（v1.1.x）队列无 lostGps 字段：已完成条目按状态文案推导，
        // 让老队列里丢位置的照片也能进「重新转换」入口
        val legacy = """[{
            "id": 9, "path": "/storage/emulated/0/DCIM/Camera/IMG_1009.jpg",
            "name": "IMG_1009.jpg", "bucketId": 1,
            "dateTaken": 1754000000000, "dateModified": 1754000000, "size": 2048,
            "status": "完成：已导出到相册 DCIM/Camera（源文件无 GPS 位置数据）",
            "done": true, "failed": false
        }, {
            "id": 10, "path": "/storage/emulated/0/DCIM/Camera/IMG_1010.jpg",
            "name": "IMG_1010.jpg", "bucketId": 1,
            "dateTaken": 1754000000001, "dateModified": 1754000001, "size": 2048,
            "status": "完成：已导出到相册 DCIM/Camera", "done": true, "failed": false
        }]"""
        val restored = QueueJson.fromJson(legacy)!!
        assertTrue("旧队列丢位置条目应推导出 lostGps", restored[0].lostGps)
        assertFalse("正常完成条目不应误标", restored[1].lostGps)
        assertNull("旧队列无 outUri 字段应为 null", restored[0].outUri)
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
