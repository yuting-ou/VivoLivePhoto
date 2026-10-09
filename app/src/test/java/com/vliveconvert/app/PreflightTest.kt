package com.vliveconvert.app

import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.Preflight
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 转换前「影响面预估」的 JVM 回归测试。
 *
 * 背景：批量转换（尤其是开启「删除原图」后）在开跑前会弹出确认框，把可确定的事实
 * 摊开给用户。弹窗的数字若不准确——例如把源文件已丢失、注定转换失败的条目也算进
 * 「将删除原图」——会直接误导用户对不可逆操作的预期。本测试锁定统计口径。
 */
class PreflightTest {

    private fun tmpDir(): File = Files.createTempDirectory("vlc_preflight").toFile()

    private fun item(dir: File, name: String, id: Long): MediaItem = MediaItem(
        id = id,
        path = File(dir, name).absolutePath,
        name = name,
        bucketId = 1L,
        dateTaken = 0L,
        dateModified = 0L,
        size = 0L
    )

    /**
     * 待转换条目：jpg 存在则计入体积，存在伴生 mp4 则体积与视频数一并计入；
     * 源文件已丢失的只计入 pending/missing，不计体积与视频；
     * 已完成/已失败条目完全不计入。
     */
    @Test
    fun countsPendingMissingBytesAndCompanionVideos() {
        val dir = tmpDir()
        // A：jpg + 伴生 mp4（双文件实况）
        File(dir, "IMG_A.jpg").writeBytes(ByteArray(1000))
        File(dir, "IMG_A.mp4").writeBytes(ByteArray(500))
        // B：仅 jpg
        File(dir, "IMG_B.jpg").writeBytes(ByteArray(2000))
        // C：源已丢失（不建文件）
        // D / E：完成 / 失败，均不应计入
        val info = Preflight.compute(
            listOf(
                ConvertItem(item = item(dir, "IMG_A.jpg", 1L)),
                ConvertItem(item = item(dir, "IMG_B.jpg", 2L)),
                ConvertItem(item = item(dir, "IMG_C.jpg", 3L)),
                ConvertItem(item = item(dir, "IMG_D.jpg", 4L), done = true),
                ConvertItem(item = item(dir, "IMG_E.jpg", 5L), failed = true)
            )
        )

        assertEquals("只有 3 项处于待转换态", 3, info.pendingCount)
        assertEquals("源文件已丢失应单独计数", 1, info.missingSources)
        assertEquals("体积 = jpg(A) + mp4(A) + jpg(B)", 3500L, info.totalSourceBytes)
        assertEquals("仅 A 有伴生视频", 1, info.companionVideoCount)
    }

    /** 相机写出大写 `.MP4` 时也要计入（与 VivoDual 的大小写策略一致） */
    @Test
    fun uppercaseCompanionVideoIsCounted() {
        val dir = tmpDir()
        File(dir, "IMG_UP.jpg").writeBytes(ByteArray(100))
        File(dir, "IMG_UP.MP4").writeBytes(ByteArray(400))

        val info = Preflight.compute(listOf(ConvertItem(item = item(dir, "IMG_UP.jpg", 1L))))

        assertEquals(1, info.companionVideoCount)
        assertEquals(500L, info.totalSourceBytes)
    }

    /** 没有待转换条目时全部归零，不应误报 */
    @Test
    fun emptyOrAllFinishedYieldsZero() {
        assertEquals(0, Preflight.compute(emptyList()).pendingCount)

        val dir = tmpDir()
        File(dir, "IMG_DONE.jpg").writeBytes(ByteArray(4096))
        val info = Preflight.compute(
            listOf(ConvertItem(item = item(dir, "IMG_DONE.jpg", 1L), done = true))
        )
        assertEquals(0, info.pendingCount)
        assertEquals(0, info.missingSources)
        assertEquals(0L, info.totalSourceBytes)
        assertEquals(0, info.companionVideoCount)
    }

    /** 体积文案：<1MB 用 KB（无小数），>=1MB 用 MB（保留一位小数） */
    @Test
    fun formatSizeBoundaries() {
        assertEquals("0 KB", Preflight.formatSize(0L))
        assertEquals("0 KB", Preflight.formatSize(-5L))
        assertEquals("1 KB", Preflight.formatSize(1024L))
        assertEquals("1.0 MB", Preflight.formatSize(1024L * 1024))
        assertEquals("1.5 MB", Preflight.formatSize(1024L * 1536))
    }
}
