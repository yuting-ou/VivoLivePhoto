package com.vliveconvert.app

import com.vliveconvert.app.core.Mp4Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * MP4 轨信息解析的 JVM 回归测试（重点：mdhd 的 version 相关偏移）。
 *
 * 背景：mdhd 是 FullBox。version=1 时 creation_time / modification_time 各占 8 字节
 * （v0 各 4 字节），于是 timescale/duration 的偏移由 v0 的 12/16 变为 v1 的 20/24。
 * 原实现两个分支都按 12/16 读：v1 时会把 modification_time 当成 timescale，
 * 导致 duration/fps 全错——而 fps 又用于由 imageTime（封面帧序号）反推封面时间戳，
 * 最终会让实况封面帧定位错误。本测试用 v0/v1 两种合成 mdhd 锁定偏移。
 */
class Mp4TrackInfoTest {

    // ---------- 合成 MP4 的最小构造工具 ----------
    // 约定：所有 xxxBox() 返回**已带头部的完整 box**，避免忘记包 box 头导致结构错位

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        val b = ByteArray(size)
        b[0] = ((size ushr 24) and 0xFF).toByte()
        b[1] = ((size ushr 16) and 0xFF).toByte()
        b[2] = ((size ushr 8) and 0xFF).toByte()
        b[3] = (size and 0xFF).toByte()
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, b, 4, 4)
        System.arraycopy(payload, 0, b, 8, payload.size)
        return b
    }

    private fun u32(v: Long): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun u64(v: Long): ByteArray = ByteArray(8) { i ->
        ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
    }

    private fun fullBox(version: Int): ByteArray = byteArrayOf(version.toByte(), 0, 0, 0)

    /** mdhd FullBox；v1 的 creation/modification 各占 8 字节 */
    private fun mdhdBox(version: Int, timescale: Long, duration: Long): ByteArray {
        val payload = if (version == 1) {
            fullBox(1) + u64(0x0000000012345678L) + u64(0x00000000ABCDEF01L) +
                u32(timescale) + u64(duration) + u32(0)
        } else {
            fullBox(0) + u32(0x12345678L) + u32(0x7ABCDEF0L) +
                u32(timescale) + u32(duration) + u32(0)
        }
        return box("mdhd", payload)
    }

    /** tkhd（v0）：宽高为末尾两个 16.16 定点，单位矩阵在其前 36 字节 */
    private fun tkhdBox(width: Int, height: Int): ByteArray {
        val matrix = u32(0x00010000L) + u32(0) + u32(0) +
            u32(0) + u32(0x00010000L) + u32(0) +
            u32(0) + u32(0) + u32(0x40000000L)
        val payload = fullBox(0) + u32(0) + u32(0) + u32(1) + u32(0) + u32(0) +
            u64(0) + u32(0) + u32(0) + matrix +
            u32(width.toLong() shl 16) + u32(height.toLong() shl 16)
        return box("tkhd", payload)
    }

    /** hdlr：FullBox + pre_defined + handler_type("vide") */
    private fun hdlrBox(): ByteArray =
        box("hdlr", fullBox(0) + u32(0) + "vide".toByteArray(Charsets.ISO_8859_1))

    /** stts：单条目，共 sampleCount 个样本 */
    private fun sttsBox(sampleCount: Long): ByteArray =
        box("stts", fullBox(0) + u32(1) + u32(sampleCount) + u32(1))

    /** stsd：一个 avc1 样本描述（fourcc 位于 body+12） */
    private fun stsdBox(): ByteArray =
        box("stsd", fullBox(0) + u32(1) + u32(8) + "avc1".toByteArray(Charsets.ISO_8859_1))

    /**
     * ftyp + moov，其中视频 trak = tkhd + mdia(mdhd + hdlr + minf(stbl(stts + stsd)))。
     * timescale=1000、duration=5000 → 5s；样本 150 → fps=30。
     */
    private fun mp4WithMdhd(version: Int): ByteArray {
        val stbl = box("stbl", sttsBox(150L) + stsdBox())
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhdBox(version, 1000L, 5000L) + hdlrBox() + minf)
        val trak = box("trak", tkhdBox(1080, 1920) + mdia)
        val moov = box("moov", trak)
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.ISO_8859_1) +
            u32(0x200) + "isom".toByteArray(Charsets.ISO_8859_1) +
            "mp41".toByteArray(Charsets.ISO_8859_1))
        return ftyp + moov
    }

    // ---------- 断言 ----------

    private fun assertParsed(version: Int) {
        val info = Mp4Util.getTrackInfo(mp4WithMdhd(version))
        assertNotNull("应解析出轨道信息（version=$version）", info)
        assertEquals("duration 应为 5s（version=$version）", 5_000_000L, info!!["duration_us"])
        assertEquals("fps 应为 30（version=$version）", 30.0, info["fps"] as Double, 1e-6)
        assertEquals("样本总数应为 150（version=$version）", 150L, info["frame_count"])
        assertEquals("编码应为 avc1（version=$version）", "avc1", info["codec"])
        assertEquals("宽应为 1080（version=$version）", 1080, info["width"])
        assertEquals("高应为 1920（version=$version）", 1920, info["height"])
    }

    @Test
    fun parsesMdhdVersion0() = assertParsed(0)

    /** 回归：version=1 时 creation/modification 加宽，偏移必须是 20/24 而非 12/16 */
    @Test
    fun parsesMdhdVersion1() = assertParsed(1)
}
