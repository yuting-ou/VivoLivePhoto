package com.vliveconvert.app.service

import android.content.Context

/**
 * 转换性能配置：自动挡（按设备堆大小决定，行为与 v2.7 完全一致，默认）与
 * 手动挡（用户逐项可调：并发/内存上限/扫描线程/缩略图线程）。
 * 无论哪挡，内存预算闸与 OOM 自愈始终生效——手动挡只是把「准入上限」交给用户，
 * 拧过头也不会撑爆堆。
 */
internal data class PerfSettings(
    /** false=自动挡（默认），true=手动挡 */
    val manual: Boolean = false,
    /** 手动挡：同时转换张数 1..8 */
    val concurrency: Int = 2,
    /** 手动挡：内存预算 128..堆的 70%（MB） */
    val memoryLimitMb: Int = defaultMemoryMb(),
    /** 手动挡：相册扫描线程 1..8 */
    val scanThreads: Int = 4,
    /** 手动挡：缩略图加载并发 1..8 */
    val thumbThreads: Int = 4,
) {
    companion object {
        private const val MB = 1024L * 1024L

        fun maxHeapMb(): Int = (Runtime.getRuntime().maxMemory() / MB).toInt().coerceAtLeast(128)

        fun memoryUpperMb(): Int = (maxHeapMb() * 0.70f).toInt().coerceAtLeast(128)

        fun defaultMemoryMb(): Int = (maxHeapMb() * 0.45f).toInt().coerceIn(128, memoryUpperMb())

        /** 并发上限随 CPU 核心数走：至少 2（单核设备罕见），至多 8 */
        fun concurrencyUpper(cores: Int): Int = cores.coerceIn(2, 8)
    }
}

/** 一键预设：只填充手动挡的四个值，不改变自动/手动开关 */
internal enum class PerfPreset(val label: String) {
    Saver("省电"),
    Balanced("均衡"),
    Performance("性能");

    fun apply(): PerfSettings = when (this) {
        Saver -> PerfSettings(
            manual = true, concurrency = 1,
            memoryLimitMb = (PerfSettings.maxHeapMb() * 0.30f).toInt()
                .coerceIn(128, PerfSettings.memoryUpperMb()),
            scanThreads = 2, thumbThreads = 2)
        Balanced -> PerfSettings(
            manual = true, concurrency = 2,
            memoryLimitMb = PerfSettings.defaultMemoryMb(),
            scanThreads = 4, thumbThreads = 4)
        Performance -> PerfSettings(
            manual = true, concurrency = 4,
            memoryLimitMb = (PerfSettings.maxHeapMb() * 0.55f).toInt()
                .coerceIn(128, PerfSettings.memoryUpperMb()),
            scanThreads = 6, thumbThreads = 6)
    }
}

/** 自动挡 = v2.7 行为：大堆 2 路、小堆 1 路 */
internal fun autoConcurrency(maxMemory: Long): Int =
    if (maxMemory >= 384L * 1024 * 1024) 2 else 1

internal fun PerfSettings.effectiveConcurrency(maxMemory: Long, cores: Int): Int =
    if (!manual) autoConcurrency(maxMemory)
    else concurrency.coerceIn(1, PerfSettings.concurrencyUpper(cores))

internal fun PerfSettings.effectiveBudgetBytes(maxMemory: Long): Long {
    val mb = 1024L * 1024L
    return if (!manual) defaultBudgetBytes(maxMemory)
    else memoryLimitMb.coerceIn(128, PerfSettings.memoryUpperMb()).toLong() * mb
}

internal fun PerfSettings.effectiveScanThreads(): Int = if (!manual) 4 else scanThreads.coerceIn(1, 8)

internal fun PerfSettings.effectiveThumbThreads(): Int = if (!manual) 4 else thumbThreads.coerceIn(1, 8)

/** SharedPreferences 持久化（键带 perf_ 前缀，损坏值一律夹回合法区间） */
internal class PerfStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("vliveconvert", Context.MODE_PRIVATE)

    fun load(): PerfSettings = PerfSettings(
        manual = prefs.getBoolean("perf_manual", false),
        concurrency = prefs.getInt("perf_concurrency", 2).coerceIn(1, 8),
        memoryLimitMb = prefs.getInt("perf_memory_mb", PerfSettings.defaultMemoryMb())
            .coerceIn(128, PerfSettings.memoryUpperMb()),
        scanThreads = prefs.getInt("perf_scan_threads", 4).coerceIn(1, 8),
        thumbThreads = prefs.getInt("perf_thumb_threads", 4).coerceIn(1, 8)
    )

    fun save(s: PerfSettings) {
        prefs.edit()
            .putBoolean("perf_manual", s.manual)
            .putInt("perf_concurrency", s.concurrency.coerceIn(1, 8))
            .putInt("perf_memory_mb", s.memoryLimitMb.coerceIn(128, PerfSettings.memoryUpperMb()))
            .putInt("perf_scan_threads", s.scanThreads.coerceIn(1, 8))
            .putInt("perf_thumb_threads", s.thumbThreads.coerceIn(1, 8))
            .apply()
    }
}