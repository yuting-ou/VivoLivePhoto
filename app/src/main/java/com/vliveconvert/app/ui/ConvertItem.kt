package com.vliveconvert.app.ui

import com.vliveconvert.app.picker.MediaItem

/** 转换列表中的一项（源照片 + 当前状态）。独立文件：纯数据类，队列 JSON 序列化与
 *  JVM 单测直接引用，避免加载同文件的 Compose 依赖。 */
data class ConvertItem(
    val item: MediaItem,
    val status: String = "待转换",
    val failed: Boolean = false,
    val done: Boolean = false,
    /** 转换产物无 GPS 位置（多为读取层被系统脱敏）：驱动「重新转换找回位置」入口 */
    val lostGps: Boolean = false,
    /** 上次导出的 MediaStore URI（重新转换时原地覆盖旧产物，避免重名加序号） */
    val outUri: String? = null,
    /** 本项为「重新转换」（找回位置）：跳过删除原图收集，输出覆盖旧产物 */
    val reconvert: Boolean = false,
)
