package com.vliveconvert.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.convert.LocationHealth
import com.vliveconvert.app.service.ConvertedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「找回位置」体检（v2.6.0-test27 重做）：
 * 扫描对**全部本工具产物**逐张给出文件事实判定（EXIF/XMP/视频段），
 * **绝不读媒体库坐标列**（test24~26 已证其在 vivo 上读不回——列里有坐标的行
 * 若文件无 EXIF GPS，仍按「无坐标」上报，这是钉死语义的用例）。
 * 修复只动用户选中的那几张，逐张读回验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationRecoveryScanTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    /** 应用产物（无任何坐标）：判定应为「无坐标」 */
    private fun appOutputNoCoords(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** 应用产物 + EXIF GPS：判定应为「有位置」 */
    private fun appOutputWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
            ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg).drop(2).toByteArray()

    /** 相机原始照片（无 VCamera 标记）：不是本工具产物 */
    private fun cameraOriginal(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { 0x5A } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun putImage(rel: String, name: String, bytes: ByteArray): android.net.Uri {
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
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

    /** 重建会删旧行插新行：按名字找当前行（旧 URI 已失效） */
    private fun rowByName(name: String): android.net.Uri? =
        resolver.query(
            images, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name), null
        )?.use { c ->
            if (c.moveToFirst())
                android.content.ContentUris.withAppendedId(images, c.getLong(0))
            else null
        }

    /** 体检按**文件事实**判定：产物文件无 EXIF GPS 即「无坐标」，媒体库列有没有值一概不问 */
    @Test
    fun scanClassifiesProductsByFileEvidence() {
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputNoCoords())
        putImage("DCIM/Camera", "IMG_B.jpg", appOutputWithGps(22, 108))
        // 列里有坐标但**文件无 EXIF GPS** 的产物：旧语义（查列）会判「不缺」，
        // 新语义（查文件）必须判「无坐标」——坐标列读不回，不能当判据
        val colOnly = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_COLONLY.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put("latitude", 22.8389)
            put("longitude", 108.1780)
        })!!
        resolver.openOutputStream(colOnly, "w")?.use { it.write(appOutputNoCoords()) }
        // 相机原始照片：不是本工具产物，不出现在结果里
        putImage("DCIM/Camera", "IMG_ORIG.jpg", cameraOriginal())

        val found = runBlocking { LocationDisplayRepair.scanProducts(app) }
        val byName = found.associateBy { it.name }

        assertEquals(
            "只含本工具产物（相机原片被排除）",
            setOf("IMG_A.jpg", "IMG_B.jpg", "IMG_COLONLY.jpg"), byName.keys)
        assertEquals(LocationHealth.Verdict.NONE, byName["IMG_A.jpg"]!!.probe.verdict)
        assertEquals(LocationHealth.Verdict.HAS_EXIF, byName["IMG_B.jpg"]!!.probe.verdict)
        assertEquals(
            "列有坐标但文件无 EXIF GPS：按文件事实判「无坐标」",
            LocationHealth.Verdict.NONE, byName["IMG_COLONLY.jpg"]!!.probe.verdict)
    }

    @Test
    fun rebuildSelectedRebuildsOnlyChosenRows() {
        // 源产物自身带 EXIF GPS：行重建后坐标列就位（无坐标可恢复的产物走 noCoords 跳过，
        // 那条路径由 LocationDisplayRepairTest 的用例覆盖）
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputWithGps(22, 108))
        val b = putImage("DCIM/Camera", "IMG_B.jpg", appOutputWithGps(23, 109))
        val found = runBlocking { LocationDisplayRepair.scanProducts(app) }
        val chosen = found.filter { it.name == "IMG_A.jpg" }
            .map { LocationDisplayRepair.RepairTarget(it.uri, it.name) }
        val processed = mutableSetOf<String>()

        val stats = LocationDisplayRepair.rebuildSelected(app, chosen, processed)
        assertEquals(1, stats.rebuilt)
        assertEquals(0, stats.failed)
        assertTrue("重建过的记入续跑名单", processed.contains("IMG_A.jpg"))
        // 选中的拿到了坐标（重建删旧插新，按名字找当前行）；未选中的原样不动
        assertTrue(coordsOf(rowByName("IMG_A.jpg")!!).first != null)
        assertTrue("未选中的记录不应被动过", coordsOf(b).first == null)
    }

    @Test
    fun rebuildSelectedPausesForSpaceAndKeepsRestListed() {
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputNoCoords())
        putImage("DCIM/Camera", "IMG_B.jpg", appOutputNoCoords())
        val found = runBlocking { LocationDisplayRepair.scanProducts(app) }
            .map { LocationDisplayRepair.RepairTarget(it.uri, it.name) }
        val processed = mutableSetOf<String>()

        val stats = LocationDisplayRepair.rebuildSelected(
            app, found, processed,
            freeSpaceBytes = { 5L * 1024 * 1024 * 1024 },
            minFreeBytes = 10L * 1024 * 1024 * 1024
        )
        assertTrue("应因空间不足暂停", stats.pausedForSpace)
        assertEquals(0, stats.rebuilt)
        assertEquals("暂停时未处理的都留在结果里（重新扫描可续跑）", 2, stats.remaining)
    }

    @Test
    fun rebuildSelectedSkipsAlreadyProcessed() {
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputNoCoords())
        val found = runBlocking { LocationDisplayRepair.scanProducts(app) }
            .map { LocationDisplayRepair.RepairTarget(it.uri, it.name) }
        val processed = mutableSetOf("IMG_A.jpg")

        val stats = LocationDisplayRepair.rebuildSelected(app, found, processed)
        assertEquals("已在续跑名单里的不再动", 0, stats.rebuilt)
        assertEquals(0, stats.remaining)
    }

    /** 索引直查：ConvertedStore 记录的产物行免嗅探直接体检（含无标记行的兼容语义） */
    @Test
    fun scanUsesConvertedStoreIndexWithoutSniff() {
        // cameraOriginal() 无产物标记——嗅探兜底会拒绝它，只有索引直查能认出
        val row = putImage("DCIM/Camera", "IMG_IDX.jpg", cameraOriginal())
        ConvertedStore.record(app, "/storage/emulated/0/DCIM/Camera/src.jpg", row.toString())

        val found = runBlocking { LocationDisplayRepair.scanProducts(app) }
        val byName = found.associateBy { it.name }
        assertEquals(setOf("IMG_IDX.jpg"), byName.keys)
        assertEquals(
            "索引指向的产物按文件事实判定（该文件无坐标）",
            LocationHealth.Verdict.NONE, byName["IMG_IDX.jpg"]!!.probe.verdict)
    }

    /** 索引里文件带 EXIF GPS 的产物：判定「有位置」 */
    @Test
    fun scanMarksIndexedRowsWithExifGpsAsHealthy() {
        val row = putImage("DCIM/Camera", "IMG_FIXED.jpg", appOutputWithGps(22, 108))
        ConvertedStore.record(app, "/storage/emulated/0/DCIM/Camera/src.jpg", row.toString())

        val found = runBlocking { LocationDisplayRepair.scanProducts(app) }
        assertEquals(1, found.size)
        assertEquals(LocationHealth.Verdict.HAS_EXIF, found[0].probe.verdict)
    }

    /** 非产物名单持久化：本次确认的相机原片，下次扫描不再嗅探 */
    @Test
    fun scanPersistsNonProductsForNextScan() {
        putImage("DCIM/Camera", "IMG_ORIG.jpg", cameraOriginal())
        runBlocking { LocationDisplayRepair.scanProducts(app) }
        val processed = LocationDisplayRepair.loadProcessed(app)
        assertTrue(
            "确认非产物应记入名单（下次扫描免嗅探）",
            processed.contains("IMG_ORIG.jpg"))
    }
}
