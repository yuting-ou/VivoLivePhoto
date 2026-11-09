package com.vliveconvert.app.service

import android.net.Uri

/**
 * 回收站分批：一次系统事务只塞一批 URI（大批量整批事务可能失败，参考上游实测 150/批）。
 * jpg 与伴生 mp4 在收集/记录序列里**相邻成对**，分批必须保持成对——
 * 否则中途取消会留下「图已恢复、视频仍在回收站」的半对，实况暂时失效。
 * 注意：过滤失效项可能破坏相邻性（jpg/mp4 通常同生共死，罕见拆对时剩余项可再次恢复补齐）。
 */
internal fun chunkTrashBatches(
    uris: List<Uri>,
    batchUris: Int = DEFAULT_TRASH_BATCH
): List<List<Uri>> {
    if (uris.isEmpty()) return emptyList()
    val pairs = (uris.size + 1) / 2
    val perBatchPairs = (batchUris / 2).coerceAtLeast(1)
    return (0 until pairs).chunked(perBatchPairs).map { idxs ->
        idxs.flatMap { i ->
            listOfNotNull(uris.getOrNull(i * 2), uris.getOrNull(i * 2 + 1))
        }
    }
}

internal const val DEFAULT_TRASH_BATCH = 150
