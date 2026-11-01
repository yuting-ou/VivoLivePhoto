package com.vliveconvert.app.convert

import androidx.exifinterface.media.ExifInterface
import com.vliveconvert.app.core.BinaryUtils
import com.vliveconvert.app.core.JpegUtil
import java.io.File
import kotlin.math.abs

/**
 * 从产物/视频里恢复 GPS 坐标，并把坐标写回 JPEG EXIF。
 *
 * 背景：老版本（原作者 1.31.x 时代）在未授予位置权限时转换，系统读取层剥掉了
 * JPEG 的 EXIF GPS；而 vivo 相机在 mp4 的 udta/©xyz 原子里始终写入 ISO 6709
 * 坐标串（如 `+22.8389+108.1780/`），位置其实一直睡在视频段里。
 *
 * v2.6.0-test27 加固（对抗性复盘结论）：
 * - **坐标判定一律带范围校验**（纬度 ±90、经度 ±180，0,0 不是位置是海）——
 *   此前无校验，二进制误命中产生的超范围坐标会让 ExifInterface.setLatLong 抛异常，
 *   注入静默失败、产物丢位置（「3% 集中丢失」的候选根因之一）
 * - **mp4 扫描先在 ©xyz 盒子窗口内找**，找不到再全量扫（全量扫的命中同样过校验），
 *   杜绝把压缩数据里的随机字节当坐标
 * - **注入失败显式化**：返回 [InjectResult]（含失败原因），不再是可静默吞掉的 null
 */
internal object GpsRecovery {

    /**
     * ISO 6709 坐标串（vivo ©xyz 原子内容）：`±DD.DDDD±DDD.DDDD[/±H.HH/]`。
     * vivo 实测 4 位小数、纬度 2-3 位、经度 3 位、高度可省。
     */
    private val ISO6709 = Regex(
        "([+-])(\\d{2,3})\\.(\\d{2,6})([+-])(\\d{2,3})\\.(\\d{2,6})(?:[+-]\\d{1,3}\\.\\d{1,6})?/"
    )

