package com.vliveconvert.app.core

/**
 * JPEG 段级工具：marker 扫描、EOI 定位、XMP APP1 替换/插入。
 * 所有操作均为字节级，不重编码，保证图像数据无损。
 */
internal class JpegException(message: String) : Exception(message)

internal object JpegUtil {
    /** XMP APP1 段头："http://ns.adobe.com/xap/1.0/\0"（29 字节） */
    val xmpApp1Prefix: ByteArray = byteArrayOf(
        0x68, 0x74, 0x74, 0x70, 0x3A, 0x2F, 0x2F, 0x6E, 0x73, 0x2E,
        0x61, 0x64, 0x6F, 0x62, 0x65, 0x2E, 0x63, 0x6F, 0x6D, 0x2F,
        0x78, 0x61, 0x70, 0x2F, 0x31, 0x2E, 0x30, 0x2F, 0x00
    )

    /** Exif APP1 段头："Exif\0\0"（6 字节） */
    internal val exifPrefix: ByteArray = byteArrayOf(
        0x45, 0x78, 0x69, 0x66, 0x00, 0x00
    )

    // 无长度字段的独立 marker
    private val standaloneMarkers: Set<Byte> = setOf(
        0x01.toByte(), 0xD8.toByte(), 0xD9.toByte(),
        0xD0.toByte(), 0xD1.toByte(), 0xD2.toByte(), 0xD3.toByte(),
        0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte()
    )

    internal class Segment(
        val marker: Byte,
        val segStart: Int,
        val totalLen: Int,
        val payloadStart: Int,
        val payloadLen: Int
    )

    /**
     * 遍历 JPEG 头部段（SOS 之前）。
     * 返回 Segment 序列：totalLen 含 marker 2 字节与长度 2 字节。
     * SOS 时停止。
     */
    fun iterateSegments(data: ByteArray, start: Int = 0): Sequence<Segment> = sequence {
        if (data.size - start < 4 || data[start] != 0xFF.toByte() || data[start + 1] != 0xD8.toByte()) {
            throw JpegException("不是有效的 JPEG（缺少 SOI）")
        }
        yield(Segment(0xD8.toByte(), start, 2, start + 2, 0))
        var pos = start + 2
        val size = data.size

        while (pos + 4 <= size) {
            if (data[pos] != 0xFF.toByte()) {
                throw JpegException("段边界错位 @$pos")
            }
            val marker = data[pos + 1]
            if (marker in standaloneMarkers) {
                yield(Segment(marker, pos, 2, pos + 2, 0))
                pos += 2
                continue
            }
            val segLen = BinaryUtils.readU16BE(data, pos + 2)
            if (segLen < 2 || pos + 2 + segLen > size) {
                throw JpegException("段长度非法 @$pos")
            }
            yield(Segment(marker, pos, 2 + segLen, pos + 4, segLen - 2))
            pos += 2 + segLen
            if (marker == 0xDA.toByte()) return@sequence
        }
    }

    /**
     * 从 SOS 段有效载荷终点扫描熵编码数据，返回 EOI(FFD9) 之后的偏移。
     * 熵编码规则：FF 00 为转义字面量；FF D0-D7 为重启 marker；FF D9 为 EOI。
     */
    fun findEoiEnd(data: ByteArray, sosPayloadEnd: Int): Int {
        var i = sosPayloadEnd
        val size = data.size

        while (i + 1 < size) {
            if (data[i] == 0xFF.toByte()) {
                val nxt = data[i + 1].toInt() and 0xFF
                if (nxt == 0x00 || nxt in 0xD0..0xD7) {
                    i += 2
                    continue
                }
                if (nxt == 0xD9) return i + 2
                i += 2 // 其他 marker 按 2 字节跳过
                continue
            }
            i++
        }
        throw JpegException("未找到 EOI（文件可能损坏）")
    }

