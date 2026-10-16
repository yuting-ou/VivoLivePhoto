package com.vliveconvert.app.convert

import com.vliveconvert.app.core.JpegUtil

/**
 * 从产物自身恢复 GPS 坐标。
 *
 * 背景：老版本（原作者 1.31.x 时代）在未授予位置权限时转换，系统读取层剥掉了
 * JPEG 的 EXIF GPS，产物因此无位置；但合并产物**原样拼接了源 mp4**，而 vivo 相机
 * 在 mp4 的 udta/©xyz 原子里始终写入 ISO 6709 坐标串（如 `+22.8389+108.1780/`）。
 * 位置其实一直睡在产物文件的视频段里——解出来即可回写。
 */
internal object GpsRecovery {

    /**
     * ISO 6709 坐标串（vivo ©xyz 原子内容）：`±DD.DDDD±DDD.DDDD[/±H.HH/]`。
     * vivo 实测 4 位小数、纬度 2-3 位、经度 3 位、高度可省。
     */
    private val ISO6709 = Regex(
        "([+-])(\\d{2,3})\\.(\\d{2,6})([+-])(\\d{2,3})\\.(\\d{2,6})(?:[+-]\\d{1,3}\\.\\d{1,6})?/"
    )

    /**
     * 从 [data] 的 [start] 起找第一个 ISO 6709 坐标串，返回 (纬度, 经度)。
     * 南纬/西经由符号决定正负。
     */
    fun parseIso6709From(data: ByteArray, start: Int = 0, end: Int = data.size): Pair<Double, Double>? {
        if (start < 0 || end > data.size || start >= end) return null
        val text = String(data, start, end - start, Charsets.ISO_8859_1)
        val m = ISO6709.find(text) ?: return null
        val (sign1, latD, latF, sign2, lonD, lonF) = m.destructured
        val lat = (latD.toDouble() + latF.toDouble() / powers10(latF.length)) *
            (if (sign1 == "-") -1.0 else 1.0)
        val lon = (lonD.toDouble() + lonF.toDouble() / powers10(lonF.length)) *
            (if (sign2 == "-") -1.0 else 1.0)
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }

    private fun powers10(n: Int): Double = when (n) {
        0 -> 1.0; 1 -> 10.0; 2 -> 100.0; 3 -> 1000.0
        4 -> 10000.0; 5 -> 100000.0; 6 -> 1000000.0
        else -> Math.pow(10.0, n.toDouble())
    }

    /**
     * 在合并产物的**视频段**（JPEG EOI 之后）里找坐标。
     * JPEG 部分不搜：产物 XMP 理论上可能残留 GPS 字样，避免把非拍摄地的串当坐标。
     */
    fun recoverFromVideoSegment(data: ByteArray): Pair<Double, Double>? {
        return try {
            var sosPayloadEnd: Int? = null
            for (seg in JpegUtil.iterateSegments(data)) {
                if (seg.marker == 0xDA.toByte()) {
                    sosPayloadEnd = seg.payloadStart + seg.payloadLen
                    break
                }
            }
            val eoiEnd = sosPayloadEnd?.let { JpegUtil.findEoiEnd(data, it) } ?: return null
            parseIso6709From(data, eoiEnd)
        } catch (_: Exception) {
            null
        }
    }
}
