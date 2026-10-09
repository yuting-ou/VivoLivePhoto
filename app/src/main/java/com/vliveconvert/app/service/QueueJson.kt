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
            val o = JSONObject()
                .put("id", m.id).put("path", m.path).put("name", m.name)
                .put("bucketId", m.bucketId).put("dateTaken", m.dateTaken)
                .put("dateModified", m.dateModified).put("size", m.size)
                .put("status", ci.status)
                .put("done", ci.done).put("failed", ci.failed)
                .put("lostGps", ci.lostGps)
                .put("reconvert", ci.reconvert)
            ci.outUri?.let { o.put("outUri", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    /**
     * 从 JSON 恢复队列条目；损坏返回 null。
     * 中断时处于「转换中…」的条目重置为「待转换」；
     * 「待原图删除后落地」的中转条目降级为明确指引（原图可能还在，同名会冲突）。
     *
     * 兼容旧版本队列（无 lostGps/outUri 字段）：已完成的条目若状态文案含
     * 「无 GPS 位置数据」（v1.1.x 的逐项标注），推导 lostGps=true——
     * 老队列转换的丢位置照片也能进「重新转换」入口。
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
                    // 中断时处于执行中的条目重置为「待转换」（reconvert 标志已单独持久化，
                    // 恢复后续转仍按重转语义覆盖旧产物）
                    if (status == "转换中…" || status == "重新转换中…") status = "待转换"
                } else if (status.contains("待原图删除后")) {
                    status = "完成：已导出到输出目录，可用「移到相机」移入相册"
                }
                val lostGps = if (o.has("lostGps")) o.optBoolean("lostGps")
                              else done && status.contains("无 GPS 位置数据")
                list.add(ConvertItem(
                    item = item, status = status, failed = failed, done = done,
                    lostGps = lostGps,
                    outUri = if (o.has("outUri")) o.optString("outUri") else null,
                    reconvert = o.optBoolean("reconvert")
                ))
            }
            list
        } catch (_: Exception) {
            null
        }
    }
}
