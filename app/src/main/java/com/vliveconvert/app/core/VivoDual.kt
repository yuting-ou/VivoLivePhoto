package com.vliveconvert.app.core

import java.io.File
import java.io.FileInputStream
import kotlin.math.round

/**
 * vivo 双文件实况照片（IMG_xxx.jpg + IMG_xxx.mp4）的识别与解析。
 * 逻辑取自 ZLivePhoto 项目的 VivoPlugin（detect/read），经真机验证。
 *
 * 双文件 JPG 尾部布局：[JPEG 主体（Primary + GainMap）]["vivo" 前缀][footer JSON][cameralbum! footer]，
 * footer 内含 livephoto ID（28 字符）与 imageTime（封面帧序号）；
 * 伴生 MP4 尾部：[vivoMediaEStream uuid][vivoMediaExtInfo uuid（内嵌源 footer 包装）]。
 */
internal object VivoDual {

    /**
     * 同目录同名 .mp4 伴生视频路径；不存在返回 null。
     *
     * 大小写策略：JPG 侧用 `ignoreCase` 判定扩展名，MP4 侧原先却只硬拼小写 `.mp4`
     * 再 `exists()`——标准不一致。在区分大小写的文件系统（Android 的 ext4/F2FS）上，
     * 相机若写出 `IMG_001.MP4`，这张照片会被判为「非双文件」而在选择器里静默消失。
     * 故这里额外尝试大写扩展名。
     *
     * 为什么不做「遍历目录做不区分大小写的全匹配」：本函数在扫描相册时对**每张 JPG**
     * 调用一次，而对没有伴生视频的普通照片，全匹配要把整个 DCIM/Camera 列一遍，
     * 千张相册会退化成 O(n²)。大写变体已覆盖现实中的全部情形，代价只是多一次 stat。
     */
    fun siblingMp4(path: String): String? {
        val file = File(path)
        val parent = file.parentFile
        val stem = file.nameWithoutExtension
        for (ext in listOf("mp4", "MP4")) {
            val candidate = if (parent != null) File(parent, "$stem.$ext") else File("$stem.$ext")
            if (candidate.exists()) return candidate.path
        }
        return null
    }

