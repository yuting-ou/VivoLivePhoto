package com.vliveconvert.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/** 分享生态的纯逻辑（intent 构造 / 一对双文件的配对判定 / 落地缓存），可 JVM 单测 */
object ShareIntents {

    /** 完成条目的分享 intent（不含 chooser；调用方用 Intent.createChooser 包装） */
    fun forLivePhoto(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * 从外部分享的一组 (uri, 文件名, mime) 里配出恰好一对 .jpg + .mp4：
     * 基名相同（大小写不敏感）才算一对；数量不对 / 基名不同 / 名字缺失返回 null。
     */
    fun classify(entries: List<Triple<Uri, String?, String?>>): Pair<Uri, Uri>? {
        val images = entries.filter { (it.third ?: "").startsWith("image/") }
        val videos = entries.filter { (it.third ?: "").startsWith("video/") }
        if (images.size != 1 || videos.size != 1) return null
        val imageStem = (images[0].second ?: "").substringBeforeLast('.').lowercase()
        val videoStem = (videos[0].second ?: "").substringBeforeLast('.').lowercase()
        if (imageStem.isEmpty() || imageStem != videoStem) return null
        return images[0].first to videos[0].first
    }

    /**
     * 把分享来的两个流拷进 cacheDir/shared/ 下的独立导入子目录：转换管线按**路径**读取，
     * 外部分享的 content URI 必须先落地成本地文件。
     * 视频小于 8B 视为无效（双文件识别的最小尾部要求），拒绝成对。
     * 文件名来自外部 Provider，是不可信输入；任何失败只清理本次导入自己的目录。
     */
    fun copyPair(
        context: Context,
        imageUri: Uri,
        videoUri: Uri,
        stem: String
    ): Pair<File, File>? {
        // 分隔符/NUL 会让产物越出缓存目录（File(parent, "/abs") 直接解析到 /abs）
        if (stem.any { it == '/' || it == '\\' || it.code == 0 }) return null
        val safeStem = stem.ifBlank { "shared_" + System.currentTimeMillis() }
        // 每次导入独立子目录：同名再次导入不得截断前次缓存（队列可能仍引用它）
        val dir = File(
            File(context.cacheDir, "shared"),
            "import_${System.nanoTime()}_${importSeq.incrementAndGet()}"
        )
        return try {
            if (!dir.mkdirs() && !dir.isDirectory) return null
            val img = File(dir, "$safeStem.jpg")
            val mp4 = File(dir, "$safeStem.mp4")
            val imgIn = context.contentResolver.openInputStream(imageUri)
                ?: throw java.io.IOException("无法读取分享的图片")
            imgIn.use { input -> img.outputStream().use { input.copyTo(it) } }
            val vidIn = context.contentResolver.openInputStream(videoUri)
                ?: throw java.io.IOException("无法读取分享的视频")
            vidIn.use { input -> mp4.outputStream().use { input.copyTo(it) } }
            if (img.length() == 0L || mp4.length() <= 8L) {
                throw java.io.IOException("分享文件为空或视频过小")
            }
            img to mp4
        } catch (_: Exception) {
            dir.deleteRecursively()
            null
        }
    }

    private val importSeq = java.util.concurrent.atomic.AtomicLong(0)
}
