package com.vliveconvert.app

import android.content.ContentValues
import android.net.Uri
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * 导出时把拍摄坐标写入 MediaStore 的 latitude/longitude 列。
 *
 * 背景（2026-10 真机结论）：vivo 相册的「位置」显示读媒体库列、不回读文件 EXIF；
 * 相机拍照时由系统以特权身份写入该列，应用插入的行恒为 NULL——所以转换产物
 * 字节里坐标完好、相册却显示不出。修复 = 导出三路径（新建插入 / 原地覆盖 /
 * moveUriToCamera 回退复制）都把坐标随行写入，与相机行为对齐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportLocationColumnsTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val collection
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 构造带 EXIF GPS（SRATIONAL，vivo 同款）的最小 JPEG：SOI + APP1(Exif) + EOI */
    internal fun jpegWithGps(latDeg: Long, lonDeg: Long): ByteArray {
        val tiff = ByteArrayOutputStream()
        tiff.write(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08))
        // IFD0：1 项 GPSInfo(0x8825)→26；next=0
        tiff.write(byteArrayOf(0x00, 0x01))
        tiff.write(byteArrayOf(
            0x88.toByte(), 0x25, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1A))
        tiff.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        // GPS IFD @26：4 项，lat@80 lon@104，next=0
        tiff.write(byteArrayOf(0x00, 0x04))
        fun entry(tag: Int, typ: Int, cnt: Long, value: Long) {
            fun i32(v: Long) = byteArrayOf(
                ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            fun i16(v: Long) = byteArrayOf(
                ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            tiff.write(i16(tag.toLong()) + i16(typ.toLong()) + i32(cnt) + i32(value))
        }
        entry(1, 2, 2, 0x4E000000L)      // "N\0\0\0"
        entry(2, 10, 3, 80L)             // SRATIONAL×3
        entry(3, 2, 2, 0x45000000L)      // "E\0\0\0"
        entry(4, 10, 3, 104L)
        tiff.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        fun rational(n: Long, d: Long) {
            fun i32(v: Long) = byteArrayOf(
                ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
            tiff.write(i32(n) + i32(d))
        }
        rational(latDeg, 1); rational(0, 1); rational(0, 1)     // @80
        rational(lonDeg, 1); rational(0, 1); rational(0, 1)     // @104
        val tiffBytes = tiff.toByteArray()
        val payloadLen = 6 + tiffBytes.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(),
                ((payloadLen shr 8) and 0xFF).toByte(), (payloadLen and 0xFF).toByte()) +
            byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiffBytes +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** 无 EXIF 的裸 JPEG */
    private fun jpegBare(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { 0x5A } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun makeResult(bytes: ByteArray, stem: String): SingleWriteResult {
        val dir = Files.createTempDirectory("vlc_gps").toFile()
        val f = File(dir, "$stem.jpg").apply { writeBytes(bytes) }
        return SingleWriteResult(
            f.path,
            listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes)))
        )
    }

    private fun readColumns(uri: Uri): Pair<Double?, Double?> {
        // MediaColumns.LATITUDE/LONGITUDE 常量在 API 36 从 SDK 移除，但 Provider 的列仍在
        // （真机 shell --projection latitude 可查），故用字面量列名
        resolver.query(uri, arrayOf("latitude", "longitude"), null, null, null)?.use { c ->
            c.moveToFirst()
            val lat = if (c.isNull(0)) null else c.getDouble(0)
            val lon = if (c.isNull(1)) null else c.getDouble(1)
            return lat to lon
        }
        throw AssertionError("行查询失败")
    }

    /** 新建插入：产物带 GPS EXIF → 行坐标列应为对应十进制度（相机写入方式的镜像） */
    @Test
    fun insertCarriesGpsColumns() {
        val result = makeResult(jpegWithGps(22, 108), "IMG_GPS")
        val uri = MediaExport.exportAndVerify(
            app, result, timestamp = 1_700_000_000_000L,
            relPath = "Pictures/GpsTest", useCameraDir = false
        )
        val (lat, lon) = readColumns(uri)
        assertNotNull("纬度列应写入", lat)
        assertEquals(22.0, lat!!, 1e-9)
        assertEquals(108.0, lon!!, 1e-9)
    }

    /** 新建插入：产物无 GPS → 不写坐标（保持 NULL，不得写 0,0 假位置） */
    @Test
    fun insertWithoutGpsKeepsColumnsNull() {
        val result = makeResult(jpegBare(), "IMG_NOGPS")
        val uri = MediaExport.exportAndVerify(
            app, result, timestamp = 1_700_000_000_000L,
            relPath = "Pictures/GpsTest", useCameraDir = false
        )
        val (lat, lon) = readColumns(uri)
        assertNull(lat)
        assertNull(lon)
    }

    /** 原地覆盖：旧行无坐标，新产物带 GPS → 覆盖后行坐标应刷新 */
    @Test
    fun overwriteRefreshesGpsColumns() {
        val rel = "Pictures/GpsOverwrite"
        val oldUri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_OLD.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        })!!
        // 旧产物需能被识别为「既往产物」（XMP 含 MotionPhoto），否则不会走覆盖路径
        val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
            """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
            """xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" """ +
            """GCamera:MotionPhoto="1"></rdf:RDF></x:xmpmeta>"""
        resolver.openOutputStream(oldUri, "w")?.use { it.write(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + JpegUtil.buildXmpApp1(motionXmp) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte())) }

        val newBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) + jpegWithGps(30, 120).drop(2).toByteArray()
        val returned = MediaExport.exportAndVerify(
            app, makeResult(newBytes, "IMG_OLD"), timestamp = 1_700_000_000_000L,
            relPath = rel, useCameraDir = false
        )
        assertEquals("应走原地覆盖", oldUri, returned)
        val (lat, lon) = readColumns(oldUri)
        assertEquals("覆盖后坐标应刷新", 30.0, lat!!, 1e-9)
        assertEquals(120.0, lon!!, 1e-9)
    }
}
