package com.vliveconvert.app

import com.vliveconvert.app.service.Preflight
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 关1「先确定每一张都有位置」的普查算术：
 * 权限已授予（门禁保证）时读到的源字节即真实拍摄状态——
 * 有 GPS 的转后保留，无 GPS 的是拍摄时未记录（非转换丢失）。
 */
class PreflightGpsCensusTest {

    /** 与 ExportLocationColumnsTest 同源的带 GPS JPEG（直接复用其构造） */
    private fun jpegWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg)

    private fun jpegWithoutGps(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun tempFile(bytes: ByteArray): String {
        val dir = Files.createTempDirectory("vlc_census").toFile()
        return File.createTempFile("census", ".jpg", dir)
            .apply { writeBytes(bytes) }.absolutePath
    }

    @Test
    fun censusCountsBothKinds() {
        val paths = listOf(
            tempFile(jpegWithGps(22, 108)),
            tempFile(jpegWithGps(31, 121)),
            tempFile(jpegWithoutGps())
        )
        val census = Preflight.countSourceGps(paths)
        assertEquals(2, census.withGps)
        assertEquals(1, census.withoutGps)
    }

    @Test
    fun unreadableFilesAreSkippedNotCounted() {
        // 源文件消失/读不了不计入任何一侧（存在性由 Preflight.compute 单独披露）
        val census = Preflight.countSourceGps(listOf("/nonexistent/path.jpg"))
        assertEquals(0, census.withGps)
        assertEquals(0, census.withoutGps)
    }
}
