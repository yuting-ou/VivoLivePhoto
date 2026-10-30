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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 位置交付校验（关2）：源字节带 GPS（读取未脱敏）时，转换产物的 EXIF 也必须
 * 带上坐标——否则本张按失败处理，绝不顶着「成功」标记交付、更不触发原图删除。
 * 正常字节管线逐字节保留 EXIF，此校验是防线而非常触发路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationDeliveryCheckTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()

    private val motionXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF """ +
        """xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" """ +
        """xmlns:VCamera="http://vivo.com/camera/" """ +
        """VCamera:VMediaKitVersion="1.0.0.9"></rdf:RDF></x:xmpmeta>"""

    private fun jpegWithGps(latDeg: Long, lonDeg: Long): ByteArray =
        ExportLocationColumnsTest().jpegWithGps(latDeg, lonDeg)

    private fun jpegWithoutGps(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            JpegUtil.buildXmpApp1(motionXmp) +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x12, 0x34) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun makeResult(bytes: ByteArray, stem: String): SingleWriteResult {
        val dir = Files.createTempDirectory("vlc_delivery").toFile()
        val f = File(dir, "$stem.jpg").apply { writeBytes(bytes) }
        return SingleWriteResult(
            f.path,
            listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes)))
        )
    }

    @Test
    fun requireGpsBlocksGpslessProduct() {
        val result = makeResult(jpegWithoutGps(), "IMG_LOSTGPS")
        val err = runCatching {
            MediaExport.exportAndVerify(
                app, result, timestamp = 1_700_000_000_000L,
                relPath = "Pictures/DeliveryTest", useCameraDir = false,
                requireGps = true)
        }.exceptionOrNull()
        assertTrue("源有位置而产物没有必须判失败", err is IOException)
        assertTrue(
            "失败信息要说明位置校验语义",
            err?.message?.contains("位置校验失败") == true)
    }

    @Test
    fun requireGpsPassesWhenProductCarriesGps() {
        val result = makeResult(jpegWithGps(22, 108), "IMG_OKGPS")
        val uri = MediaExport.exportAndVerify(
            app, result, timestamp = 1_700_000_000_000L,
            relPath = "Pictures/DeliveryTest", useCameraDir = false,
            requireGps = true)
        assertNotNull(uri)
        resolverOf().query(
            uri, arrayOf("latitude"), null, null, null)?.use { c ->
            c.moveToFirst()
            assertTrue("产物行坐标应就位", !c.isNull(0))
        }
    }

    @Test
    fun defaultOffDoesNotBlockGpslessProduct() {
        // 破损修复（糊图）路径不开启强校验：默认参数必须保持旧行为
        val result = makeResult(jpegWithoutGps(), "IMG_NOCHECK")
        val uri = MediaExport.exportAndVerify(
            app, result, timestamp = 1_700_000_000_000L,
            relPath = "Pictures/DeliveryTest", useCameraDir = false)
        assertNotNull(uri)
    }

    private fun resolverOf() = app.contentResolver
}
