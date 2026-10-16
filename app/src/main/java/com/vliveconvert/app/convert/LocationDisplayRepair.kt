package com.vliveconvert.app.convert

import android.content.ContentValues
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import com.vliveconvert.app.core.JpegUtil
import java.io.File
import kotlin.text.toByteArray

/**
 * 「恢复已有照片的位置显示」：给历史转换产物找回拍摄坐标。
 *
 * 两层修复（2026-10 真机结论）：
 * 1. **字节层恢复**：老版本（原作者 1.31.x 时代）在无位置权限时转换，系统读取层
 *    剥掉了 JPEG 的 EXIF GPS；但合并产物**原样拼接了源 mp4**，vivo 相机在 mp4 的
 *    udta/©xyz 原子里始终写有 ISO 6709 坐标串——从视频段解出坐标，用 ExifInterface
 *    写回该产物 JPEG 的 EXIF（先切出 JPEG 单独处理再拼回尾部，保证视频字节零改动）。
 * 2. **显示层补写**：vivo 相册的位置显示读媒体库 latitude/longitude 列、不回读文件
 *    EXIF；把坐标（文件自带的或刚恢复的）UPDATE 回媒体库行。
 *
 * 枚举范围：相机相册里的应用产物（XMP 含 VCamera:VMediaKitVersion 标记——相机原始
 * 双文件天然没有该标记）。UPDATE 只动媒体库，除坐标恢复外不改任何文件字节。
 */
internal object LocationDisplayRepair {

    /** 应用产物的 XMP 签名（vivo 模板独有；相机原始双文件不含） */
    private val APP_OUTPUT_MARKER: ByteArray = "VCamera:VMediaKitVersion".toByteArray()

    /** 头部嗅探长度：EXIF 与 XMP 都在文件头部，512KB 足够 */
    private const val SNIFF_LIMIT = 512 * 1024

    data class Stats(
        /** 扫描的图片总数 */
        val scanned: Int,
        /** 其中识别出的应用产物 */
        val outputs: Int,
        /** 文件字节无 GPS、成功从视频段恢复并写回的产物数 */
        val restored: Int,
        /** 坐标列补写成功的行数（含恢复的） */
        val repaired: Int,
        /** 应用产物中无 GPS 且视频段也恢复不出坐标的 */
        val noGps: Int
    )

    /**
     * 执行修复。[onProgress] (已扫描, 待处理总数) 用于进度展示。
     * 单行失败不中断整体（该行跳过，计入 scanned 不计入 repaired）。
     */
    fun repair(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Stats {
        val resolver = context.contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        data class Candidate(val uri: Uri)

        val candidates = ArrayList<Candidate>()
        try {
            resolver.query(
                images,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("DCIM/Camera", "DCIM/Camera/%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    candidates.add(Candidate(
                        ContentUrisWithId(images, c.getLong(0))))
                }
            }
        } catch (_: Exception) {}

        var scanned = 0
        var outputs = 0
        var restored = 0
        var repaired = 0
        var noGps = 0
        // 列补写失败原因诊断：只记前 3 条，避免刷爆日志
        var diagLogged = 0
        val total = candidates.size
        for ((index, candidate) in candidates.withIndex()) {
            scanned++
            if (index % 50 == 0) onProgress(index, total)
            try {
                val header = readHeader(resolver, candidate.uri) ?: continue
                // 非应用产物（相机原始双文件、用户其它照片）跳过：
                // XMP 含 VCamera:VMediaKitVersion 的才是本工具的转换产物
                if (!containsBytes(header, APP_OUTPUT_MARKER)) continue
                outputs++
                var coords = JpegUtil.gpsCoordinates(header)
                if (coords == null) {
                    // 文件字节无 GPS（老版本无权限时被系统读取层剥掉）：
                    // 从产物自身嵌入的视频段恢复坐标，写回 EXIF 后再走列补写
                    val full = readAll(resolver, candidate.uri) ?: run { noGps++; continue }
                    val recovered = GpsRecovery.recoverFromVideoSegment(full)
                        ?: run { noGps++; continue }
                    val newBytes = injectGpsExif(context, full, recovered)
                        ?: run { noGps++; continue }
                    val written = try {
                        resolver.openOutputStream(candidate.uri, "w")?.use {
                            it.write(newBytes)
                            true
                        } == true
                    } catch (_: Exception) {
                        false
                    }
                    if (!written) { noGps++; continue }
                    restored++
                    coords = recovered
                    // EXIF 增长会改变文件大小，SIZE 列必须同步
                    try {
                        resolver.update(candidate.uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.SIZE, newBytes.size.toLong())
                        }, null, null)
                    } catch (_: Exception) {}
                }
                val updated = try {
                    resolver.update(candidate.uri, ContentValues().apply {
                        put("latitude", coords.first)
                        put("longitude", coords.second)
                    }, null, null)
                } catch (e: Exception) {
                    if (diagLogged < 3) {
                        android.util.Log.e("GpsRepair", "列补写异常 uri=$candidate", e)
                        diagLogged++
                    }
                    0
                }
                if (updated > 0) {
                    repaired++
                } else if (diagLogged < 3) {
                    // 返回 0 行：Provider 忽略/拒绝了坐标列写入——记一条用于真机诊断
                    android.util.Log.w(
                        "GpsRepair", "列补写返回 $updated 行 uri=$candidate")
                    diagLogged++
                }
            } catch (_: Exception) {
                // 单个文件损坏/读取失败：跳过，继续整体修复
            }
        }
        onProgress(total, total)
        return Stats(scanned, outputs, restored, repaired, noGps)
    }

    /**
     * 把坐标写进合并产物的 EXIF：**必须先切出 JPEG 部分（SOI..EOI）单独交给
     * ExifInterface**——它重写文件时会截掉 EOI 之后的全部数据，直接喂合并文件
     * 会毁掉嵌入的视频。改写后的 JPEG 与原样尾部重新拼接；自检失败则返回 null
     * （调用方保持原文件不动）。
     */
    private fun injectGpsExif(
        context: Context,
        data: ByteArray,
        coords: Pair<Double, Double>
    ): ByteArray? {
        return try {
            var sosPayloadEnd: Int? = null
            for (seg in JpegUtil.iterateSegments(data)) {
                if (seg.marker == 0xDA.toByte()) {
                    sosPayloadEnd = seg.payloadStart + seg.payloadLen
                    break
                }
            }
            val eoiEnd = sosPayloadEnd?.let { JpegUtil.findEoiEnd(data, it) } ?: return null
            val tail = data.copyOfRange(eoiEnd, data.size)
            val tmp = File.createTempFile("gpsfix", ".jpg", context.cacheDir)
            try {
                tmp.writeBytes(data.copyOfRange(0, eoiEnd))
                ExifInterface(tmp.path).apply {
                    setLatLong(coords.first, coords.second)
                    saveAttributes()
                }
                val newJpeg = tmp.readBytes()
                // 自检：改写后的 JPEG 必须能解出坐标且仍是合法 JPEG
                if (JpegUtil.gpsCoordinates(newJpeg) == null) return null
                newJpeg + tail
            } finally {
                tmp.delete()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ContentUrisWithId(collection: Uri, id: Long): Uri =
        android.content.ContentUris.withAppendedId(collection, id)

    private fun readHeader(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): ByteArray? {
        return resolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(SNIFF_LIMIT)
            var n = 0
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n <= 0) null else if (n < buf.size) buf.copyOf(n) else buf
        }
    }

    private fun readAll(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): ByteArray? {
        return try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun containsBytes(data: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || data.size < needle.size) return false
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
