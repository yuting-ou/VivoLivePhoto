package com.vliveconvert.app.service

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 精确「已转」索引：源图片绝对路径（小写）→ 转换产物 URI。
 *
 * 选择器此前只能按「同名/同基名(n)」启发式猜，同名碰撞时会误标；转换成功时
 * 服务知道确切的源路径与产物 URI，落盘成一张小映射即可精确判定。上限 5000
 * （超出淘汰最旧），原子写与损坏容错照 BatchLog：辅助索引绝不能拖垮主流程。
 */
internal object ConvertedStore {

    private const val FILE = "converted_store.json"
    private const val CAP = 5000
    private val lock = Any()

    fun record(context: Context, sourcePath: String, outUri: String) {
        if (sourcePath.isBlank() || outUri.isBlank()) return
        synchronized(lock) {
            try {
                val pairs = readPairs(context).toMutableList()
                val key = sourcePath.lowercase()
                pairs.removeAll { it.first == key }
                pairs.add(key to outUri)
                val trimmed = pairs.takeLast(CAP)
                val f = File(context.filesDir, FILE)
                val tmp = File(context.filesDir, "$FILE.tmp")
                tmp.writeText(toJson(trimmed))
                if (!tmp.renameTo(f)) {
                    f.writeText(toJson(trimmed))
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun load(context: Context): Map<String, String> =
        try { readPairs(context).toMap() } catch (_: Exception) { emptyMap() }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE).delete() } catch (_: Exception) {}
    }

    private fun readPairs(context: Context): List<Pair<String, String>> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val row = arr.optJSONArray(i) ?: return@mapNotNull null
                val path = row.optString(0)
                val uri = row.optString(1)
                if (path.isBlank() || uri.isBlank()) null else path to uri
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toJson(pairs: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((path, uri) in pairs) arr.put(JSONArray().put(path).put(uri))
        return arr.toString()
    }
}
