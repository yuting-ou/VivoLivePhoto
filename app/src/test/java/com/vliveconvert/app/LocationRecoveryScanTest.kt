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
 * 「找回位置」检测重做（v2.6.0-test28）的集成回归：
 * 判定 = 矩阵（文件字节 × 行坐标）× 现场列读校准。
 *
 * - 校准本身在 Robolectric 真跑（pending 探针行 → READABLE，且探针行清理干净）
 * - 三种校准模式（READABLE / OPAQUE / escalate）下的矩阵组合逐条断言
 * - 保留既有行为：索引直查、非产物名单持久化、只认本工具产物
 *
 * 病根回归：test27 把全部产物列进「找回位置」（100 张里 95 张正常的淹没 5 张
 * 真不显示的）——现在「已正常」不再进列表（只计数），「文件有 EXIF + 列不可读」
 * 归「待确认」而不是「需修复」。
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

    /** 应用产物（无任何坐标）：E=0, F=0 */
    private fun appOutputNoCoords(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** 应用产物 + EXIF GPS：E=1 */
    private fun appOutputWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            com.vliveconvert.app.core.JpegUtil.buildXmpApp1(motionXmp) +
            ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg).drop(2).toByteArray()

    /** 应用产物，EXIF 无 GPS、视频段带 ©xyz 坐标：E=0, F=1（真机丢位置人群的形态） */
    private fun appOutputVideoOnly(): ByteArray {
        val xyzTail = byteArrayOf(0x00, 0x00, 0x00, 0x1E) +
            byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A) +
            byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) +
            "+22.8389+108.1780/".toByteArray()
        return appOutputNoCoords() + xyzTail
    }

    /** 相机原始照片（无 VCamera 标记）：不是本工具产物 */
    private fun cameraOriginal(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(64) { 0x5A } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun putImage(
        rel: String, name: String, bytes: ByteArray,
        coords: Pair<Double, Double>? = null
    ): android.net.Uri {
        val uri = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.DATE_TAKEN, 1_700_000_000_000L)
            if (coords != null) {
                put("latitude", coords.first)
                put("longitude", coords.second)
            }
        })!!
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
        return uri
    }

    /** 注入式列读信号（绕开现场校准，直接钉矩阵分支） */
    private fun opaqueSignal(reads: Map<String, Pair<Double, Double>> = emptyMap()) =
        LocationDisplayRepair.ColumnSignal(
            calibration = LocationHealth.ColumnCalibration.OPAQUE,
            detail = "测试注入",
            readDirect = { uri -> reads[uri.toString()] },
            readPresenceViaSelection = { null })

    private fun failedSignal() = LocationDisplayRepair.ColumnSignal(
        calibration = LocationHealth.ColumnCalibration.FAILED,
        detail = "测试注入：校准异常",
        readDirect = { null },
        readPresenceViaSelection = { null })

    private fun classifyOf(
        outcome: LocationDisplayRepair.ScanOutcome, name: String
    ): LocationHealth.Classification =
        outcome.rows.first { it.name == name }.classification

    // ------------------------------------------------ 校准本身

    /** Robolectric 上真跑校准：出生带坐标的 pending 探针能直接读回 → READABLE；探针行清理干净 */
    @Test
    fun calibrationReadsBackAndCleansUp() {
        val signal = LocationDisplayRepair.calibrateColumns(resolver)
        assertEquals(LocationHealth.ColumnCalibration.READABLE, signal.calibration)
        assertTrue("校准详情应描述读回值", signal.detail.contains("22"))
        // 探针行必须删干净（不留媒体库垃圾）
        val left = resolver.query(
            images, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'vlc_calib_%'", null, null)
            ?.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            ?: emptyList()
        assertTrue("校准探针行必须清理干净，残留：$left", left.isEmpty())
    }

    // ------------------------------------------------ READABLE 模式（真实校准，不注入）

    /** 列可读：E=1+行有坐标→OK（不进列表主体）；E=1+行缺→需修复；E=0+F=1→需修复；E=0+F=0→无位置 */
    @Test
    fun readableModeAppliesFullMatrix() {
        putImage("DCIM/Camera", "IMG_OK.jpg", appOutputWithGps(22, 108), coords = 22.0 to 108.0)
        putImage("DCIM/Camera", "IMG_MISS.jpg", appOutputWithGps(23, 109)) // 行坐标缺失
        putImage("DCIM/Camera", "IMG_VID.jpg", appOutputVideoOnly())
        putImage("DCIM/Camera", "IMG_NONE.jpg", appOutputNoCoords())

        val outcome = runBlocking { LocationDisplayRepair.scanProducts(app) }

        assertEquals(LocationHealth.Classification.OK, classifyOf(outcome, "IMG_OK.jpg"))
        assertEquals(LocationHealth.Classification.REPAIR_NEEDED, classifyOf(outcome, "IMG_MISS.jpg"))
        assertEquals(LocationHealth.Classification.REPAIR_NEEDED, classifyOf(outcome, "IMG_VID.jpg"))
        assertEquals(LocationHealth.Classification.NO_LOCATION, classifyOf(outcome, "IMG_NONE.jpg"))
        // 相机原片不收录
        putImage("DCIM/Camera", "IMG_ORIG.jpg", cameraOriginal())
        val outcome2 = runBlocking { LocationDisplayRepair.scanProducts(app) }
        assertTrue(outcome2.rows.none { it.name == "IMG_ORIG.jpg" })
    }

    // ------------------------------------------------ OPAQUE 模式（注入）

    /** 列不可读（真机的疑似形态）：E=1 → 待确认（不是需修复！）；E=0 保持矩阵 */
    @Test
    fun opaqueModeClassifiesExifAsUnknownNotRepair() {
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputWithGps(22, 108), coords = 22.0 to 108.0)
        putImage("DCIM/Camera", "IMG_VID.jpg", appOutputVideoOnly())
        putImage("DCIM/Camera", "IMG_NONE.jpg", appOutputNoCoords())

        val outcome = runBlocking {
            LocationDisplayRepair.scanProducts(app, columnSignal = opaqueSignal())
        }

        // 病根回归：test27 会把它列成待修复；正确语义是「待确认」（文件有坐标、行不可观测）
        assertEquals(LocationHealth.Classification.UNKNOWN, classifyOf(outcome, "IMG_A.jpg"))
        assertEquals(LocationHealth.Classification.REPAIR_NEEDED, classifyOf(outcome, "IMG_VID.jpg"))
        assertEquals(LocationHealth.Classification.NO_LOCATION, classifyOf(outcome, "IMG_NONE.jpg"))
    }

    /** 校准失败（异常）按不可读处理：E=1 → 待确认 */
    @Test
    fun failedCalibrationBehavesAsOpaque() {
        putImage("DCIM/Camera", "IMG_A.jpg", appOutputWithGps(22, 108))

        val outcome = runBlocking {
            LocationDisplayRepair.scanProducts(app, columnSignal = failedSignal())
        }

        assertEquals(LocationHealth.Classification.UNKNOWN, classifyOf(outcome, "IMG_A.jpg"))
    }

    // ------------------------------------------------ escalate（列名义不可读，但有行读到值）

    /** 有任一行读到值 → 列实际可读：读到值的行 OK，读不到的行（行坐标缺失）→ 需修复 */
    @Test
    fun escalatedOpaqueAppliesReadableMatrix() {
        val ok = putImage("DCIM/Camera", "IMG_OK.jpg", appOutputWithGps(22, 108), coords = 22.0 to 108.0)
        putImage("DCIM/Camera", "IMG_MISS.jpg", appOutputWithGps(23, 109))

        val outcome = runBlocking {
            LocationDisplayRepair.scanProducts(
                app, columnSignal = opaqueSignal(reads = mapOf(ok.toString() to (22.0 to 108.0))))
        }

        assertEquals(LocationHealth.Classification.OK, classifyOf(outcome, "IMG_OK.jpg"))
        assertEquals(LocationHealth.Classification.REPAIR_NEEDED, classifyOf(outcome, "IMG_MISS.jpg"))
    }

    // ------------------------------------------------ 既有行为保留

    /** 索引直查：ConvertedStore 记录的产物行免嗅探直接体检（含无产物标记行的兼容语义） */
    @Test
    fun scanUsesConvertedStoreIndexWithoutSniff() {
        // cameraOriginal() 无产物标记——嗅探兜底会拒绝它，只有索引直查能认出
        val row = putImage("DCIM/Camera", "IMG_IDX.jpg", cameraOriginal())
        ConvertedStore.record(app, "/storage/emulated/0/DCIM/Camera/src.jpg", row.toString())

        val outcome = runBlocking { LocationDisplayRepair.scanProducts(app) }
        val byName = outcome.rows.associateBy { it.name }
        assertEquals(setOf("IMG_IDX.jpg"), byName.keys)
        assertEquals(
            "索引指向的产物按矩阵判定（该文件无坐标 → 无位置）",
            LocationHealth.Classification.NO_LOCATION, byName["IMG_IDX.jpg"]!!.classification)
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

    /** 索引与嗅探不双重计入：同一行只出现一次 */
    @Test
    fun indexedRowsAreNotDoubleCounted() {
        val row = putImage("DCIM/Camera", "IMG_DUP.jpg", appOutputWithGps(22, 108))
        ConvertedStore.record(app, "/storage/emulated/0/DCIM/Camera/src.jpg", row.toString())

        val outcome = runBlocking { LocationDisplayRepair.scanProducts(app) }
        assertEquals("索引已报的行不应再被嗅探重复计入",
            1, outcome.rows.count { it.name == "IMG_DUP.jpg" })
    }

    // ------------------------------------------------ 修复（选中式）保留

    @Test
    fun rebuildSelectedRebuildsOnlyChosenRows() {
        val a = putImage("DCIM/Camera", "IMG_A.jpg", appOutputWithGps(22, 108))
        val b = putImage("DCIM/Camera", "IMG_B.jpg", appOutputWithGps(23, 109))
        val processed = mutableSetOf<String>()

        val stats = LocationDisplayRepair.rebuildSelected(
            app, listOf(LocationDisplayRepair.RepairTarget(a, "IMG_A.jpg")), processed)

        assertEquals(1, stats.rebuilt)
        assertEquals(0, stats.failed)
        assertTrue("重建过的记入续跑名单", processed.contains("IMG_A.jpg"))
        // 选中的按名字找到新行（重建删旧插新）；未选中的原行不动
        val newA = rowByName("IMG_A.jpg")!!
        assertTrue("选中的行应已重建（URI 变化）", newA != a)
        assertEquals("未选中的记录不应被动过", b, rowByName("IMG_B.jpg"))
    }

    @Test
    fun rebuildSelectedPausesForSpaceAndKeepsRestListed() {
        val a = putImage("DCIM/Camera", "IMG_A.jpg", appOutputNoCoords())
        val b = putImage("DCIM/Camera", "IMG_B.jpg", appOutputNoCoords())

        val stats = LocationDisplayRepair.rebuildSelected(
            app,
            listOf(LocationDisplayRepair.RepairTarget(a, "IMG_A.jpg"),
                LocationDisplayRepair.RepairTarget(b, "IMG_B.jpg")),
            mutableSetOf(),
            freeSpaceBytes = { 5L * 1024 * 1024 * 1024 },
            minFreeBytes = 10L * 1024 * 1024 * 1024
        )
        assertTrue("应因空间不足暂停", stats.pausedForSpace)
        assertEquals(0, stats.rebuilt)
        assertEquals("暂停时未处理的都留在结果里（重新扫描可续跑）", 2, stats.remaining)
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
}
