package com.vliveconvert.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.convert.Converter
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.FooterUtil
import com.vliveconvert.app.core.LivePhotoAsset
import com.vliveconvert.app.core.OutputVerifier
import com.vliveconvert.app.core.SegmentDigest
import com.vliveconvert.app.core.SingleWriteResult
import com.vliveconvert.app.core.VivoSingle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 暂存与交付的数据安全边界：
 * 1. 同名不同内容的两张并发转换，各自暂存路径独立、产物各自匹配自己的摘要；
 * 2. 写出先 tmp 后发布，失败不留半成品；
 * 3. 交付前对**暂存文件**做分段摘要校验——新建与覆盖两条路径都不得把串写内容交付。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConverterJobIsolationTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()
    private val liveId = "-1234567890".padEnd(28, '0')

    private fun log(level: String, msg: String, tag: String) { println("[$level][$tag] $msg") }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArray(8 + payload.size)
        BinaryUtils.writeU32BE(b, 0, (payload.size + 8).toLong())
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, b, 4, 4)
        System.arraycopy(payload, 0, b, 8, payload.size)
        return b
    }

    private fun minimalJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), // SOI
        0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 16, 0, 16, 1, 1, 0x11, 0, // SOF0
        0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0, // SOS
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x12, 0x34, 0x56,
        0xFF.toByte(), 0xD9.toByte() // EOI
    )

    private fun pseudoRandom(size: Int, seed: Int): ByteArray {
        val b = ByteArray(size)
        var s = seed.toLong()
        for (i in 0 until size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            b[i] = (s ushr 33).toByte()
        }
        return b
    }

    /** 同 stem、不同视频内容（seed 不同）的一对双文件 */
    private fun makePair(dir: File, stem: String, seed: Int): File {
        val json = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to liveId,
            "version" to 2107
        ))
        val footer = FooterUtil.buildFooter(json, liveId, FooterUtil.vivoPrefix)
        val jpg = File(dir, "$stem.jpg").apply {
            writeBytes(minimalJpeg() +
                "streamdata".toByteArray(Charsets.US_ASCII) + "DEGS".toByteArray(Charsets.US_ASCII) +
                pseudoRandom(64, 11) + FooterUtil.vivoPrefix + footer)
        }
        val extJson = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to liveId,
            "version" to 2107
        ))
        val extInfo = box("uuid", "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII) +
            FooterUtil.buildFooter(extJson, liveId, FooterUtil.vivoPrefix))
        File(dir, "$stem.mp4").writeBytes(
            box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isom".toByteArray() + "mp41".toByteArray()) +
                box("mdat", pseudoRandom(4096, seed)) + box("moov", ByteArray(0)) +
                box("uuid", "vivoMediaEStream".toByteArray(Charsets.US_ASCII) + pseudoRandom(122, 7)) +
                extInfo)
        return jpg
    }

    @Test
    fun concurrentSameStemGetsIndependentStaging() {
        val dirA = Files.createTempDirectory("vlc_job_a").toFile()
        val dirB = Files.createTempDirectory("vlc_job_b").toFile()
        val outDir = Files.createTempDirectory("vlc_job_out").toFile()
        val jpgA = makePair(dirA, "IMG_SAME", 101)
        val jpgB = makePair(dirB, "IMG_SAME", 202)

        val gate = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<SingleWriteResult>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = listOf(jpgA, jpgB).map { jpg ->
            Thread {
                try {
                    gate.await()
                    results.add(Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log))
                } catch (t: Throwable) { errors.add(t) }
            }.apply { start() }
        }
        gate.countDown()
        workers.forEach { it.join(15_000) }
        assertTrue("转换不应抛异常: $errors", errors.isEmpty())
        assertTrue("两张都应产出", results.size == 2)
        val (a, b) = results
        assertNotEquals("同名两张必须各有独立暂存路径", a.path, b.path)
        assertTrue(File(a.path).exists() && File(b.path).exists())
        assertTrue("A 产物必须匹配 A 的摘要",
            File(a.path).inputStream().use { OutputVerifier.verify(it, a.segments) })
        assertTrue("B 产物必须匹配 B 的摘要",
            File(b.path).inputStream().use { OutputVerifier.verify(it, b.segments) })
    }

    @Test
    fun successfulWriteLeavesNoTmp() {
        val outDir = Files.createTempDirectory("vlc_atomic_ok").toFile()
        val asset = LivePhotoAsset(minimalJpeg(), null, ByteArray(0))
        val result = VivoSingle.write(asset, outDir.absolutePath, "IMG_ATOMIC", ::log)
        assertTrue(File(result.path).exists())
        assertFalse("成功后不得留下 .tmp", File(outDir, "IMG_ATOMIC.jpg.tmp").exists())
    }

    @Test
    fun failedWriteLeavesNoResidue() {
        val outDir = Files.createTempDirectory("vlc_atomic_ro").toFile()
        assertTrue(outDir.setReadOnly())
        try {
            val asset = LivePhotoAsset(minimalJpeg(), null, ByteArray(0))
            try {
                VivoSingle.write(asset, outDir.absolutePath, "IMG_ATOMIC", ::log)
                fail("只读目录写出必须失败")
            } catch (_: IOException) {
                // 预期
            }
            assertFalse(File(outDir, "IMG_ATOMIC.jpg").exists())
            assertFalse("失败后不得留下 .tmp 半成品", File(outDir, "IMG_ATOMIC.jpg.tmp").exists())
        } finally {
            outDir.setWritable(true)
        }
    }

    /** 覆盖路径的读回比对只能证明「拷贝忠实」，不能证明「内容可信」——交付前必须对暂存文件验摘要 */
    @Test
    fun tamperedStagingRejectedOnOverwritePath() {
        val resolver = app.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val bytes = minimalJpeg() + pseudoRandom(512, 77)
        val dir = Files.createTempDirectory("vlc_tamper").toFile()
        val staged = File(dir, "IMG_TAMPER.jpg").apply { writeBytes(bytes) }
        val result = SingleWriteResult(
            staged.path, listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes))))
        val old = resolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_TAMPER.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        })!!
        resolver.openOutputStream(old, "w")?.use { it.write(bytes) }

        // 串写/损坏：改动视频段一字节（头部 XMP/EXIF 仍可解析，覆盖路径会一路走到重建）
        bytes[bytes.size - 30] = (bytes[bytes.size - 30].toInt() xor 0x5A).toByte()
        staged.writeBytes(bytes)

        try {
            MediaExport.exportAndVerify(
                app, result, 1_700_000_000_000L, "DCIM/Camera",
                useCameraDir = true, oldUriString = old.toString())
            fail("被改写的暂存产物绝不能经覆盖路径交付")
        } catch (_: IOException) {
            // 预期：交付前自检拦截
        }
        // 旧产物原样保留
        val oldBytes = resolver.openInputStream(old)!!.use { it.readBytes() }
        assertTrue("旧产物必须原样保留", oldBytes.contentEquals(
            minimalJpeg() + pseudoRandom(512, 77)))
    }

    @Test
    fun tamperedStagingRejectedOnNewPath() {
        val bytes = minimalJpeg() + pseudoRandom(512, 78)
        val dir = Files.createTempDirectory("vlc_tamper_new").toFile()
        val staged = File(dir, "IMG_TAMPER_NEW.jpg").apply { writeBytes(bytes) }
        val result = SingleWriteResult(
            staged.path, listOf(SegmentDigest("all", 0, bytes.size, OutputVerifier.md5Of(bytes))))
        bytes[bytes.size - 30] = (bytes[bytes.size - 30].toInt() xor 0x5A).toByte()
        staged.writeBytes(bytes)
        try {
            MediaExport.exportAndVerify(
                app, result, 1_700_000_000_000L, "Pictures/VLiveConvert", useCameraDir = false)
            fail("被改写的暂存产物绝不能经新建路径交付")
        } catch (_: IOException) {
            // 预期
        }
    }
}