package com.vliveconvert.app.core

import java.io.InputStream
import java.security.MessageDigest

/**
 * 单文件写出结果的分段摘要（写后自检用）。
 * 每段记录 [offset, offset+length) 区间与写出到内存时的 MD5，
 * 校验时对最终落盘（或进入媒体库）的文件流式重算并逐段比对——
 * 段连续无缝且文件必须恰好结束，任何截断/损坏/多余字节都会被发现。
 */
internal class SegmentDigest(
    val name: String,
    val offset: Int,
    val length: Int,
    val md5Hex: String
)

/** 单文件写出结果：输出路径 + 分段摘要 */
internal class SingleWriteResult(val path: String, val segments: List<SegmentDigest>)

/**
 * 输出自检器：对导出产物做流式 MD5 逐段校验（O(1) 内存）。
 * 自检通过才允许标记「完成」/进入删除原图流程，防止静默损坏的照片
 * 在原图已被删除后才被发现（数据安全事故）。
 */
internal object OutputVerifier {

    /** 顺序读取整个流，逐段校验摘要；段间必须连续、结尾不得有多余字节 */
    fun verify(input: InputStream, segments: List<SegmentDigest>): Boolean {
        if (segments.isEmpty()) return false
        return try {
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(64 * 1024)
            for (seg in segments) {
                var remaining = seg.length
                while (remaining > 0) {
                    val want = if (remaining < buf.size) remaining else buf.size
                    val n = input.read(buf, 0, want)
                    if (n < 0) return false // 流提前结束（文件被截断）
                    md.update(buf, 0, n)
                    remaining -= n
                }
                if (!seg.md5Hex.equals(md.digest().toHexString(), ignoreCase = true)) {
                    return false // 该段内容与转换时不一致（损坏/写错）
                }
            }
            // 段全部读完且校验通过后，流必须恰好结束（无多余字节）
            input.read() == -1
        } catch (_: Exception) {
            false
        }
    }

    private fun ByteArray.toHexString(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** 计算字节数组的 MD5 十六进制串（写端生成分段摘要用） */
    fun md5Of(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).toHexString()
}
