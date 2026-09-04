package com.vliveconvert.app.service

import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.ui.ConvertItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 转换队列的 JSON 序列化（纯函数，无 Android/Compose 依赖，可 JVM 单测）。
 *
 * 崩溃背景（v1.0.7~1.0.8）：队列持久化曾在 IO 线程直接迭代 Compose 的
 * SnapshotStateList，与主线程的条目状态写并发 → ConcurrentModificationException
 * → 转换中闪退。修复原则：**任何线程要遍历队列，只能用主线程取出的不可变快照**；
 * 本对象只接收/返回普通 List，彻底隔离 Compose 状态。
 */
internal object QueueJson {

    fun toJson(snapshot: List<ConvertItem>): String {
        val arr = JSONArray()
        for (ci in snapshot) {
            val m = ci.item
            arr.put(JSONObject()
                .put("id", m.id).put("path", m.path).put("name", m.name)
                .put("bucketId", m.bucketId).put("dateTaken", m.dateTaken)
                .put("dateModified", m.dateModified).put("size", m.size)
                .put("status", ci.status)
                .put("done", ci.done).put("failed", ci.failed))
        }
        return arr.toString()
    }

    /**
     * 从 JSON 恢复队列条目；损坏返回 null。
     * 中断时处于「转换中…」的条目重置为「待转换」；
     * 「待原图删除后落地」的中转条目降级为明确指引（原图可能还在，同名会冲突）。
     */
    fun fromJson(text: String): List<ConvertItem>? {
        return try {
            val arr = JSONArray(text)
            val list = mutableListOf<ConvertItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val item = MediaItem(
                    id = o.getLong("id"),
                    path = o.optString("path"),
                    name = o.optString("name"),
                    bucketId = o.optLong("bucketId"),
                    dateTaken = o.optLong("dateTaken"),
                    dateModified = o.optLong("dateModified"),
                    size = o.optLong("size")
                )
                val done = o.optBoolean("done")
                val failed = o.optBoolean("failed")
                var status = o.optString("status")
                if (!done && !failed) {
                    if (status == "转换中…") status = "待转换"
                } else if (status.contains("待原图删除后")) {
                    status = "完成：已导出到输出目录，可用「移到相机」移入相册"
                }
                list.add(ConvertItem(item = item, status = status, failed = failed, done = done))
            }
            list
        } catch (_: Exception) {
            null
        }
    }
}
