package com.vliveconvert.app.core

/**
 * 输出目录是 MediaStore 相对路径。空串表示恢复默认，由调用方处理；
 * 返回 null 表示路径非法。
 */
fun sanitizeOutputRelPath(input: String): String? {
    val s = input.trim().replace('\\', '/').trim('/')
    if (s.isEmpty() || s.contains("..")) return null
    if (s.split('/').any { it.isEmpty() || it == "." }) return null
    if (Regex("""[:*?"<>|]""").containsMatchIn(s)) return null
    return s
}

/** 实时校验用；空白输入视为「恢复默认」，不算错误 */
fun outputPathErrorMessage(input: String): String? {
    if (input.isBlank()) return null
    return if (sanitizeOutputRelPath(input) == null) {
        "路径无效：不能包含 \\ : * ? \" < > | 或 .."
    } else null
}