    /**
     * 是否为 vivo 双文件实况照片：
     * 1) JPG 扩展名；2) 存在同目录同名 .mp4；3) XMP 无内嵌动态照片标记
     *    （排除 Google/OPPO/小米等内嵌单文件）；4) JPG 尾部能解析出含 livephoto ID 的 cameralbum! footer。
     *
     * 判定顺序刻意按「由廉价到昂贵」排列：扩展名 → 同目录文件存在性（stat）
     * → 头部 XMP（读头部）→ 尾部 footer（读尾部）。调用方（内置选择器逐张判定相册）
     * 依赖这个顺序避免无谓的磁盘读取，**不要调整顺序，也不要在调用侧重复实现一遍**。
     */
    fun isVivoDualFile(path: String): Boolean {
        if (!path.endsWith(".jpg", ignoreCase = true) &&
            !path.endsWith(".jpeg", ignoreCase = true)
        ) return false
        // 伴生视频存在且非空：空文件不可能是实况视频，提前排除避免后续读取白费
        val mp4 = siblingMp4(path) ?: return false
        if (File(mp4).length() <= 8L) return false
        if (XmpTemplate.parseMotionXmp(XmpTemplate.sniffXmp(path)).isMotion) return false

        return try {
            val f = File(path)
            val size = f.length()
            FileInputStream(path).use { fs ->
                fs.skip(maxOf(0L, size - 8192))
                val tail = ByteArray(minOf(8192L, size).toInt())
                var read = 0
                while (read < tail.size) {
                    val n = fs.read(tail, read, tail.size - read)
                    if (n < 0) break
                    read += n
                }
                val footer = FooterUtil.parseFooter(tail.copyOfRange(0, read))
                footer?.livephotoId != null
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 解析为 LivePhotoAsset（字节级无损：JPEG 原样拆分，MP4 仅剥离尾部 uuid box）。
     */
    fun read(path: String, log: (String, String, String) -> Unit): LivePhotoAsset {
        log("info", "按 vivo 双文件实况照片解析", "vivo")
        val data = File(path).readBytes()
        val footer = FooterUtil.parseFooter(data)
            ?: throw VivoDualException("JPG 尾部未找到 vivo livephoto 标记")

        val liveId = footer.livephotoId!!
        val mp4Path = siblingMp4(path)
            ?: throw VivoDualException("缺少伴生视频文件：${File(path).nameWithoutExtension}.mp4")

        // JPG 主体（footer 之前）→ 拆 Primary / GainMap / streamdata 附加块。
        // 直接在整份文件上按区间解析（[0, footerStart)），不先 copyOfRange 出等长副本：
        // 大照片这一份副本就与整图等大，省掉它可显著降低内存峰值。
        val bodyEnd = footer.footerStart
        val (jpegs, consumed) = JpegUtil.splitJpegs(data, 0, bodyEnd)
        if (jpegs.isEmpty()) throw VivoDualException("JPG 主体解析失败")
        val primary = jpegs[0]
        val gainmap = if (jpegs.size > 1) jpegs[1] else null
        // JPEG 之后的附加数据块（"streamdata" 魔数开头的 vivo 私有流）：
        // 普通实况约 114B（DEGS 流信息）；人像实况约 4MB（IAC 深度/虚化数据，
        // 丢失会导致相册不再显示人像徽标、无法后编辑光圈/虚化）。必须原样透传。
        val streamData = if (consumed < bodyEnd) data.copyOfRange(consumed, bodyEnd) else ByteArray(0)

        // MP4：剥离末尾 vivoMediaExtInfo uuid box（内嵌源 footer 包装）
        val mp4Raw = File(mp4Path).readBytes()
        val mp4Footer = FooterUtil.parseFooter(mp4Raw)
        var imageTime: Long? = footer.imageTime
        if (mp4Footer != null) {
            if (imageTime == null) imageTime = mp4Footer.imageTime
            val mp4Id = mp4Footer.livephotoId
            if (mp4Id != null && mp4Id != liveId) {
                log("warning", "JPG 与 MP4 的 livephoto ID 不一致：$liveId / $mp4Id", "vivo")
            }
        }

        val video = Mp4Util.stripVivoUuid(mp4Raw)
        if (!Mp4Util.hasFtyp(video))
            throw VivoDualException("伴生 MP4 无效（缺少 ftyp box）")

        val asset = LivePhotoAsset(
            primaryJpeg = primary,
            gainmapJpeg = gainmap,
            videoMp4 = video,
            sourceFormat = "vivo_dual",
        )
        asset.livephotoId = liveId
        asset.imageTime = imageTime
        asset.videoInfo = Mp4Util.getTrackInfo(video) ?: mutableMapOf()
        // 透传数据：streamdata 附加块 + 源 JPG/MP4 footer 的完整字段
        // （供单文件写出时合并人像标记 moduleid/joint.refocus 等）
        asset.extras["vivo_streamdata"] = streamData
        footer.json?.let { asset.extras["vivo_jpg_footer"] = it }
        mp4Footer?.json?.let { asset.extras["vivo_mp4_footer"] = it }

        // 源 JPEG「读到的字节」是否含 GPS EXIF：
        // 未授予 ACCESS_MEDIA_LOCATION 时，系统（MediaStore/FUSE）在读取层已把 GPS 剥离，
        // 此处为 false——供上层向用户提示「转换产物将丢失地点信息」。
        asset.extras["source_has_gps"] = JpegUtil.hasGpsExif(primary)

        // 由 imageTime（帧序号）反推封面时间戳
        val imageTimeVal = asset.imageTime
        val fps = asset.videoInfo["fps"] as? Double
        if (imageTimeVal != null && fps != null && fps > 0.0) {
            asset.presentationTsUs = round(imageTimeVal.toDouble() / fps * 1_000_000.0).toLong()
        }
        return asset
    }
}

internal class VivoDualException(message: String) : Exception(message)
