package com.vliveconvert.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SegmentDigest
import com.vliveconvert.app.core.SingleWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 写入后不变式（「数量多起来必丢位置」的通用自愈）：
 * **入库行坐标必须等于文件坐标**——批量插入时坐标列偶发被丢（ROM 侧行为，真机存在、
 * Robolectric 无法复现）→ ensureRowCoords 当场发现，UPDATE 拉齐；UPDATE 不生效的
 * ROM（vivo）走行重建。拉不齐 = 本张失败，绝不静默交付。
 *
 * Robolectric 的 UPDATE 会生效，因此这里钉住「UPDATE 生效即拉齐」分支与统计口径；
 * 「UPDATE 被忽略 → 重建」分支由 OverwriteLocationRebuildTest 的直接重建用例钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RowCoordsInvariantTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun jpegWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg)

    private fun makeResult(bytes: ByteArray, stem: String): SingleWriteResult {
        val dir = Files.createTempDirectory("vlc_invariant").toFile()
        val f = File(dir, "$stem.jpg").apply { writeBytes(bytes) }
        return SingleWriteResult(
            f.path,
            listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes)))
        )
    }

    private fun coordsOf(uri: android.net.Uri): Pair<Double?, Double?> {
        resolver.query(uri, arrayOf("latitude", "longitude"), null, null, null)?.use { c ->
            c.moveToFirst()
            return (if (c.isNull(0)) null else c.getDouble(0)) to
                (if (c.isNull(1)) null else c.getDouble(1))
        }
        throw AssertionError("行查询失败")
    }

    /** 行出生无坐标（批量插入被丢）→ ensureRowCoords 用 UPDATE 拉齐并计数 */
    @Test
    fun healsCoordlessRowToMatchFile() {
        val bytes = jpegWithGps(22, 108)
        // 行出生时故意不带坐标列（模拟 ROM 批量插入丢失）
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_HEAL.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        val src = File(makeResult(bytes, "IMG_HEAL").path)
        val stats = MediaExport.LocationStats()

        val healed = MediaExport.ensureRowCoords(
            resolver, uri, src, 1_700_000_000_000L, 22.0 to 108.0, stats)

        assertEquals(uri, healed)
        val (lat, _) = coordsOf(uri)
        assertEquals("行坐标应与文件一致", 22.0, lat!!, 1e-9)
        assertEquals("自愈计数应记 1", 1, stats.rowHealed)
        assertEquals(0, stats.rowFailed)
    }

    /** 行坐标与文件一致 → 原样返回（不重建不换行） */
    @Test
    fun matchingRowIsReturnedAsIs() {
        val bytes = jpegWithGps(31, 121)
        val src = File(makeResult(bytes, "IMG_MATCH").path)
        val exportStats = MediaExport.LocationStats()
        val uri = MediaExport.exportAndVerify(
            app, makeResult(bytes, "IMG_MATCH"), timestamp = 1_700_000_000_000L,
            relPath = "DCIM/Camera", useCameraDir = true, stats = exportStats)
        assertEquals("EXIF 来源应计入统计", 1, exportStats.exif)
        val stats = MediaExport.LocationStats()

        val out = MediaExport.ensureRowCoords(
            resolver, uri, src, 1_700_000_000_000L, 31.0 to 121.0, stats)

        assertEquals(uri, out)
        assertEquals("一致时不应记自愈", 0, stats.rowHealed)
        val (lat, _) = coordsOf(uri)
        assertEquals(31.0, lat!!, 1e-9)
    }
}
