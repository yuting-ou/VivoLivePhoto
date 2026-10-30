package com.vliveconvert.app

import com.vliveconvert.app.service.ConvertService
import com.vliveconvert.app.service.convertProgressWeight
import com.vliveconvert.app.service.finalizeMergesIntoBar
import com.vliveconvert.app.service.finalizeProgressValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾（删原图 → 以原名移入相机相册）并入总进度条的进度算术。
 *
 * 修的体验缺陷：收尾曾游离在进度条之外——转换一结束进度条就到 100%，
 * 用户还要对着满格进度条干等删原图/移文件几十秒，不知道还剩什么。
 * 并入后转换段只占 0~0.9，收尾段逐项推进 0.9 → 1.0，
 * 「没到 100% = 还有事没做完」重新成立。
 */
class FinalizeProgressTest {

    @Test
    fun mergeDecisionTruthTable() {
        assertTrue("有延迟项且已授权所有文件访问 → 收尾并入进度条",
            finalizeMergesIntoBar(deferredCount = 3, allFilesAccess = true))
        assertTrue("没有延迟落地项就没有收尾段可并入",
            !finalizeMergesIntoBar(deferredCount = 0, allFilesAccess = true))
        assertTrue("未授权所有文件访问：收尾发生在回收站确认后，进度条管不到",
            !finalizeMergesIntoBar(deferredCount = 3, allFilesAccess = false))
    }

    @Test
    fun convertPhaseWeight() {
        assertEquals("收尾并入时转换段只占 0.9",
            ConvertService.FINALIZE_PROGRESS_START, convertProgressWeight(true), 1e-6f)
        assertEquals("不并入时转换即全部工作（原行为不变）",
            1f, convertProgressWeight(false), 1e-6f)
    }

    @Test
    fun finalizePhaseAdvancesMonotonicallyToFull() {
        val n = 5
        var prev = -1f
        for (f in 0..n) {
            val p = finalizeProgressValue(f, n)
            assertTrue("收尾进度必须单调不减", p >= prev)
            prev = p
        }
        assertEquals("收尾起点 = 转换段终点，不回退不跳跃",
            ConvertService.FINALIZE_PROGRESS_START, finalizeProgressValue(0, n), 1e-6f)
        assertEquals("最后一项完成后恰好 100%",
            1f, finalizeProgressValue(n, n), 1e-6f)
    }
}
