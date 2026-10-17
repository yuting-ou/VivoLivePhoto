package com.vliveconvert.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.core.JpegUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 行重建：让历史转换产物在相册里显示位置的机制。
 * 守住的边界：只重建「应用产物」（XMP 含 VCamera 标记）；无坐标也恢复不出的不删记录；
 * 重建后字节逐字节一致、坐标列就位；空间不足自动暂停；已处理名单可续跑。
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

    /** 老版本损坏产物夹具：标记在、EXIF 无 GPS、视频段带 ©xyz 坐标 */
    private fun damagedWithVideoGps(): ByteArray {
        val xyzTail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + xyzTail
    }

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

    private fun rowCount(uri: Uri): Int =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count } ?: 0
        }.getOrDefault(0)

    private fun findRowByName(name: String): Uri? =
        resolver.query(
            images, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name), null
        )?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(images, c.getLong(0))
            else null
        }

    /** 核心场景：产物被重建（新行带坐标、字节一致），已处理名单阻止二次处理 */
    @Test
    fun rebuildsOutputsAndSkipsProcessed() {
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputWithGps(22, 108))
        putImage("DCIM/Camera", "IMG_B.jpg", appOutputWithGps(23, 109))
        val processed = mutableSetOf<String>()

        val first = LocationDisplayRepair.rebuildAll(app, processed)
        assertEquals(2, first.rebuilt)
        assertEquals(0, first.remaining)
        assertTrue(processed.containsAll(setOf("IMG_A.jpg", "IMG_B.jpg")))

        // 第二轮：全部已处理，不再动任何行
        val second = LocationDisplayRepair.rebuildAll(app, processed)
        assertEquals(0, second.rebuilt)
        assertEquals(0, second.failed)
    }

    /** 空间不足：立即暂停，不动任何记录 */
    @Test
    fun pausesWhenFreeSpaceLow() {
        putImage("DCIM/Camera", "IMG_SPACE.jpg", appOutputWithGps(22, 108))
        val stats = LocationDisplayRepair.rebuildAll(
            app, mutableSetOf(),
            freeSpaceBytes = { 5L * 1024 * 1024 * 1024 },
            minFreeBytes = 10L * 1024 * 1024 * 1024
        )
        assertTrue("应因空间不足暂停", stats.pausedForSpace)
        assertEquals(0, stats.rebuilt)
        assertTrue(stats.remaining >= 1)
    }

    /** 相机原始双文件不是产物：不重建、但标记已处理避免反复嗅探 */
    @Test
    fun skipsCameraDualSources() {
        val dualJpg = putImage("DCIM/camera", "IMG_DUAL.jpg",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { 0x5A } +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        putVideo("DCIM/camera", "IMG_DUAL.mp4")
        val processed = mutableSetOf<String>()

        val stats = LocationDisplayRepair.rebuildAll(app, processed)
        assertEquals(0, stats.rebuilt)
        assertTrue(processed.contains("IMG_DUAL.jpg"))
        assertEquals(1, rowCount(dualJpg))
    }

    /** 小写目录（vivo 相机）下的产物同样被覆盖 */
    @Test
    fun lowercasePathRowsAreCovered() {
        putImage("DCIM/camera", "IMG_LOW.jpg", appOutputWithGps(31, 121))
        val stats = LocationDisplayRepair.rebuildAll(app, mutableSetOf())
        assertEquals(1, stats.rebuilt)
    }

    /**
     * 老版本损坏产物（EXIF 无 GPS、视频段有 ©xyz）：重建时恢复坐标写回 EXIF，
     * 视频段逐字节保留，新行带坐标列。
     */
    @Test
    fun recoversGpsFromVideoSegmentOnRebuild() {
        val damaged = damagedWithVideoGps()
        val tail = damaged.takeLast(34)
        putImage("DCIM/Camera", "IMG_DAMAGED.jpg", damaged)

        val stats = LocationDisplayRepair.rebuildAll(app, mutableSetOf())
        assertEquals(1, stats.rebuilt)

        val newUri = findRowByName("IMG_DAMAGED.jpg")!!
        val fixed = resolver.openInputStream(newUri)!!.use { it.readBytes() }
        // 视频段保留
        assertEquals(tail.toList(), fixed.takeLast(tail.size))
        // EXIF 恢复出坐标
        val got = JpegUtil.gpsCoordinates(fixed)
        assertNotNull("恢复后应含 EXIF GPS", got)
        assertEquals(22.8389, got!!.first, 1e-6)
        assertEquals(108.1780, got.second, 1e-6)
        // 行坐标
        val (lat, _) = readColumns(newUri)
        assertEquals(22.8389, lat!!, 1e-6)
    }

    /** 无坐标且视频段恢复不出：不删记录（noCoords 计数，原行保留） */
    @Test
    fun outputWithoutAnyCoordinatesIsKept() {
        val row = putImage("DCIM/Camera", "IMG_NOC.jpg",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + JpegUtil.buildXmpApp1(motionXmp) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val stats = LocationDisplayRepair.rebuildAll(app, mutableSetOf())
        assertEquals(0, stats.rebuilt)
        assertEquals(1, stats.noCoords)
        assertEquals("原记录必须保留", 1, rowCount(row))
    }

    /** 单张重建：字节逐字节一致、时间戳保留 */
    @Test
    fun recreateRowKeepsBytesAndTimestamps() {
        val bytes = appOutputWithGps(22, 108)
        val old = putImage("DCIM/Camera", "IMG_REBUILD.jpg", bytes)
        resolver.update(old, ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_TAKEN, 1_700_000_000_000L)
            put(MediaStore.MediaColumns.DATE_MODIFIED, 1_700_000_000L)
        }, null, null)

        assertEquals(LocationDisplayRepair.RecreateResult.OK,
            LocationDisplayRepair.recreateRow(app, old))
        assertEquals("旧行应被删除", 0, rowCount(old))

        val newUri = findRowByName("IMG_REBUILD.jpg")!!
        assertNotEquals(old, newUri)
        resolver.query(newUri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN,
            "latitude", "longitude"), null, null, null)?.use { c ->
            c.moveToFirst()
            assertEquals(1_700_000_000_000L, c.getLong(0))
            assertEquals(22.0, c.getDouble(1), 1e-6)
            assertEquals(108.0, c.getDouble(2), 1e-6)
        }
        val readBack = resolver.openInputStream(newUri)!!.use { it.readBytes() }
        assertTrue("重建后文件字节必须与原文件一致", readBack.contentEquals(bytes))
    }
}