    /**
     * 把可能由多个 JPEG 顺序拼接的数据拆成单个 JPEG 字节块列表。
     * 返回 (jpegList, consumed)。剩余非 JPEG 字节不在结果中。
     */
    fun splitJpegs(data: ByteArray): Pair<MutableList<ByteArray>, Int> {
        val result = mutableListOf<ByteArray>()
        var pos = 0
        val size = data.size

        while (pos + 4 <= size && data[pos] == 0xFF.toByte() && data[pos + 1] == 0xD8.toByte()) {
            var sosPayloadEnd: Int? = null
            for (seg in iterateSegments(data, pos)) {
                if (seg.marker == 0xDA.toByte()) {
                    sosPayloadEnd = seg.payloadStart + seg.payloadLen
                    break
                }
            }
            if (sosPayloadEnd == null) {
                throw JpegException("JPEG 缺少 SOS 段")
            }
            val eoiEnd = findEoiEnd(data, sosPayloadEnd)
            result.add(data.copyOfRange(pos, eoiEnd))
            pos = eoiEnd

            // 跳过后续 JPEG 之间可能的填充 0xFF
            while (pos < size && data[pos] == 0xFF.toByte() && pos + 1 < size && data[pos + 1] == 0xFF.toByte()) {
                pos++
            }
        }
        return result to pos
    }

    /** 从 SOF0/SOF2 段读取图像尺寸，返回 (width, height)；失败返回 (0, 0)。 */
    fun getDimensions(jpeg: ByteArray): Pair<Int, Int> {
        for (seg in iterateSegments(jpeg)) {
            val m = seg.marker.toInt() and 0xFF
            if (m == 0xC0 || m == 0xC1 || m == 0xC2 || m == 0xC3 ||
                m == 0xC5 || m == 0xC6 || m == 0xC7 ||
                m == 0xC9 || m == 0xCA || m == 0xCB ||
                m == 0xCD || m == 0xCE || m == 0xCF
            ) {
                if (seg.payloadLen >= 5) {
                    val height = BinaryUtils.readU16BE(jpeg, seg.payloadStart + 1)
                    val width = BinaryUtils.readU16BE(jpeg, seg.payloadStart + 3)
                    return width to height
                }
            }
        }
        return 0 to 0
    }

