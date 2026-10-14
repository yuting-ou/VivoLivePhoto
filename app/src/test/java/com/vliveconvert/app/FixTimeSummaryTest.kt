package com.vliveconvert.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 修复时间收尾汇总的真值表：半途警告必须聚合一次说清，不许逐项覆盖只留最后一条 */
class FixTimeSummaryTest {

    @Test
    fun normalCaseHasNoSuffix() {
        assertEquals("修复完成：成功 3 项，跳过 0 项", fixTimeSummary(3, 0, emptyList(), emptyList()))
    }

    @Test
    fun skipExplainsReason() {
        assertEquals(
            "修复完成：成功 1 项，跳过 2 项（文件名中无时间信息或不可写）",
            fixTimeSummary(1, 2, emptyList(), emptyList()))
    }

    @Test
    fun mtimeWarnAggregatedWithNames() {
        val s = fixTimeSummary(1, 0, listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg"), emptyList())
        assertTrue(s.contains("4 项媒体库时间已更新但物理 mtime 未生效："))
        assertTrue("点名最多 3 个，多了以「等」收尾", s.contains("a.jpg、b.jpg、c.jpg 等"))
        assertTrue("不列全名单", !s.contains("d.jpg"))
    }

    @Test
    fun mediaFailExplainsPhysicalTimeUncertainty() {
        val s = fixTimeSummary(0, 1, emptyList(), listOf("x.jpg"))
        assertTrue(s.contains("1 项媒体库更新失败（物理时间可能已改）：x.jpg"))
    }
}
