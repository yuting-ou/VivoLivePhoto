package com.vliveconvert.app.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一次转换批次的留存摘要（「设置 → 转换记录」的行数据） */
data class BatchLogEntry(
    val timeMs: Long,
    val ok: Int,
    val fail: Int,
    val noGps: Int,
    val reconvert: Boolean,
    val deletedOriginals: Int,
    val movedToCamera: Int,
    val dest: String
)

/**
 * 批次日志（最新在前，上限 50 条）：用户清空列表 10 秒撤销窗过后唯一能回答
 * 「上周转的那批在哪、成没成功」的凭据。定位是辅助记忆——文件损坏一律静默
 * 空表，绝不因此影响主流程；原子写与并发纪律照抄 ConvertCenter.persistQueue。
 */
internal object BatchLog {

    private const val FILE = "batch_log.json"
    private const val CAP = 50
    private val lock = Any()

    fun append(context: Context, e: BatchLogEntry) {
        synchronized(lock) {
            try {
                val list = load(context) + e
                val trimmed = list.sortedByDescending { it.timeMs }.take(CAP)
                val f = File(context.filesDir, FILE)
                val tmp = File(context.filesDir, "$FILE.tmp")
                tmp.writeText(toJson(trimmed))
                if (!tmp.renameTo(f)) {
                    f.writeText(toJson(trimmed))
                    tmp.delete()
                }
            } catch (_: Exception) {}
        }
    }

    fun load(context: Context): List<BatchLogEntry> {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) emptyList()
            else fromJson(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE).delete() } catch (_: Exception) {}
    }

    private fun toJson(list: List<BatchLogEntry>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("t", e.timeMs).put("ok", e.ok).put("fail", e.fail)
                    .put("gps", e.noGps).put("rc", e.reconvert)
                    .put("del", e.deletedOriginals).put("mov", e.movedToCamera)
                    .put("dest", e.dest)
            )
        }
        return arr.toString()
    }

    /** 损坏返回空表（调用方无需判空处理） */
    private fun fromJson(text: String): List<BatchLogEntry> {
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                BatchLogEntry(
                    timeMs = o.optLong("t"),
                    ok = o.optInt("ok"),
                    fail = o.optInt("fail"),
                    noGps = o.optInt("gps"),
                    reconvert = o.optBoolean("rc"),
                    deletedOriginals = o.optInt("del"),
                    movedToCamera = o.optInt("mov"),
                    dest = o.optString("dest")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
