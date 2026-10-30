package com.vliveconvert.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SegmentDigest
import com.vliveconvert.app.core.SingleWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * 零星丢位置根因的修复（100 张丢 4~5 张的真机现象）：
 *
 * **根因**：原地覆盖路径用 UPDATE 刷坐标列，而 vivo 对既有行的坐标列 UPDATE
 * **静默忽略**（2026-10 真机结论）——凡「同一张照片此前转过一次、那次产物出生时
 * 没坐标」的重转，文件字节里有 GPS、媒体库行却刷不进坐标，相册就不显示位置。
 * 「找回位置」的行重建能修好，正说明 UPDATE 不行、重建才行。
 *
 * 修复：覆盖后复查坐标，UPDATE 没生效且不一致 → 行重建（删行重插、出生带坐标）。
 * Robolectric 的 MediaProvider 会执行坐标 UPDATE（真机 vivo 不会），因此本类用
 * 「直接调用行重建」+「坐标比对纯函数」钉住修复语义；「UPDATE 后复查」的分支
 * 由 ExportLocationColumnsTest 的覆盖用例继续覆盖（Robolectric 下走 UPDATE 生效路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverwriteLocationRebuildTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    private fun jpegWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg)

    private fun makeResult(bytes: ByteArray, stem: String): SingleWriteResult {
        val dir = Files.createTempDirectory("vlc_rebuild").toFile()
        val f = File(dir, "$stem.jpg").apply { writeBytes(bytes) }
        return SingleWriteResult(
            f.path,
            listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes)))
        )
    }

    /** 出生即无坐标的既往产物行（模拟脱敏时代的旧产物） */
    private fun putCoordlessRow(name: String, bytes: ByteArray): android.net.Uri {
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.MediaColumns.DATE_TAKEN, 1_700_000_000_000L)
            put(MediaStore.MediaColumns.DATE_MODIFIED, 1_700_000_000L)
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        return uri
    }

    private fun coordsOf(uri: android.net.Uri): Pair<Double?, Double?> {
        resolver.query(uri, arrayOf("latitude", "longitude"), null, null, null)?.use { c ->
            c.moveToFirst()
            return (if (c.isNull(0)) null else c.getDouble(0)) to
                (if (c.isNull(1)) null else c.getDouble(1))
        }
        throw AssertionError("行查询失败")
    }

    private fun rowByName(name: String): android.net.Uri? =
        resolver.query(
            images, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name), null
        )?.use { c ->
            if (c.moveToFirst())
                android.content.ContentUris.withAppendedId(images, c.getLong(0))
            else null
        }

    /** 坐标比对：容忍 EXIF 有理数与十进制度互转的低位精度差 */
    @Test
    fun coordsMatchToleratesRoundingOnly() {
        val a = 22.8389 to 108.1780
        assertTrue(MediaExport.coordsMatch(a, 22.8389001 to 108.1779999))
        assertTrue("1e-6 之外必须视为不同", !MediaExport.coordsMatch(a, 22.84 to 108.178))
        assertTrue(MediaExport.coordsMatch(null, null))
        assertTrue("一侧有一侧没有必须重建", !MediaExport.coordsMatch(a, null))
    }

    /** 行重建：旧行（无坐标）→ 新行带坐标、文件名不变、字节逐字节一致、旧行消失 */
    @Test
    fun rebuildGivesNewRowBornWithCoords() {
        val bytes = jpegWithGps(22, 108)
        val old = putCoordlessRow("IMG_REBUILD.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val src = File(makeResult(bytes, "IMG_REBUILD").path)

        val newUri = MediaExport.rebuildRowWithCoords(
            resolver, old, src, 1_700_000_000_000L, 22.0 to 108.0)

        assertNotNull("重建应产出新行", newUri)
        assertTrue("旧行应已删除（文件名空出，无 (1) 序号）", newUri != old)
        val current = rowByName("IMG_REBUILD.jpg")!!
        val (lat, lon) = coordsOf(current)
        assertEquals("新行应出生带坐标（vivo 相册可显示）", 22.0, lat!!, 1e-9)
        assertEquals(108.0, lon!!, 1e-9)
        assertEquals("字节逐字节一致", bytes.toList(),
            resolver.openInputStream(current)!!.use { it.readBytes() }.toList())
        val dateTaken = resolver.query(current,
            arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)!!.use { c ->
            c.moveToFirst(); c.getLong(0)
        }
        assertEquals("拍摄时间保留", 1_700_000_000_000L, dateTaken)
    }

    /** 兜底链：产物 EXIF 无 GPS 但视频段带坐标（vivo ©xyz）→ 导出行仍应带坐标 */
    @Test
    fun exportFallsBackToVideoSegmentCoords() {
        val xyzTail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + xyzTail
        val dir = Files.createTempDirectory("vlc_fallback").toFile()
        val f = File(dir, "IMG_VIDGPS.jpg").apply { writeBytes(bytes) }

        val uri = MediaExport.exportAndVerify(
            app, SingleWriteResult(
                f.path,
                listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes)))
            ),
            timestamp = 1_700_000_000_000L,
            relPath = "DCIM/Camera", useCameraDir = true)

        assertNotNull(uri)
        val (lat, lon) = coordsOf(uri)
        assertEquals("EXIF 缺失时应从视频段恢复坐标写列", 22.8389, lat!!, 1e-6)
        assertEquals(108.1780, lon!!, 1e-6)
    }

    /** 无任何坐标来源（EXIF/视频段都没有）→ 列保持 NULL（不得写 0,0 假位置） */
    @Test
    fun exportWithoutAnyCoordsKeepsColumnsNull() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val dir = Files.createTempDirectory("vlc_nocoords").toFile()
        val f = File(dir, "IMG_NONE.jpg").apply { writeBytes(bytes) }

        val uri = MediaExport.exportAndVerify(
            app, SingleWriteResult(
                f.path,
                listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes)))
            ),
            timestamp = 1_700_000_000_000L,
            relPath = "DCIM/Camera", useCameraDir = true)
        val (lat, _) = coordsOf(uri)
        assertNull(lat)
    }
}
