package com.vliveconvert.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VliveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * 本地崩溃日志：未捕获异常先落盘再交还系统默认处理（应用仍按原流程终止）。
 * 不联网、不上传——用户可在主界面「日志」入口把崩溃日志导出到 Download/VLiveConvert
 * 交给开发者定位，替代「闪退了但没人知道为什么」的盲修状态。
 */
internal object CrashLog {

    private const val DIR = "crash"
    private const val KEEP = 10 // 只保留最近 10 份
    private const val PREFS = "vliveconvert"
    private const val KEY_EXPORTED = "crash_exported_names"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(app.filesDir, DIR).apply { mkdirs() }
                val version = try {
                    val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                    "${pi.versionName}(${pi.longVersionCode})"
                } catch (_: Exception) {
                    "?"
                }
                val head = buildString {
                    append("app: ").append(app.packageName).append(" v").append(version)
                        .append('\n')
                    append("time: ")
                        .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        .append('\n')
                    append("thread: ").append(thread.name).append('\n')
                }
                File(dir, "crash_${System.currentTimeMillis()}.txt")
                    .writeText(head + Log.getStackTraceString(throwable))
                dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach {
                    try { it.delete() } catch (_: Exception) {}
                }
            } catch (_: Throwable) {
                // 记录失败不影响原崩溃流程
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun count(context: Context): Int = try {
        File(context.filesDir, DIR).listFiles()?.size ?: 0
    } catch (_: Exception) {
        0
    }

    /** 一次导出的结果：exported=本次新导出份数；already=此前已导出、本次跳过份数 */
    data class CrashExport(val exported: Int, val already: Int, val hasAnyLog: Boolean)

    /**
     * 把崩溃日志导出到 Download/VLiveConvert（MediaStore 标准写入，无需权限）。
     *
     * **按文件名去重**：MediaStore 对同名 DISPLAY_NAME 会自动追加 "(1)" "(2)" 序号，
     * 用户连点 N 次就会攒出几十份重复文件（v1.7.22 及之前正是如此）。
     * 已导出的名字记在 SharedPreferences，第二次点只导新出现的崩溃。
     */
    fun exportToDownloads(context: Context): CrashExport {
        val files = try {
            File(context.filesDir, DIR).listFiles()?.sortedBy { it.name }
                ?: return CrashExport(0, 0, false)
        } catch (_: Exception) {
            return CrashExport(0, 0, false)
        }
        if (files.isEmpty()) return CrashExport(0, 0, false)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = (prefs.getStringSet(KEY_EXPORTED, emptySet()) ?: emptySet()).toMutableSet()
        val resolver = context.contentResolver
        // 预填设备上已存在的同名文件（v1.7.22 及之前连点攒下的几十份重复）：
        // 升级后第一次点也不再复制。查不到（影子环境/ROM 差异）就退回仅靠本地记录
        try {
            resolver.query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?)",
                arrayOf("Download/VLiveConvert/", "Download/VLiveConvert"),
                null
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) done.add(c.getString(nameIdx))
            }
        } catch (_: Exception) {
        }
        var exported = 0
        var already = 0
        for (f in files) {
            if (f.name in done) { already++; continue }
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VLiveConvert")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: continue
                resolver.openOutputStream(uri, "w")?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                } ?: continue
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null)
                done.add(f.name)
                exported++
            } catch (_: Exception) {
                // 单个失败不影响其余导出
            }
        }
        if (exported > 0) {
            prefs.edit().putStringSet(KEY_EXPORTED, done).apply()
        }
        return CrashExport(exported, already, true)
    }
}
