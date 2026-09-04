package com.vliveconvert.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vliveconvert.app.ui.ConvertItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

/**
 * 转换中心：进程级共享的转换状态（Compose 可观察）+ 队列落盘。
 *
 * 状态放在单例而非 Activity，使转换批次在 Activity 重建（旋转/分屏/深色切换）与
 * 退后台时保持存活——实际转换执行在 [ConvertService]（前台服务）中，
 * Activity 只是状态的观察者与操作的发起者。
 */
object ConvertCenter {

    /** 转换队列（含每项状态），UI 与服务共同读写（写均发生在主线程） */
    val items = mutableStateListOf<ConvertItem>()

    var isConverting by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var progressDetail by mutableStateOf("")
    /** 应用级状态行（权限提示 / 转换进度 / 完成摘要共用） */
    var statusText by mutableStateOf("")

    /**
     * 批次结束后待 Activity 发起「移入回收站」确认的原图 URI
     * （未授予所有文件访问权限时的删除路径；系统弹窗只能由 Activity 拉起）。
     * 仅存内存：进程被杀时这些 URI 丢失，原图保持原样（宁可漏删不可误删）。
     */
    val pendingTrashUris: MutableList<Uri> =
        Collections.synchronizedList(mutableListOf<Uri>())

    /**
     * 待确认删除的原图数（pendingTrashUris 快照）。
     * 独立成 Compose 状态：批次结束、用户仍停留前台时（onStart 不会再次触发），
     * Activity 通过观察此值变化拉起回收站确认弹窗。
     */
    var pendingTrashCount by mutableIntStateOf(0)

    /**
     * 待落地条目：「移到相机相册 + 转换后删除原图」同时开启时，
     * 转换产物先导出到输出目录（中转），待原图删除确认完成后
     * 以原名 move 进 DCIM/Camera——原图先删腾名，避免 MediaStore 自动加 "(1)" 序号
     * （安全语义不变：原图删除发生在新文件完整落盘并自检通过之后）。
     */
    class FinalizeEntry(val uri: Uri, val originalName: String, val itemKey: String)

    val pendingFinalize: MutableList<FinalizeEntry> =
        Collections.synchronizedList(mutableListOf())

    private const val QUEUE_FILE = "convert_queue.json"

    /** 开始转换：持久化队列并启动前台服务执行批次 */
    fun start(context: Context): Boolean {
        if (isConverting) return false
        val targets = items.filter { !it.done && !it.failed }
        if (targets.isEmpty()) {
            statusText = "没有待转换的照片"
            return false
        }
        persistQueue(context)
        isConverting = true
        progress = 0f
        progressDetail = "已处理 0/${targets.size}"
        context.startForegroundService(
            Intent(context, ConvertService::class.java).setAction(ConvertService.ACTION_START))
        return true
    }

    /** 按源文件 key 替换列表项（主线程调用） */
    fun replaceItem(old: ConvertItem, new: ConvertItem) {
        val idx = items.indexOfFirst { it.item.key == old.item.key }
        if (idx >= 0) items[idx] = new
    }

    // ---------- 队列落盘（进程被杀后可恢复列表） ----------

    fun persistQueue(context: Context) {
        try {
            val arr = JSONArray()
            for (ci in items) {
                val m = ci.item
                arr.put(JSONObject()
                    .put("id", m.id).put("path", m.path).put("name", m.name)
                    .put("bucketId", m.bucketId).put("dateTaken", m.dateTaken)
                    .put("dateModified", m.dateModified).put("size", m.size)
                    .put("status", ci.status)
                    .put("done", ci.done).put("failed", ci.failed))
            }
            File(context.filesDir, QUEUE_FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    /**
     * 从磁盘恢复上次未完成的队列（仅在当前队列为空时加载）。
     * 上次中断时处于「转换中…」的项重置为待转换。
     * @return 恢复后仍未完成的条数（0 表示无需恢复）
     */
    fun loadQueue(context: Context): Int {
        if (items.isNotEmpty()) return 0
        val f = File(context.filesDir, QUEUE_FILE)
        if (!f.exists()) return 0
        return try {
            val arr = JSONArray(f.readText())
            var pending = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val item = com.vliveconvert.app.picker.MediaItem(
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
                    pending++
                    if (status == "转换中…") status = "待转换"
                } else if (status.contains("待原图删除后")) {
                    // 进程中断导致落地未完成：文件已在中转目录（输出目录），
                    // 可用顶栏「移到相机」手动移入（与原图同名时会带序号）
                    status = "完成：已导出到输出目录，可用「移到相机」移入相册"
                }
                items.add(ConvertItem(item = item, status = status, failed = failed, done = done))
            }
            pending
        } catch (_: Exception) {
            0
        }
    }

    /** 清空队列文件（用户「清空」或全部完成后调用） */
    fun clearQueue(context: Context) {
        try { File(context.filesDir, QUEUE_FILE).delete() } catch (_: Exception) {}
    }
}
