package com.vliveconvert.app

import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.FooterUtil
import com.vliveconvert.app.core.Mp4Util
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频零复制的等价性：按 sourceEnd 直接插入 lpex，必须与「先剥尾再插入」逐字节一致，
 * 且结果不含尾部 uuid、stco/co64 偏移修复正确。
 */
class Mp4UtilSourceEndTest {

    private val liveId = "-1234567890".padEnd(28, '0')

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteArray(8 + payload.size)
        BinaryUtils.writeU32BE(b, 0, (payload.size + 8).toLong())
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, b, 4, 4)
        System.arraycopy(payload, 0, b, 8, payload.size)
        return b
    }

    private fun uuidFooter(): ByteArray {
        val json = FooterUtil.buildFooterJson(linkedMapOf(
            "com.android.camera.imageTime" to 12L,
            "com.android.camera.livephoto" to liveId,
            "version" to 2107
        ))
        return box("uuid", "vivoMediaExtInfo".toByteArray(Charsets.US_ASCII) +
            FooterUtil.buildFooter(json, liveId, FooterUtil.vivoPrefix))
    }

    /** moov 内含 stco（两条目指向 insertAt 之后，验证偏移后移） */
    private fun moovWithStco(): ByteArray {
        val stcoPayload = ByteArray(16) // version/flags(4) + count(4) + 2*entry(4)
        BinaryUtils.writeU32BE(stcoPayload, 4, 2)
        BinaryUtils.writeU32BE(stcoPayload, 8, 1_000_000)
        BinaryUtils.writeU32BE(stcoPayload, 12, 2_000_000)
        return box("moov", box("stco", stcoPayload))
    }

    private fun rawMp4(withUuid: Boolean): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isom".toByteArray())
        val base = ftyp + box("mdat", ByteArray(256) { 0x33 }) + moovWithStco()
        return if (withUuid) base + uuidFooter() else base
    }

    @Test
    fun stripEndBoundaries() {
        assertEquals(0, Mp4Util.stripVivoUuidEnd(ByteArray(0)))
        val noUuid = rawMp4(false)
        assertEquals(noUuid.size, Mp4Util.stripVivoUuidEnd(noUuid))
        val withUuid = rawMp4(true)
        assertTrue(Mp4Util.stripVivoUuidEnd(withUuid) < withUuid.size)
        assertEquals(Mp4Util.stripVivoUuid(withUuid).size, Mp4Util.stripVivoUuidEnd(withUuid))
    }

    @Test
    fun sourceEndInsertIsByteIdenticalToStripThenInsert() {
        val payload = "LivePhotoExtension{}".toByteArray(Charsets.US_ASCII)
        for (withUuid in listOf(true, false)) {
            val raw = rawMp4(withUuid)
            val end = Mp4Util.stripVivoUuidEnd(raw)
            val old = Mp4Util.insertBoxIntoMoov(Mp4Util.stripVivoUuid(raw), "lpex", payload)
            val new = Mp4Util.insertBoxIntoMoov(raw, "lpex", payload, sourceEnd = end)
            assertArrayEquals("有 uuid=$withUuid 时两条路径必须逐字节一致", old, new)
            assertEquals(end + 8 + payload.size, new.size)
        }
    }

    @Test
    fun chunkOffsetsShiftedByDelta() {
        val payload = "LivePhotoExtension{}".toByteArray(Charsets.US_ASCII)
        val raw = rawMp4(true)
        val end = Mp4Util.stripVivoUuidEnd(raw)
        val result = Mp4Util.insertBoxIntoMoov(raw, "lpex", payload, sourceEnd = end)
        val delta = 8 + payload.size
        // 定位 result 中的 stco（moov 第一个子 box），读两条目
        val boxes = Mp4Util.iterateBoxes(result, 0, result.size).toList()
        val moov = boxes.first { it.type == "moov" }
        val stco = Mp4Util.iterateBoxes(result, moov.offset + 8, moov.offset + moov.size).first { it.type == "stco" }
        val body = stco.offset + 8
        assertEquals(1_000_000L + delta, BinaryUtils.readU32BE(result, body + 8))
        assertEquals(2_000_000L + delta, BinaryUtils.readU32BE(result, body + 12))
    }
}