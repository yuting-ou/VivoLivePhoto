package com.vliveconvert.app.service

import com.vliveconvert.app.core.JpegUtil
import com.vliveconvert.app.core.VivoDual
import com.vliveconvert.app.ui.ConvertItem
import java.io.File

/**
 * 转换前的影响面预估（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * 动因：转换是「一堆照片一起处理」的批量操作，开启「删除原图」后还带有不可逆性。
 * 在此之前用户点下「开始转换」只能看到「N 张待转换」，既不知道有没有源文件已经丢失、
 * 也不知道会占用多少空间、会删掉多少文件——全凭事后看结果。
 * 本对象在开跑前把这些**确定的事实**汇总出来，供确认弹窗展示。
 *
 * 只统计无需读取文件内容即可确定的项（存在性 / 大小 / 伴生视频），
 * 因此开销极小；「是否含位置信息」由 [countSourceGps] 在 IO 线程异步普查
 * （逐张读文件头），结果补进预检弹窗——不打破 compute 的主线程纪律。
 */
internal object Preflight {

    /** 可用空间守卫结论 */
    enum class SpaceGuard { OK, INSUFFICIENT }

    /**
     * 空间守卫口径：可用 < 需求 + 1GB 预留 → 拦截（预留覆盖暂存/媒体库开销）。
     * usable 读不到（=0，个别文件系统/挂载点会返回 0）视为「无法判断」放行——
     * 宁可漏拦，不可误拦（误拦等于把能用的人挡在门外）。
     */
    fun spaceGuard(neededBytes: Long, usableBytes: Long): SpaceGuard = when {
        usableBytes <= 0L -> SpaceGuard.OK
        usableBytes < neededBytes + 1_000_000_000L -> SpaceGuard.INSUFFICIENT
        else -> SpaceGuard.OK
    }

    /**
     * 删除路径文案：授权「所有文件访问」时是直接删除，走 vivo 相册「第三方删除拦截」；
     * 未授权时是系统回收站（30 天）。预检必须如实说明用户能从哪找回。
     */
    fun deleteCopy(directDelete: Boolean): String =
        if (directDelete) "删除后可在 vivo 相册「第三方删除拦截」中查看/恢复"
        else "移入系统回收站，30 天内可恢复"

    /** 主存储可用字节（=0 表示无法判断） */
    fun usableBytes(): Long =
        try { File("/storage/emulated/0").usableSpace.takeIf { it > 0 } ?: 0L } catch (_: Exception) { 0L }

    class Info(
        /** 待转换条目数（不含已完成/已失败） */
        val pendingCount: Int,
        /** 源文件已不存在的条目数（转换时会失败或跳过） */
        val missingSources: Int,
        /** 可读取的源文件合计字节数（含伴生视频）——输出体积与之相当 */
        val totalSourceBytes: Long,
        /** 存在的伴生视频数（开启「删除原图」时这些也会被删） */
        val companionVideoCount: Int
    )

    fun compute(items: List<ConvertItem>): Info {
        var pending = 0
        var missing = 0
        var bytes = 0L
        var videos = 0
        for (ci in items) {
            if (ci.done || ci.failed) continue
            pending++
            val jpg = File(ci.item.path)
            if (!jpg.exists()) {
                missing++
                continue
            }
            bytes += jpg.length()
            val mp4 = VivoDual.siblingMp4(ci.item.path)
            if (mp4 != null) {
                bytes += File(mp4).length()
                videos++
            }
        }
        return Info(pending, missing, bytes, videos)
    }

    /** 源照片位置普查结果：哪些拍摄时记录了位置、哪些本来就没有 */
    class GpsCensus(
        /** 源字节含 GPS EXIF 的张数（转换后将保留位置） */
        val withGps: Int,
        /** 源字节无 GPS 的张数（拍摄时未记录位置，转后也不会有——与脱敏无关） */
        val withoutGps: Int
    )

    /**
     * 源照片位置普查（关1「先确定每一张都有位置」）：逐张读文件头判 EXIF GPS。
     *
     * 调用前提：**位置权限已授予**（门禁保证）——此时读到无 GPS 即拍摄时未记录，
     * 不存在脱敏误判；转换后产物若无位置反而说明管线失守（另由导出侧交付校验把关）。
     * 只读头部（EXIF 必在文件头），每张开销一次开流；在 IO 线程调用，结果异步
     * 补进预检弹窗——不打破 compute 的「主线程 stat 级」纪律。
     */
    fun countSourceGps(paths: List<String>): GpsCensus {
        var withGps = 0
        var withoutGps = 0
        for (path in paths) {
            val head = try {
                File(path).inputStream().use { ins ->
                    val buf = ByteArray(128 * 1024)
                    var n = 0
                    while (n < buf.size) {
                        val r = ins.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n <= 0) null else if (n < buf.size) buf.copyOf(n) else buf
                }
            } catch (_: Exception) {
                null
            } ?: continue
            if (JpegUtil.hasGpsExif(head)) withGps++ else withoutGps++
        }
        return GpsCensus(withGps, withoutGps)
    }

    /** 人类可读体积（保留一位小数；< 1MB 时用 KB） */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val mb = bytes / 1024.0 / 1024.0
        return when {
            mb < 1.0 -> "%.0f KB".format(bytes / 1024.0)
            mb < 1024.0 -> "%.1f MB".format(mb)
            else -> "%.1f GB".format(mb / 1024.0)
        }
    }
}
