package com.vliveconvert.app.ui

import com.vliveconvert.app.picker.MediaItem

/** 转换列表中的一项（源照片 + 当前状态）。独立文件：纯数据类，队列 JSON 序列化与
 *  JVM 单测直接引用，避免加载同文件的 Compose 依赖。 */
data class ConvertItem(
    val item: MediaItem,
    val status: String = "待转换",
    val failed: Boolean = false,
    val done: Boolean = false,
)
