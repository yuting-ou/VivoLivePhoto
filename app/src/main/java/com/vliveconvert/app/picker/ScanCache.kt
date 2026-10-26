package com.vliveconvert.app.picker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 选择器冷启动缓存：已判定通过的双文件实况（按 item.key = id:size:modified）。
 *
 * 只存「判定结论」，不存扫描进度：命中 key（文件未编辑）直接展示，未命中再扫。
 * 文件被编辑 → key 变化 → 自然重扫；已删除项不在当前查询里 → 不会复活。
 * 上限 12000/相册；损坏静默空表（缓存绝不能拖垮选择器）。
 */
class ScanCache(private val context: Context) {

    private val lock = Any()

    fun load(bucketId: Long): Map<String, MediaItem> {
        val arr = readAll()[bucketId.toString()] ?: return emptyMap()
        val out = HashMap<String, MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            out[key] = MediaItem(
                id = o.optLong("id"),
                path = o.optString("path"),
                name = o.optString("name"),
                bucketId = o.optLong("bucket"),
                dateTaken = o.optLong("taken"),
                dateModified = o.optLong("modified"),
                size = o.optLong("size")
            )
        }
        return out
    }

    fun save(bucketId: Long, accepted: List<MediaItem>) {
        synchronized(lock) {
            try {
                val all = readAll()
                val arr = JSONArray()
                accepted.take(12000).forEach { m ->
                    arr.put(
                        JSONObject()
                            .put("key", m.key)
                            .put("id", m.id).put("path", m.path).put("name", m.name)
                            .put("bucket", m.bucketId).put("taken", m.dateTaken)
                            .put("modified", m.dateModified).put("size", m.size)
                    )
                }
                all[bucketId.toString()] = arr
                val root = JSONObject()
                for ((k, v) in all) root.put(k, v)
                val f = File(context.filesDir, FILE)
                val tmp = File(context.filesDir, "$FILE.tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(root.toString())
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readAll(): MutableMap<String, JSONArray> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return HashMap()
        return try {
            val root = JSONObject(f.readText())
            val out = HashMap<String, JSONArray>()
            for (key in root.keys()) {
                root.optJSONArray(key)?.let { out[key] = it }
            }
            out
        } catch (_: Exception) {
            HashMap()
        }
    }

    private companion object {
        const val FILE = "picker_cache.json"
    }
}

/** 把当前相册图片分成（缓存命中，需扫描）两组；key 不一致（文件被编辑）视为需扫描 */
internal fun partitionCached(
    current: List<MediaItem>,
    cached: Map<String, MediaItem>
): Pair<List<MediaItem>, List<MediaItem>> = current.partition { cached.containsKey(it.key) }
