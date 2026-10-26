package com.vliveconvert.app.service

/**
 * 进度通知的「预计剩余时间」口径（纯函数，可 JVM 单测）：
 * 至少完成 2 张才谈得上速率；剩余为 0 不显示；不足 1 分钟给专属文案，
 * 不出现「约 0 分钟 / 约 1 分钟」这种两头不讨好的读数。
 */
internal object ProgressEta {

    fun text(elapsedMs: Long, done: Int, total: Int): String? {
        val remaining = total - done
        if (done < 2 || remaining <= 0 || elapsedMs <= 0L) return null
        val remainingMs = elapsedMs / done * remaining
        return if (remainingMs < 60_000L) "预计剩余不到 1 分钟"
        else "预计剩余约 ${(remainingMs + 59_999) / 60_000} 分钟"
    }
}
