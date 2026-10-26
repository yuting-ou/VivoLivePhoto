package com.vliveconvert.app

import com.vliveconvert.app.convert.InPlaceMerge
import com.vliveconvert.app.core.SegmentDigest
import com.vliveconvert.app.core.SingleWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 原地合并的安全语义（纯 JVM）：
 * - 成功路径：源 .jpg 被产物原子替换，`.merging` 临时件不残留，mtime 对齐拍摄时间；
 * - **自检必须发生在 rename 之前**：任何失败路径（自检不过 / 暂存件缺失）下
 *   源文件字节必须与替换前完全一致——这是本实验特性不可妥协的底线。
 */
class InPlaceMergeTest {

    private val ts = 1_700_000_000_000L

    private fun dir(): File = Files.createTempDirectory("inplace").toFile()

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }

    private fun resultOf(content: ByteArray, staged: File) = SingleWriteResult(
        path = staged.absolutePath,
        segments = listOf(SegmentDigest("all", 0, content.size, md5(content))),
        sourceHasGps = true,
        sourceFingerprint = "fp"
    )

    @Test
    fun replacesSourceAtomically() {
        val d = dir()
        val oldBytes = ByteArray(512) { 1 }
        val newBytes = ByteArray(900) { 2 }
        val staged = File(d, "staged.jpg").apply { writeBytes(newBytes) }
        val source = File(d, "IMG_a.jpg").apply { writeBytes(oldBytes) }

        assertTrue(InPlaceMerge.replaceSource(resultOf(newBytes, staged), source.absolutePath, ts))

        assertEquals("源文件必须变成产物内容", newBytes.toList(), source.readBytes().toList())
        assertFalse("临时件不得残留", File(d, "IMG_a.jpg.merging").exists())
        assertEquals("mtime 应对齐拍摄时间", ts, source.lastModified())
    }

    @Test
    fun verifyFailureLeavesSourceByteIdentical() {
        val d = dir()
        val oldBytes = ByteArray(512) { 1 }
        val junkBytes = ByteArray(900) { 9 }
        // staged 内容与 segments 描述不一致 → 自检必须失败
        val staged = File(d, "staged.jpg").apply { writeBytes(junkBytes) }
        val source = File(d, "IMG_b.jpg").apply { writeBytes(oldBytes) }

        assertFalse(InPlaceMerge.replaceSource(resultOf(oldBytes, staged), source.absolutePath, ts))

        assertEquals("自检失败时源文件必须原样保留", oldBytes.toList(), source.readBytes().toList())
        assertFalse(File(d, "IMG_b.jpg.merging").exists())
    }

    @Test
    fun missingStagedFileFailsSafely() {
        val d = dir()
        val oldBytes = ByteArray(512) { 1 }
        val source = File(d, "IMG_c.jpg").apply { writeBytes(oldBytes) }
        val ghost = File(d, "ghost.jpg").apply { writeBytes(ByteArray(64) { 3 }) }
        ghost.delete()

        assertFalse(InPlaceMerge.replaceSource(resultOf(ByteArray(64) { 3 }, ghost), source.absolutePath, ts))
        assertEquals(oldBytes.toList(), source.readBytes().toList())
    }

    @Test
    fun missingSourceFailsSafely() {
        val d = dir()
        val bytes = ByteArray(64) { 3 }
        val staged = File(d, "staged.jpg").apply { writeBytes(bytes) }
        val source = File(d, "IMG_d.jpg") // 不存在

        assertFalse(InPlaceMerge.replaceSource(resultOf(bytes, staged), source.absolutePath, ts))
        assertFalse("不得凭空创建源文件", source.exists())
    }
}
