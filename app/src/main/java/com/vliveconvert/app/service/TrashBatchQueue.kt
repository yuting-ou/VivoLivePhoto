package com.vliveconvert.app.service

import android.net.Uri

/**
 * 回收站删除/恢复的分批确认队列：一次系统事务只塞一批 URI，避免大批量整批事务失败。
 * 确认一批推进一批；取消即停，剩余项原样保留。已确认项单独记账，供部分成功时如实汇报。
 */
internal class TrashBatchQueue(
    all: List<Uri>,
    batchSize: Int = DEFAULT_BATCH_SIZE
) {
    private val batches: List<List<Uri>> = all.chunked(batchSize.coerceAtLeast(1))
    private var next = 0

    val total: Int = all.size
    val confirmed = ArrayList<Uri>()

    val hasMore: Boolean get() = next < batches.size

    /** 当前待确认批（仅在 [hasMore] 为 true 时调用） */
    fun peek(): List<Uri> = batches[next]

    /** 记录当前批已确认并推进，返回该批 */
    fun confirm(): List<Uri> {
        val batch = batches[next++]
        confirmed.addAll(batch)
        return batch
    }

    /** 取消后尚未确认的剩余项 */
    fun remaining(): List<Uri> = batches.drop(next).flatten()

    companion object {
        /** 初始策略参考上游真机实测；不是平台保证的上限，验证后可调 */
        const val DEFAULT_BATCH_SIZE = 150
    }
}