    /** ©xyz box 标记（box size 之后紧跟的 type）：0xA9 78 79 7A */
    private val XXYZ_MARKER = byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A)

    /**
     * 从 [data] 的 [start] 起找第一个**通过范围校验**的 ISO 6709 坐标串。
     * 超范围/0,0 的命中跳过继续找——误命中不再污染结果。
     */
    fun parseIso6709From(data: ByteArray, start: Int = 0, end: Int = data.size): Pair<Double, Double>? {
        if (start < 0 || end > data.size || start >= end) return null
        val text = String(data, start, end - start, Charsets.ISO_8859_1)
        for (m in ISO6709.findAll(text)) {
            parseMatch(m.destructured)?.let { return it }
        }
        return null
    }

    /** 解析并校验单个匹配；非法返回 null。 */
    private fun parseMatch(d: MatchResult.Destructured): Pair<Double, Double>? {
        val (sign1, latD, latF, sign2, lonD, lonF) = d
        val lat = (latD.toDouble() + latF.toDouble() / powers10(latF.length)) *
            (if (sign1 == "-") -1.0 else 1.0)
        val lon = (lonD.toDouble() + lonF.toDouble() / powers10(lonF.length)) *
            (if (sign2 == "-") -1.0 else 1.0)
        // 范围校验：纬度 ±90、经度 ±180——超界的「命中」是二进制误命中，不是坐标
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        if (lat == 0.0 && lon == 0.0) return null // 0,0 是无位置，不是海上的家
        return lat to lon
    }

    private fun powers10(n: Int): Double = when (n) {
        0 -> 1.0; 1 -> 10.0; 2 -> 100.0; 3 -> 1000.0
        4 -> 10000.0; 5 -> 100000.0; 6 -> 1000000.0
        else -> Math.pow(10.0, n.toDouble())
    }

    /**
     * 从**内存中的 mp4 字节**恢复坐标：
     * 1. 先逐个 ©xyz box 的窗口内解析（vivo 的坐标就写在这里，窗口命中最可信）；
     * 2. 找不到再全量扫（命中同样过范围校验，合法范围内的误命中概率可忽略）。
     *
     * 调用方已持有视频字节（转换管线 / 产物修复），不再整文件二次读盘；
     * 并发下也不会为扫描额外复制整段 mp4。
     */
    fun recoverFromMp4Bytes(video: ByteArray): Pair<Double, Double>? {
        var idx = BinaryUtils.indexOf(video, XXYZ_MARKER, 0)
        while (idx >= 0) {
            val from = maxOf(0, idx - 4)
            val to = minOf(video.size, idx + 4 + 512)
            parseIso6709From(video, from, to)?.let { return it }
            idx = BinaryUtils.indexOf(video, XXYZ_MARKER, idx + 1)
        }
        return parseIso6709From(video, 0)
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
            recoverFromMp4BytesRange(data, eoiEnd)
        } catch (_: Exception) {
            null
        }
    }

    /** 在 [from] 到文件尾的区间里找坐标（先 ©xyz 窗口、再区间全量扫）。 */
    private fun recoverFromMp4BytesRange(data: ByteArray, from: Int): Pair<Double, Double>? {
        var idx = BinaryUtils.indexOf(data, XXYZ_MARKER, from)
        while (idx >= 0) {
            val wFrom = maxOf(0, idx - 4)
            val to = minOf(data.size, idx + 4 + 512)
            parseIso6709From(data, wFrom, to)?.let { return it }
            idx = BinaryUtils.indexOf(data, XXYZ_MARKER, idx + 1)
        }
        return parseIso6709From(data, from)
    }

    /** 注入结果：成功给字节；失败给**可读原因**（绝不静默，调用方必须显式处理）。 */
    sealed class InjectResult {
        class Ok(val bytes: ByteArray) : InjectResult()
        class Fail(val reason: String) : InjectResult()
    }

    /**
     * 把坐标写进 JPEG 数据的 EXIF：**必须先切出 JPEG 部分（SOI..EOI）单独交给
     * ExifInterface**——它重写文件时会截掉 EOI 之后的全部数据，直接喂合并文件
     * 或带 footer 的双文件源 JPG 会毁掉尾部（嵌入视频 / footer）。改写后的 JPEG
     * 与原样尾部重新拼接。
     *
     * 失败返回 [InjectResult.Fail] 并带原因（结构无法定位 / 坐标非法 / EXIF 重写
     * 失败 / 写后自检读不回）——任何一环失败都**显式**，不允许静默降级。
     * 自检会回读坐标并与目标值比对，防止「写进去的不是我们要的」。
     */
    fun injectGpsExif(
        data: ByteArray,
        coords: Pair<Double, Double>
    ): InjectResult {
        return try {
            var sosPayloadEnd: Int? = null
            for (seg in JpegUtil.iterateSegments(data)) {
                if (seg.marker == 0xDA.toByte()) {
                    sosPayloadEnd = seg.payloadStart + seg.payloadLen
                    break
                }
            }
            val eoiEnd = sosPayloadEnd?.let { JpegUtil.findEoiEnd(data, it) }
                ?: return InjectResult.Fail("无法定位 JPEG 图像数据边界（SOS/EOI），文件结构非常规")
            val tail = data.copyOfRange(eoiEnd, data.size)
            // java.io.tmpdir：Android 上是应用 cache 目录；JVM 测试是系统临时目录。
            // 不再依赖 Context（原语与 UI 层解耦，管线任意处都可调用）。
            val tmp = File.createTempFile("gpsfix", ".jpg")
            try {
                tmp.writeBytes(data.copyOfRange(0, eoiEnd))
                // EXIF 库初始化/构造/保存：单独兜住 LinkageError——
                // androidx ExifInterface 依赖 android.util.Log/Pair，在非 Android 环境
                // （纯 JVM 测试、个别 ROM 类加载异常）初始化直接抛 Error。
                // 「库不可用」必须降级为显式 Fail，绝不能让 Error 炸穿整个转换批次
                val newJpeg = try {
                    ExifInterface(tmp.path).apply {
                        setLatLong(coords.first, coords.second)
                        saveAttributes()
                    }
                    tmp.readBytes()
                } catch (e: LinkageError) {
                    return InjectResult.Fail(
                        "EXIF 库不可用（${e.javaClass.simpleName}），本张跳过注入")
                }
                val check = JpegUtil.gpsCoordinates(newJpeg)
                    ?: return InjectResult.Fail("EXIF 重写后自检读不回坐标（写入了但解析不到）")
                if (abs(check.first - coords.first) > 1e-4 ||
                    abs(check.second - coords.second) > 1e-4
                ) {
                    return InjectResult.Fail(
                        "EXIF 写后自检坐标不一致（期望 ${coords.first},${coords.second}，" +
                            "读回 ${check.first},${check.second}）")
                }
                InjectResult.Ok(newJpeg + tail)
            } finally {
                tmp.delete()
            }
        } catch (e: IllegalArgumentException) {
            InjectResult.Fail("坐标值非法被 EXIF 库拒绝（${e.message}）")
        } catch (e: Exception) {
            InjectResult.Fail("EXIF 重写失败（${e.javaClass.simpleName}: ${e.message}）")
        }
    }
}
