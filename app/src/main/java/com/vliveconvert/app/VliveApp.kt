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

    /** 把全部崩溃日志导出到 Download/VLiveConvert（MediaStore 标准写入，无需权限） */
    fun exportToDownloads(context: Context): Int {
        val files = try {
            File(context.filesDir, DIR).listFiles()?.sortedBy { it.name } ?: return 0
        } catch (_: Exception) {
            return 0
        }
        if (files.isEmpty()) return 0
        val resolver = context.contentResolver
        var exported = 0
        for (f in files) {
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
                exported++
            } catch (_: Exception) {
                // 单个失败不影响其余导出
            }
        }
        return exported
    }
}
