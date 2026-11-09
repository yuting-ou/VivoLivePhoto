package com.vliveconvert.app

import com.vliveconvert.app.convert.Converter
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.FooterUtil
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files

/**
 * 单张转换的线程累计分配量门禁（量具自证 + 固定夹具 + 暖机后取中位）。
 * 注意：累计分配量 ≠ 峰值存活堆 ≠ 设备 PSS；本门禁只防「重新引入整段大副本」的分配回归，
 * 不冒充真机内存结论。夹具：主图 4MiB + 视频 16MiB。
 */
class ConverterAllocationTest {

    private val liveId = "-1234567890".padEnd(28, '0')
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun log(level: String, msg: String, tag: String) {}

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArray(8 + payload.size)
        BinaryUtils.writeU32BE(b, 0, (payload.size + 8).toLong())
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, b, 4, 4)
        System.arraycopy(payload, 0, b, 8, payload.size)
        return b
    }

    private fun bigJpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 16, 0, 16, 1, 1, 0x11, 0))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0))
        out.write(ByteArray(4 * 1024 * 1024) { ((it * 31 + 7) and 0x7F).toByte() }) // 无 FF 熵数据
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun makePair(dir: File): File {
        val json = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to liveId,
            "version" to 2107
        ))
        val footer = FooterUtil.buildFooter(json, liveId, FooterUtil.vivoPrefix)
        val jpg = File(dir, "IMG_ALLOC.jpg").apply {
            writeBytes(bigJpeg() + FooterUtil.vivoPrefix + footer)
        }
        val extInfo = box("uuid", "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII) +
            FooterUtil.buildFooter(json, liveId, FooterUtil.vivoPrefix))
        File(dir, "IMG_ALLOC.mp4").writeBytes(
            box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isom".toByteArray()) +
                box("mdat", ByteArray(16 * 1024 * 1024) { ((it * 13 + 5) and 0x7F).toByte() }) +
                box("moov", ByteArray(0)) +
                box("uuid", "vivoMediaEStream".toByteArray(Charsets.US_ASCII) + ByteArray(122)) +
                extInfo)
        return jpg
    }

    private fun measureAlloc(jpg: File, outDir: File): Long {
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        Converter.convertToVivoSingle(jpg.absolutePath, outDir.absolutePath, ::log)
        return bean.getThreadAllocatedBytes(tid) - before
    }

    @Test
    fun singleConversionAllocationGate() {
        assertTrue("量具须支持线程分配计量", bean.isThreadAllocatedMemorySupported)
        // 量具自证：已知 1MiB 分配必须被量到
        val tid = Thread.currentThread().threadId()
        val t0 = bean.getThreadAllocatedBytes(tid)
        val sink = ByteArray(1024 * 1024) { it.toByte() }
        val probe = bean.getThreadAllocatedBytes(tid) - t0
        assertTrue("量具自证失败：1MiB 分配应被量到（实得 $probe，sink=${sink.size}）",
            probe >= 1024 * 1024)

        val srcDir = Files.createTempDirectory("vlc_alloc_src").toFile()
        val jpg = makePair(srcDir)
        // 暖机 1 次（JIT/类加载），再测 3 次取中位
        measureAlloc(jpg, Files.createTempDirectory("vlc_alloc_warm").toFile())
        val samples = (1..3).map {
            measureAlloc(jpg, Files.createTempDirectory("vlc_alloc_run").toFile())
        }.sorted()
        val median = samples[1]
        val mb = median / (1024.0 * 1024.0)
        println("[ConverterAllocation] samples(MiB)=${samples.map { it / 1048576 }} median=${"%.2f".format(mb)}MiB")
        // 门禁阈值由实测校准：旧剥尾路径基线 78.07MiB，零复制后 62.07MiB（省一份 16MiB 视频副本，
        // 各 3 次样本完全一致）。门槛取 70MiB：高于优化后正常波动、低于「重新引入整段副本」的 ~78MiB。
        assertTrue("单张分配中位 ${"%.2f".format(mb)}MiB 超过门禁 70MiB", median <= 70L * 1024 * 1024)
    }
}