    /**
     * 检测 JPEG 的 EXIF 中是否存在非空 GPS IFD。
     *
     * 背景：Android 10+（本项目 minSdk 34）在应用缺少 ACCESS_MEDIA_LOCATION 时，
     * MediaProvider/FUSE 会在「读取层」实时改写媒体文件的 EXIF、剥离全部 GPS 标签，
     * 应用拿到的是脱敏后的字节。本工具是字节级无损管道——读到的字节若已被脱敏，
     * 转换产物同样无 GPS（不可事后恢复）。此检测用于把这一事实暴露给调用方展示。
     *
     * 注意：返回 false 仅代表「当前读到的字节」中无 GPS，不代表拍摄时未记录位置。
     */
    fun hasGpsExif(jpeg: ByteArray): Boolean {
        return try {
            for (seg in iterateSegments(jpeg)) {
                val m = seg.marker.toInt() and 0xFF
                if (m == 0xE1 && seg.payloadLen > exifPrefix.size &&
                    BinaryUtils.arrayEquals(jpeg, seg.payloadStart, exifPrefix)
                ) {
                    val tiffStart = seg.payloadStart + exifPrefix.size
                    return exifHasNonEmptyGpsIfd(jpeg, tiffStart, seg.payloadStart + seg.payloadLen)
                }
                if (m == 0xDA) return false
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 最小化 TIFF/EXIF 解析：IFD0 中 tag 0x8825（GPSInfo 指针）指向的 GPS IFD 非空
     * 才判定为「含 GPS」。解析失败一律视为无 GPS（仅用于诊断提示，不阻塞转换）。
     */
    private fun exifHasNonEmptyGpsIfd(data: ByteArray, tiffStart: Int, tiffEnd: Int): Boolean {
        if (tiffStart < 0 || tiffStart + 8 > tiffEnd || tiffEnd > data.size) return false
        val little = when {
            data[tiffStart] == 0x49.toByte() && data[tiffStart + 1] == 0x49.toByte() -> true  // "II"
            data[tiffStart] == 0x4D.toByte() && data[tiffStart + 1] == 0x4D.toByte() -> false // "MM"
            else -> return false
        }

        fun u16(off: Int): Int {
            if (off < 0 || off + 2 > tiffEnd) return -1
            return if (little)
                (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
            else
                ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
        }

        fun u32(off: Int): Long {
            if (off < 0 || off + 4 > tiffEnd) return -1L
            return if (little)
                (data[off].toInt() and 0xFF).toLong() or
                    ((data[off + 1].toInt() and 0xFF).toLong() shl 8) or
                    ((data[off + 2].toInt() and 0xFF).toLong() shl 16) or
                    ((data[off + 3].toInt() and 0xFF).toLong() shl 24)
            else
                ((data[off].toInt() and 0xFF).toLong() shl 24) or
                    ((data[off + 1].toInt() and 0xFF).toLong() shl 16) or
                    ((data[off + 2].toInt() and 0xFF).toLong() shl 8) or
                    (data[off + 3].toInt() and 0xFF).toLong()
        }

        val ifd0Offset = u32(tiffStart + 4).toInt()
        if (ifd0Offset < 0) return false
        val ifd0 = tiffStart + ifd0Offset
        val count = u16(ifd0)
        if (count <= 0) return false
        var i = 0
        while (i < count) {
            val entry = ifd0 + 2 + i * 12
            if (entry + 12 > tiffEnd) return false
            if (u16(entry) == 0x8825) {
                val gpsIfdOffset = u32(entry + 8).toInt()
                if (gpsIfdOffset < 0) return false
                val gpsCount = u16(tiffStart + gpsIfdOffset)
                return gpsCount > 0
            }
            i++
        }
        return false
    }

    internal class XmpSegment(val segStart: Int, val totalLen: Int, val xmpText: String)

    /** 定位 XMP APP1 段，返回 XmpSegment；无则 null。 */
    fun findXmpSegment(jpeg: ByteArray): XmpSegment? {
        for (seg in iterateSegments(jpeg)) {
            val m = seg.marker.toInt() and 0xFF
            if (m == 0xE1 && BinaryUtils.arrayEquals(jpeg, seg.payloadStart, xmpApp1Prefix)) {
                val start = seg.payloadStart + xmpApp1Prefix.size
                val end = start + seg.payloadLen - xmpApp1Prefix.size
                val xmpBytes = jpeg.copyOfRange(start, end)
                return XmpSegment(seg.segStart, seg.totalLen, String(xmpBytes, Charsets.UTF_8))
            }
        }
        return null
    }

    /** 构造 XMP APP1 段字节。 */
    fun buildXmpApp1(xmpText: String): ByteArray {
        val xmpBytes = xmpText.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(xmpApp1Prefix.size + xmpBytes.size)
        System.arraycopy(xmpApp1Prefix, 0, payload, 0, xmpApp1Prefix.size)
        System.arraycopy(xmpBytes, 0, payload, xmpApp1Prefix.size, xmpBytes.size)

        val result = ByteArray(4 + payload.size)
        result[0] = 0xFF.toByte()
        result[1] = 0xE1.toByte()
        BinaryUtils.writeU16BE(result, 2, payload.size + 2)
        System.arraycopy(payload, 0, result, 4, payload.size)
        return result
    }

    /**
     * 替换已有 XMP APP1 段；不存在则插入到第一个 APP1(Exif) 之后（无 APP1 则紧随 SOI）。
     */
    fun replaceOrInsertXmp(jpeg: ByteArray, newXmpText: String): ByteArray {
        val newSeg = buildXmpApp1(newXmpText)
        val found = findXmpSegment(jpeg)

        if (found != null) {
            val result = ByteArray(jpeg.size - found.totalLen + newSeg.size)
            System.arraycopy(jpeg, 0, result, 0, found.segStart)
            System.arraycopy(newSeg, 0, result, found.segStart, newSeg.size)
            System.arraycopy(jpeg, found.segStart + found.totalLen, result,
                found.segStart + newSeg.size, jpeg.size - found.segStart - found.totalLen)
            return result
        }

        // 插入位置：第一个 APP1(Exif) 之后，否则紧随 SOI
        var insertAt = 2
        for (seg in iterateSegments(jpeg)) {
            if (seg.marker in standaloneMarkers) continue
            val m = seg.marker.toInt() and 0xFF
            if (m == 0xE1 && BinaryUtils.arrayEquals(jpeg, seg.payloadStart, exifPrefix)) {
                insertAt = seg.segStart + seg.totalLen
            }
            break // 只看第一个非独立段
        }

        val final = ByteArray(jpeg.size + newSeg.size)
        System.arraycopy(jpeg, 0, final, 0, insertAt)
        System.arraycopy(newSeg, 0, final, insertAt, newSeg.size)
        System.arraycopy(jpeg, insertAt, final, insertAt + newSeg.size, jpeg.size - insertAt)
        return final
    }
}
