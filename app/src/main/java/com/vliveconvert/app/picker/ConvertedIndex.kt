package com.vliveconvert.app.picker

import android.content.ContentResolver
import android.provider.MediaStore

/**
 * 「已转换」索引：目标输出目录内 名称→媒体行 id 的轻量映射。
 *
 * 判定是**提示性**的（UI 角标/筛选），导出层（MediaExport.findPreviousOutput 的
 * 指纹校验）才是覆盖决策的权威——这里只用文件名形状，一次媒体库查询就够，
 * 不逐张嗅探 XMP（8.5k 张的规模不允许）。
 */
internal object ConvertedIndex {

    /**
     * 目标目录内全部图片行：小写文件名 → _ID（重名先到先得）。
     *
     * 为什么不用 `RELATIVE_PATH LIKE ?` 谓词：真机可用，但谓词过滤放进客户端后
     * 语义在测试环境与真机完全一致（SQLite LIKE 的 ASCII 大小写不敏感是隐式行为，
     * 显式写出来才守得住）；选择器打开本就要全库扫描一遍，多投影一列的代价可忽略。
     */
    fun load(resolver: ContentResolver, relPath: String): Map<String, Long> {
        val index = HashMap<String, Long>()
        val prefix = relPath.trimEnd('/').lowercase() + "/"
        try {
            resolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH
                ),
                null, null, null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val rel = (c.getString(iRel) ?: "").trimEnd('/').lowercase()
                    if (rel != prefix.trimEnd('/') && !rel.startsWith(prefix)) continue
                    val name = c.getString(iName) ?: continue
                    index.putIfAbsent(name.lowercase(), c.getLong(iId))
                }
            }
        } catch (_: Exception) {
        }
        return index
    }

    /**
     * 基名 [stem] 的双文件对是否已有转换产物：
     * 目标目录里存在 `stem.jpg`（id ≠ 自己——源对主图天然占着原名）
     * 或 `stem(n).jpg`（n≥1）。嵌套括号名（`stem(1)(1).jpg`）属于另一个源，不算。
     *
     * (n) 判定用手工解析而不是逐次编译正则：选择器按此函数全量过滤 8.5k 项，
     * 每项一次 `Regex(...)` 的编译开销不可接受。
     */
    fun isConverted(stem: String, ownImageId: Long, index: Map<String, Long>): Boolean {
        val exact = stem.lowercase() + ".jpg"
        val prefix = stem.lowercase() + "("
        for ((name, id) in index) {
            if (id == ownImageId) continue
            if (name == exact) return true
            if (name.startsWith(prefix) && name.endsWith(").jpg")) {
                // prefix 与 ").jpg" 之间必须全是数字：嵌套括号名（(1)(1).jpg）
                // 的中段含 ')'，属于另一个源
                val digits = name.substring(prefix.length, name.length - 5)
                if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) return true
            }
        }
        return false
    }
}
