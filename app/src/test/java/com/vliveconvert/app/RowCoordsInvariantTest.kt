package com.vliveconvert.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SegmentDigest
import com.vliveconvert.app.core.SingleWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 导出落库的坐标回归（不依赖坐标列查询做失败判定——真机实测该查询读不回坐标列，
 * test24 曾据此误判并回滚全部导出）：
 * - 新建插入：行坐标来自插入值（真机验证过「出生带坐标可显示」）
 * - 覆盖路径：一律行重建（删行重插、出生带坐标）——真机验证过的原语
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
        val dir = Files.createTempDirectory("vlc_reg").toFile()
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

    private fun putOldProduct(name: String, bytes: ByteArray): android.net.Uri {
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.MediaColumns.DATE_TAKEN, 1_700_000_000_000L)
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        return uri
    }

    /** 新建插入带坐标（真机验证过的显示路径，回归钉住） */
    @Test
    fun freshInsertCarriesRowCoords() {
        val stats = MediaExport.LocationStats()
        val uri = MediaExport.exportAndVerify(
            app, makeResult(jpegWithGps(22, 108), "IMG_FRESH"), timestamp = 1_700_000_000_000L,
            relPath = "DCIM/Camera", useCameraDir = true, stats = stats)
        val (lat, lon) = coordsOf(uri)
        assertEquals(22.0, lat!!, 1e-9)
        assertEquals(108.0, lon!!, 1e-9)
        assertEquals("EXIF 来源计入统计", 1, stats.exif)
        assertEquals(0, stats.rowRebuilt)
    }

    /** 覆盖路径：旧行（无坐标）→ 一律行重建，新行出生带坐标、旧行消失、字节一致 */
    @Test
    fun overwriteRebuildsRowBornWithCoords() {
        val bytes = jpegWithGps(22, 108)
        val old = putOldProduct("IMG_OVR.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val stats = MediaExport.LocationStats()

        val uri = MediaExport.exportAndVerify(
            app, makeResult(bytes, "IMG_OVR"), timestamp = 1_700_000_000_000L,
            relPath = "DCIM/Camera", useCameraDir = true,
            oldUriString = old.toString(), stats = stats)

        assertTrue("覆盖应走行重建并返回新行", uri != old)
        assertEquals("重建计数", 1, stats.rowRebuilt)
        val (lat, lon) = coordsOf(uri)
        assertEquals(22.0, lat!!, 1e-9)
        assertEquals(108.0, lon!!, 1e-9)
        assertEquals("字节逐字节一致", bytes.toList(),
            resolver.openInputStream(uri)!!.use { it.readBytes() }.toList())
        val dateTaken = resolver.query(uri,
            arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)!!.use { c ->
            c.moveToFirst(); c.getLong(0)
        }
        assertEquals("拍摄时间保留", 1_700_000_000_000L, dateTaken)
    }
}
