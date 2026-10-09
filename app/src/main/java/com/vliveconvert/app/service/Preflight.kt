package com.vliveconvert.app.service

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
 * 因此开销极小；「是否含位置信息」不在其中（需读文件内容，成本高且可能误判）。
 */
internal object Preflight {

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

    /** 人类可读体积（保留一位小数；< 1MB 时用 KB） */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 1.0) {
            "%.0f KB".format(bytes / 1024.0)
        } else {
            "%.1f MB".format(mb)
        }
    }
}
