package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 队列文件的并发写安全。
 *
 * 修的缺陷：persistQueue 曾是裸 `writeText`（截断+写）。服务里两个并行 job 每完成一项
 * 就持久化一次，与 Activity 侧的增删/收尾持久化并发——两个写者一交错就把 JSON 写坏，
 * 冷启动 `fromJson` 解析失败返回 null → **整个队列静默丢弃**（v1.7.15 修的正是
 * 「队列是进程被杀可续转的唯一凭据」，这里等于在写侧重新打开了同一个洞）。
 *
 * 现在是「临时文件 + 同目录 rename（原子）+ 写者串行锁」。
 * 本用例让 4 个写者持续写不同的合法负载、读者连续解析：任何一次读到的必须是
 * **完整的一份负载**，不能是交错/截断的碎片。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueAtomicWriteTest {

    private fun item(seq: Int) = ConvertItem(
        item = MediaItem(
            id = seq.toLong(), path = "/sdcard/DCIM/Camera/IMG_$seq.jpg",
            name = "IMG_$seq.jpg", bucketId = 1L,
            dateTaken = 1_700_000_000_000L + seq, dateModified = 1_700_000_000L, size = 1024L * seq,
        ),
        status = "status-$seq",
    )

    @Test
    fun concurrentWritersNeverTearTheQueueFile() {
        val context = ApplicationProvider.getApplicationContext<VliveApp>()
        val target = File(context.filesDir, "convert_queue.json")
        target.delete()

        val writers = 4
        val writesPerWriter = 60
        val done = CountDownLatch(writers)
        val stop = AtomicBoolean(false)
        val torn = AtomicLong(0)
        val reads = AtomicLong(0)

        val writerThreads = (0 until writers).map { w ->
            Thread {
                repeat(writesPerWriter) { i ->
                    // 负载加大（~40KB）以拉宽「截断→写完」的竞态窗口
                    val payload = List(60) { k -> item(w * 1000 + i * 10 + k) }
                    ConvertCenter.persistQueue(context, payload)
                }
                done.countDown()
            }
        }

        val reader = Thread {
            while (!stop.get()) {
                try {
                    val bytes = target.readBytes()          // 与写者并发读
                    if (bytes.isNotEmpty()) {
                        reads.incrementAndGet()
                        // 必须能解析出一份完整 JSON；碎片/交错在此现形
                        com.vliveconvert.app.service.QueueJson.fromJson(String(bytes))
                            ?: torn.incrementAndGet()
                    }
                } catch (_: Exception) {
                    // 文件被原子替换的瞬间 read 可能撞上 rename——这不是撕裂
                }
            }
        }

        writerThreads.forEach { it.start() }
        reader.start()
        done.await()
        stop.set(true)
        reader.join()

        assertTrue("读者一次都没读到？量具没跑起来", reads.get() > 100)
        assertTrue(
            "并发写产生了 ${torn.get()} 次撕裂读（读到无法解析的队列文件），队列文件会被写坏",
            torn.get() == 0L
        )
    }

    @Test
    fun failedWriteLeavesPreviousFileIntact() {
        // 原子写的另一半承诺：写入失败不得破坏旧文件（writeText 会先把旧内容截断掉）
        val context = ApplicationProvider.getApplicationContext<VliveApp>()
        val good = listOf(item(1))
        ConvertCenter.persistQueue(context, good)

        // 把目录改成只读，迫使临时文件创建失败
        val filesDir = context.filesDir
        val oldWritable = filesDir.setWritable(false, false)
        try {
            if (!oldWritable) return   // 环境不支持（如以 root 跑）：本条退化跳过，不作假证据
            ConvertCenter.persistQueue(context, listOf(item(2), item(3)))
        } finally {
            filesDir.setWritable(true, false)
        }

        val restored = com.vliveconvert.app.service.QueueJson.fromJson(
            File(filesDir, "convert_queue.json").readText())
        assertTrue("写入失败后旧队列必须原样保留", restored?.size == 1)
    }
}
