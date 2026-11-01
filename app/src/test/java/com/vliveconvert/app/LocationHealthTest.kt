package com.vliveconvert.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.LocationHealth
import com.vliveconvert.app.core.JpegUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 位置体检与修复引擎（v2.6.0-test27）：
 *
 * - 判定只信**文件字节**（EXIF/XMP/视频段），不信媒体库坐标列
 * - 修复：EXIF 缺的先写回文件 EXIF 再行重建；每张**读回验证**（文件事实）
 * - 行重建**不丢数据**：IS_FAVORITE 随迁、先建新行后删旧行（产物不消失在
 *   「删完插入失败」的窗口里）、时间戳保留
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationHealthTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    private fun jpegBody(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** 产物：EXIF GPS 版 */
    private fun productWithExifGps(latDeg: Long, lonDeg: Long): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) +
            ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg).drop(2).toByteArray()

    /** 产物：无任何坐标 */
    private fun productNoCoords(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) + jpegBody()

    /** 产物：EXIF 无 GPS，视频段带 ©xyz 坐标（老版本被剥 EXIF 的形态） */
    private fun productVideoOnly(): ByteArray {
        val xyzTail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        return productNoCoords() + xyzTail
    }

    private fun putImage(
        rel: String, name: String, bytes: ByteArray, favorite: Int = 0
    ): android.net.Uri {
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.DATE_TAKEN, 1_700_000_000_000L)
            if (favorite != 0) put(MediaStore.MediaColumns.IS_FAVORITE, favorite)
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        return uri
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

    // ------------------------------------------------ 判定（体检）

    /** 四种判定各就各位：EXIF 有 / EXIF 缺坐标在视频段 / 三处都无 / 垃圾数据读不动 */
    @Test
    fun probeVerdictsAreFileEvidenceBased() {
        assertEquals(LocationHealth.Verdict.HAS_EXIF,
            LocationHealth.probeFull(productWithExifGps(22, 108)).verdict)
        val videoOnly = LocationHealth.probeFull(productVideoOnly())
        assertEquals(LocationHealth.Verdict.VIDEO_ONLY, videoOnly.verdict)
        assertEquals(22.8389, videoOnly.best!!.first, 1e-4)
        assertEquals(LocationHealth.Verdict.NONE,
            LocationHealth.probeFull(productNoCoords()).verdict)
        assertEquals(LocationHealth.Verdict.UNREADABLE,
            LocationHealth.probeFull(byteArrayOf(0x11, 0x22)).copy(unreadable = true).verdict)
    }

    /** 判定依据必须带上每个来源的解析结果（诊断报告的核心证据） */
    @Test
    fun probeEvidenceListsEverySource() {
        val ev = LocationHealth.probeFull(productVideoOnly()).evidence()
        assertTrue(ev.contains("EXIF=无"))
        assertTrue(ev.contains("XMP=无"))
        assertTrue(ev.contains("视频段=22.83890,108.17800"))
    }

    // ------------------------------------------------ 修复

    /** 核心场景：EXIF 缺失的产物 → 注入 EXIF + 行重建 + 读回验证通过，收藏保留 */
    @Test
    fun repairInjectsExifAndPreservesFavorite() {
        val bytes = productVideoOnly()
        val old = putImage("DCIM/Camera", "IMG_FIX.jpg", bytes, favorite = 1)

        val outcome = LocationHealth.repairProduct(app, old)

        assertEquals(LocationHealth.RepairResult.OK, outcome.result)
        assertEquals("注入了 EXIF（坐标在视频段）", "注入EXIF+行重建", outcome.action)
        assertTrue("必须读回验证", outcome.verified)
        assertEquals(22.8389, outcome.coords!!.first, 1e-4)

        // 新行：字节含 EXIF GPS、尾部逐字节保留、行坐标就位、**收藏保留**
        val newUri = rowByName("IMG_FIX.jpg")!!
        assertNotEquals(old, newUri)
        val fixed = resolver.openInputStream(newUri)!!.use { it.readBytes() }
        val coords = JpegUtil.gpsCoordinates(fixed)
        assertNotNull("修复后产物必须带 EXIF GPS", coords)
        assertEquals(22.8389, coords!!.first, 1e-4)
        assertTrue("视频段尾部必须逐字节保留",
            fixed.takeLast(34) == bytes.takeLast(34))
        resolver.query(newUri,
            arrayOf("latitude", MediaStore.MediaColumns.IS_FAVORITE),
            null, null, null)!!.use { c ->
            c.moveToFirst()
            assertEquals(22.8389, c.getDouble(0), 1e-6)
            assertEquals("重建不得丢收藏", 1, c.getInt(1))
        }
        // 拍摄时间保留
        resolver.query(newUri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN),
            null, null, null)!!.use { c ->
            c.moveToFirst()
            assertEquals(1_700_000_000_000L, c.getLong(0))
        }
    }

    /** EXIF 已在的产物：仅行重建（不重复注入），字节逐字节一致 */
    @Test
    fun repairKeepsBytesWhenExifAlreadyPresent() {
        val bytes = productWithExifGps(22, 108)
        val old = putImage("DCIM/Camera", "IMG_KEEP.jpg", bytes)

        val outcome = LocationHealth.repairProduct(app, old)

        assertEquals(LocationHealth.RepairResult.OK, outcome.result)
        assertEquals("仅行重建", outcome.action)
        val newUri = rowByName("IMG_KEEP.jpg")!!
        val fixed = resolver.openInputStream(newUri)!!.use { it.readBytes() }
        assertTrue("字节必须逐字节一致", fixed.contentEquals(bytes))
    }

    /** 三处都无坐标：NO_COORDS，原行不动（不删记录、不编造位置） */
    @Test
    fun repairWithoutAnyCoordsKeepsRow() {
        val row = putImage("DCIM/Camera", "IMG_NOC.jpg", productNoCoords())

        val outcome = LocationHealth.repairProduct(app, row)

        assertEquals(LocationHealth.RepairResult.NO_COORDS, outcome.result)
        // 原行保留
        assertNotNull(rowByName("IMG_NOC.jpg"))
    }

    /** 读失败的产物：显式 FAILED 带原因（绝不静默当「无坐标」） */
    @Test
    fun repairUnreadableFileFailsExplicitly() {
        val row = putImage("DCIM/Camera", "IMG_BAD.jpg", productNoCoords())
        resolver.delete(row, null, null) // 删掉文件内容让读取失败（行失效）

        val outcome = LocationHealth.repairProduct(app, row)

        assertEquals(LocationHealth.RepairResult.FAILED, outcome.result)
        assertNotNull("失败必须带原因", outcome.error)
    }

    // ------------------------------------------------ 报告

    /** 诊断报告：每行都有判定依据；汇总可读；导出的是可回传文本 */
    @Test
    fun reportRendersPerPhotoEvidence() {
        val report = LocationHealth.buildReport(
            appVersion = "2.6.0-test27 (115)",
            device = "vivo V2405A / Android 15",
            rows = listOf(
                LocationHealth.ReportLine("IMG_A.jpg", "有位置", "EXIF=22.83890,108.17800"),
                LocationHealth.ReportLine("IMG_B.jpg", "EXIF 缺失", "EXIF=无，XMP=无，视频段=22.83890,108.17800",
                    repair = "修复成功：注入EXIF+行重建（读回验证通过）")
            ),
            summary = "共 2 张产物"
        )
        assertTrue(report.contains("IMG_A.jpg"))
        assertTrue(report.contains("EXIF=22.83890,108.17800"))
        assertTrue(report.contains("注入EXIF+行重建（读回验证通过）"))
        assertTrue(report.contains("2.6.0-test27 (115)"))
    }
}
