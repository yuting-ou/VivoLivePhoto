package com.vliveconvert.app.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.round

/**
 * vivo 双文件实况照片（IMG_xxx.jpg + IMG_xxx.mp4）的识别与解析。
 * 逻辑取自 ZLivePhoto 项目的 VivoPlugin（detect/read），经真机验证。
 *
 * 双文件 JPG 尾部布局：[JPEG 主体（Primary + GainMap）]["vivo" 前缀][footer JSON][cameralbum! footer]，
 * footer 内含 livephoto ID（28 字符）与 imageTime（封面帧序号）；
 * 伴生 MP4 尾部：[vivoMediaEStream uuid][vivoMediaExtInfo uuid（内嵌源 footer 包装）]。
 *
 * footer 是**可选**的（议题 #2 破损修复）：vivo X200 系列相机「前台算法优化被中断」
 * （拍完立刻息屏/退后台）会留下破损实况——糊 JPG **没有** cameralbum! footer +
 * 相册不可见的伴生 MP4。footer 有则取 liveId/imageTime，无则由
 * [LivePhotoAsset.effectivePtsUs/effectiveImageTime] 的中点帧兜底；解析路径保持
 * 唯一（footer 解析只有 FooterUtil.parseFooter 一份实现，此处不做第二份）。
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

        return hasVivoFooter(path)
    }

    /**
     * JPG 尾部能否解析出含 livephoto ID 的 cameralbum! footer。
     * 正常双文件判定与破损判定共用这**唯一**的 footer 检查实现，禁止另写一份。
     *
     * 尾部窗口读取用 seek 而非 FileInputStream.skip：skip 的契约允许「少跳」，
     * 一旦发生窗口就会错位、footer 解析失败，该照片会被静默判为非双文件而漏收。
     *
     * 窗口**自适应放大**：footer JSON 可能比固定 8KB 长（人像实况 faceInfo 多人时
     * 最易触界），而 parseFooter 对「窗口太短」与「不是双文件」都返回 null、无法区分
     * ——固定窗口会把大 footer 的真双文件漏收进选择器。parseFooter 按 marker 从后
     * 向前定位，放大窗口不改变锚点，只可能让 jsonStart 从负变合法
     */
    private fun hasVivoFooter(path: String): Boolean {
        return try {
            val f = File(path)
            val size = f.length()
            RandomAccessFile(f, "r").use { raf ->
                for (window in longArrayOf(8192L, 32768L, 131072L, size)) {
                    val tailLen = minOf(window, size).toInt()
                    if (tailLen <= 0) return false
                    raf.seek(size - tailLen)
                    val tail = ByteArray(tailLen)
                    raf.readFully(tail)
                    if (FooterUtil.parseFooter(tail)?.livephotoId != null) return true
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 相机目录判定（误报硬防线之一）：破损候选仅收录相机拍照目录。
     * 与选择器 MediaRepo 的相册置顶判断共用同一口径（单一实现，picker 侧委托至此）。
     */
    internal fun isCameraDirPath(path: String?): Boolean {
        val p = path ?: return false
        return p.contains("/DCIM/Camera/", ignoreCase = true) ||
            p.contains("/DCIM/相册/", ignoreCase = true) ||
            p.contains("/DCIM/Camera", ignoreCase = true)
    }

    /** EXIF 机型校验（误报硬防线之一）：Make/Model 任一含 vivo 才认定为 vivo 相机产物 */
    private fun isVivoExif(path: String): Boolean {
        return try {
            val (make, model) = JpegUtil.exifMakeModel(File(path).readBytes())
            make?.contains("vivo", ignoreCase = true) == true ||
                model?.contains("vivo", ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 破损实况的判定结论（议题 #2）：破损形态 = 与 [isVivoDualFile] 共享同一套
     * 廉价前置（扩展名 → 同目录同名伴生 MP4 → XMP 无内嵌标记），唯独 footer 检查
     * 取反——正常双文件在此前已被选择器收走，剩下的「相机目录里的 JPG+MP4 对、
     * 无内嵌标记、无 footer」就是「优化被中断」的破损形态。
     *
     * 误报硬防线（缺一不可，从严避免误收录）：
     * 1. 仅收录相机目录（[isCameraDirPath]）
     * 2. EXIF 机型校验（[isVivoExif]）
     * 3. MP4 moov 必须存在——缺失即拍摄中断截断，结论为 [BrokenVerdict.VIDEO_CORRUPT]，
     *    不得进可修判定（修复页据此明确标注，转换入口据此明确拒绝，不做半成品转换）
     */
    fun brokenVerdict(path: String): BrokenVerdict {
        return try {
            if (!path.endsWith(".jpg", ignoreCase = true) &&
                !path.endsWith(".jpeg", ignoreCase = true)
            ) return BrokenVerdict.NOT_BROKEN
            val mp4 = siblingMp4(path) ?: return BrokenVerdict.NOT_BROKEN
            if (File(mp4).length() <= 8L) return BrokenVerdict.NOT_BROKEN
            if (XmpTemplate.parseMotionXmp(XmpTemplate.sniffXmp(path)).isMotion)
                return BrokenVerdict.NOT_BROKEN
            if (hasVivoFooter(path)) return BrokenVerdict.NOT_BROKEN
            if (!isCameraDirPath(path)) return BrokenVerdict.NOT_BROKEN
            if (!isVivoExif(path)) return BrokenVerdict.NOT_BROKEN
            if (Mp4Util.hasMoov(mp4)) BrokenVerdict.REPAIRABLE else BrokenVerdict.VIDEO_CORRUPT
        } catch (_: Exception) {
            BrokenVerdict.NOT_BROKEN
        }
    }

    /** 是否为「可修复」的破损实况（破损形态 + MP4 moov 完整）；修复页/转换入口的分发判据 */
    fun isBrokenDualFile(path: String): Boolean =
        brokenVerdict(path) == BrokenVerdict.REPAIRABLE

    /**
     * 解析为 LivePhotoAsset（字节级无损：JPEG 原样拆分，MP4 仅剥离尾部 uuid box）。
     *
     * footer 可选：正常双文件从 footer 取 liveId/imageTime；破损对（无 footer，
     * 见类注释）这两项为空，由 [LivePhotoAsset.effectivePtsUs/effectiveImageTime]
     * 的中点帧兜底，sourceFormat 标记为 "vivo_broken"。
     */
    fun read(path: String, log: (String, String, String) -> Unit): LivePhotoAsset {
        val data = File(path).readBytes()
        val footer = FooterUtil.parseFooter(data)
        val liveId = footer?.livephotoId
        if (footer == null) {
            log("info", "JPG 尾部无 vivo footer：按破损双文件实况解析（仅修复合并，画质无法还原）", "vivo")
        } else {
            log("info", "按 vivo 双文件实况照片解析", "vivo")
        }
        val mp4Path = siblingMp4(path)
            ?: throw VivoDualException("缺少伴生视频文件：${File(path).nameWithoutExtension}.mp4")

        // JPG 主体（footer 之前；破损对无 footer 则全文件按图像流解析）→ 拆 Primary / GainMap /
        // streamdata 附加块。直接在整份文件上按区间解析（不先 copyOfRange 出等长副本）：
        // 大照片这一份副本就与整图等大，省掉它可显著降低内存峰值。
        val bodyEnd = footer?.footerStart ?: data.size
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
        var imageTime: Long? = footer?.imageTime
        if (mp4Footer != null) {
            if (imageTime == null) imageTime = mp4Footer.imageTime
            val mp4Id = mp4Footer.livephotoId
            // JPG 无 footer（破损对）时 liveId 为空，无从比对，不算不一致
            if (liveId != null && mp4Id != null && mp4Id != liveId) {
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
            sourceFormat = if (footer == null) "vivo_broken" else "vivo_dual",
        )
        asset.livephotoId = liveId
        asset.imageTime = imageTime
        asset.videoInfo = Mp4Util.getTrackInfo(video) ?: mutableMapOf()
        // 透传数据：streamdata 附加块 + 源 JPG/MP4 footer 的完整字段
        // （供单文件写出时合并人像标记 moduleid/joint.refocus 等）
        asset.extras["vivo_streamdata"] = streamData
        footer?.json?.let { asset.extras["vivo_jpg_footer"] = it }
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

/** 破损实况判定结论（供修复页区分展示，转换入口据此明确拒绝，无静默失败） */
internal enum class BrokenVerdict {
    /** 不是破损形态（正常双文件 / 普通照片 / 不满足误报防线） */
    NOT_BROKEN,
    /** 破损形态且 MP4 完整（moov 存在）→ 可修复 */
    REPAIRABLE,
    /** 破损形态但 MP4 缺 moov（拍摄中断截断）→ 不可修：视频损坏 */
    VIDEO_CORRUPT,
}
