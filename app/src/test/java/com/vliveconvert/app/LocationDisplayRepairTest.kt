package com.vliveconvert.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.core.JpegUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 位置显示恢复工具：给历史转换产物补写媒体库坐标列。
 * 守住的边界：只动「应用产物」（XMP 含 VCamera 标记）的行；相机原始双文件与
 * 无坐标的产物一律不碰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationDisplayRepairTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos
        get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    private fun jpegWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg)

    /** 应用产物夹具：XMP 带 VCamera 标记 + EXIF 带 GPS（真实产物两者皆有） */
    private fun appOutputWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) +
            jpegWithGps(latDeg, lonDeg).drop(2).toByteArray()

    private fun jpegBare(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { 0x5A } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun putImage(rel: String, name: String, bytes: ByteArray): Uri {
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        return uri
    }

    private fun putVideo(rel: String, name: String) {
        resolver.insert(videos, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        })
    }

    private fun readColumns(uri: Uri): Pair<Double?, Double?> {
        resolver.query(uri, arrayOf("latitude", "longitude"), null, null, null)?.use { c ->
            c.moveToFirst()
            val lat = if (c.isNull(0)) null else c.getDouble(0)
            val lon = if (c.isNull(1)) null else c.getDouble(1)
            return lat to lon
        }
        throw AssertionError("行查询失败")
    }

    @Test
    fun repairsAppOutputsWithCoordinates() {
        val out = putImage("DCIM/Camera", "IMG_OUT1.jpg", appOutputWithGps(22, 108))
        // 行内预置错误坐标，验证被正确值覆盖
        resolver.update(out, ContentValues().apply {
            put("latitude", 1.0); put("longitude", 2.0)
        }, null, null)

        val stats = LocationDisplayRepair.repair(app)

        assertEquals(1, stats.outputs)
        assertEquals(1, stats.repaired)
        val (lat, lon) = readColumns(out)
        assertEquals(22.0, lat!!, 1e-9)
        assertEquals(108.0, lon!!, 1e-9)
    }

    @Test
    fun skipsCameraDualSources() {
        // 相机原始双文件：jpg 无应用标记 + 同名 mp4 伴生
        val dualJpg = putImage("DCIM/camera", "IMG_DUAL.jpg", jpegWithGps(30, 120))
        putVideo("DCIM/camera", "IMG_DUAL.mp4")

        val stats = LocationDisplayRepair.repair(app)

        assertEquals("双文件源不是应用产物，不得计入", 0, stats.outputs)
        assertEquals(0, stats.repaired)
        val (lat, _) = readColumns(dualJpg)
        assertNull("源文件行不得被改动", lat)
    }

    @Test
    fun countsAppOutputsWithoutGps() {
        putImage("DCIM/Camera", "IMG_NOGPS.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + JpegUtil.buildXmpApp1(motionXmp) + jpegBare().drop(2).toByteArray())

        val stats = LocationDisplayRepair.repair(app)

        assertEquals(1, stats.outputs)
        assertEquals(0, stats.repaired)
        assertEquals(1, stats.noGps)
    }

    @Test
    fun lowercasePathRowsAreCovered() {
        // vivo 相机目录的小写形态也要被扫到（应用产物可能落在任一形态下）
        putImage("DCIM/camera", "IMG_LOW.jpg", appOutputWithGps(31, 121))
        val stats = LocationDisplayRepair.repair(app)
        assertEquals(1, stats.outputs)
        assertEquals(1, stats.repaired)
    }

    /** 解析器烟测：夹具能解出坐标（与修复主链路共用 JpegUtil.gpsCoordinates） */
    @Test
    fun fixtureParses() {
        val got = JpegUtil.gpsCoordinates(jpegWithGps(22, 108))
        assertNotNull(got)
    }

    /**
     * 端到端恢复：产物 JPEG 无 GPS（老版本被系统剥掉）但视频段带 ©xyz 坐标 →
     * 修复后文件 EXIF 有坐标、**尾部视频字节原样**、坐标列写入。
     */
    @Test
    fun recoversGpsFromVideoSegmentAndWritesBack() {
        val xyzTail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        // 无 EXIF GPS 的产物：SOI + 标记 XMP + SOS/EOI + 视频段
        val damaged = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + xyzTail
        val row = putImage("DCIM/Camera", "IMG_DAMAGED.jpg", damaged)

        val stats = LocationDisplayRepair.repair(app)
        assertEquals(1, stats.outputs)
        assertEquals("应从视频段恢复 1 张", 1, stats.restored)

        val fixed = resolver.openInputStream(row)!!.use { it.readBytes() }
        // 尾部视频字节原样保留
        assertEquals("视频段必须逐字节保留",
            xyzTail.toList(), fixed.takeLast(xyzTail.size))
        // JPEG 部分恢复出坐标（androidx ExifInterface 写入的 GPS）
        val got = JpegUtil.gpsCoordinates(fixed)
        assertNotNull("恢复后文件应含 EXIF GPS", got)
        assertEquals(22.8389, got!!.first, 1e-6)
        assertEquals(108.1780, got.second, 1e-6)
        // 恢复流程同时尝试坐标列写入（Robolectric 里应成功）
        val (lat, _) = readColumns(row)
        assertEquals(22.8389, lat!!, 1e-6)
    }
}
