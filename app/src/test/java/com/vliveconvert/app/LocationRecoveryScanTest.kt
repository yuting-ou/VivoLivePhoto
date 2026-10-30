package com.vliveconvert.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.service.ConvertedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「找回位置」检测 + 选中式修复：
 * 扫描只报「应用产物且媒体库无坐标」的记录（相册不显示位置的那批），
 * 重建只动用户选中的那几张——替代旧版全库分批重建（数千张陪跑）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationRecoveryScanTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val resolver get() = app.contentResolver
    private val images
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    // 与 LocationDisplayRepairTest 同源的夹具构造（保持两边一致），
    // 直接复用其私有构造太绕：这里用可见的最小 XMP 模板 + 无坐标 JPEG
    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    /** 应用产物（无坐标列）：用户抱怨的「转完相册里没位置」那批 */
    private fun appOutputNoCoords(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** 应用产物 + EXIF GPS：重建后坐标列应就位（与 LocationDisplayRepairTest 夹具同源） */
    private fun appOutputWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
            ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg).drop(2).toByteArray()

    /** 相机原始照片（无 VCamera 标记、无坐标列）：不在修复范围 */
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

    @Test
    fun scanFindsOnlyAppProductsWithoutCoords() {
        val missingA = putImage("DCIM/Camera", "IMG_A.jpg", appOutputNoCoords())
        val missingB = putImage("DCIM/Camera", "IMG_B.jpg", appOutputNoCoords())
        // 带坐标列的产物：相册已能显示位置，不应上报
        val withCoords = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_OK.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put("latitude", 22.8389)
            put("longitude", 108.1780)
        })!!
        resolver.openOutputStream(withCoords, "w")?.use { it.write(appOutputNoCoords()) }
        // 相机原始照片：不是本工具产物，不上报
        putImage("DCIM/Camera", "IMG_ORIG.jpg", cameraOriginal())

        val found = runBlocking { LocationDisplayRepair.scanMissingLocation(app) }
        assertEquals(
            "只报缺坐标的本工具产物",
            setOf("IMG_A.jpg", "IMG_B.jpg"), found.map { it.name }.toSet())
        assertEquals(
            "扫描结果的 URI 应指向缺坐标的行", missingA, found.first { it.name == "IMG_A.jpg" }.uri)
    }

    @Test
    fun rebuildSelectedRebuildsOnlyChosenRows() {
        // 源产物自身带 EXIF GPS：行重建后坐标列就位（无坐标可恢复的产物走 noCoords 跳过，
        // 那条路径由 LocationDisplayRepairTest 的 recreateRow 用例覆盖）
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputWithGps(22, 108))
        val b = putImage("DCIM/Camera", "IMG_B.jpg", appOutputWithGps(23, 109))
        val found = runBlocking { LocationDisplayRepair.scanMissingLocation(app) }
        val chosen = found.filter { it.name == "IMG_A.jpg" }
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
        val found = runBlocking { LocationDisplayRepair.scanMissingLocation(app) }
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
        val found = runBlocking { LocationDisplayRepair.scanMissingLocation(app) }
        val processed = mutableSetOf("IMG_A.jpg")

        val stats = LocationDisplayRepair.rebuildSelected(app, found, processed)
        assertEquals("已在续跑名单里的不再动", 0, stats.rebuilt)
        assertEquals(0, stats.remaining)
    }

    /** 索引直查：ConvertedStore 记录的产物行免嗅探直接上报（含无标记行的兼容语义） */
    @Test
    fun scanUsesConvertedStoreIndexWithoutSniff() {
        // cameraOriginal() 无产物标记——嗅探兜底会拒绝它，只有索引直查能认出
        val row = putImage("DCIM/Camera", "IMG_IDX.jpg", cameraOriginal())
        ConvertedStore.record(app, "/storage/emulated/0/DCIM/Camera/src.jpg", row.toString())

        val found = runBlocking { LocationDisplayRepair.scanMissingLocation(app) }
        assertEquals(
            "索引指向的缺坐标产物应直接上报",
            setOf("IMG_IDX.jpg"), found.map { it.name }.toSet())
    }

    /** 索引里已有坐标的产物不上报（已修过/相机写入过，不缺） */
    @Test
    fun scanSkipsIndexedRowsThatHaveCoords() {
        val row = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_FIXED.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put("latitude", 22.8389)
            put("longitude", 108.1780)
        })!!
        resolver.openOutputStream(row, "w")?.use { it.write(cameraOriginal()) }
        ConvertedStore.record(app, "/storage/emulated/0/DCIM/Camera/src.jpg", row.toString())

        val found = runBlocking { LocationDisplayRepair.scanMissingLocation(app) }
        assertTrue("有坐标的不缺，不应上报", found.isEmpty())
    }

    /** 非产物名单持久化：本次确认的相机原片，下次扫描不再嗅探 */
    @Test
    fun scanPersistsNonProductsForNextScan() {
        putImage("DCIM/Camera", "IMG_ORIG.jpg", cameraOriginal())
        runBlocking { LocationDisplayRepair.scanMissingLocation(app) }
        val processed = LocationDisplayRepair.loadProcessed(app)
        assertTrue(
            "确认非产物应记入名单（下次扫描免嗅探）",
            processed.contains("IMG_ORIG.jpg"))
    }
}
