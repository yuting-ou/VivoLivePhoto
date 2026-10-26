package com.vliveconvert.app

import com.vliveconvert.app.service.ProgressEta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进度通知「预计剩余时间」的口径：至少完成 2 张才有速率可言；
 * 剩余为 0 不显示；不足 1 分钟用「不到 1 分钟」而不是「约 0/1 分钟」。
 */
class ProgressEtaTest {

    @Test
    fun `done 少于 2 张不显示`() {
        assertNull(ProgressEta.text(elapsedMs = 500L, done = 1, total = 4))
        assertNull(ProgressEta.text(elapsedMs = 0L, done = 2, total = 4))
    }

    @Test
    fun `全部完成时不显示`() {
        assertNull(ProgressEta.text(elapsedMs = 600_000L, done = 2, total = 2))
    }

    @Test
    fun `按速率向上取整到分钟`() {
        assertEquals("预计剩余约 2 分钟", ProgressEta.text(elapsedMs = 120_000L, done = 2, total = 4))
        assertEquals("预计剩余约 3 分钟", ProgressEta.text(elapsedMs = 90_000L, done = 3, total = 9))
    }

    @Test
    fun `不足一分钟用专属文案`() {
        assertEquals("预计剩余不到 1 分钟", ProgressEta.text(elapsedMs = 40_000L, done = 2, total = 3))
    }
}
