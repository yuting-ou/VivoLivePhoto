package com.vliveconvert.app

import com.vliveconvert.app.service.ConvertService
import com.vliveconvert.app.service.StartDecision
import com.vliveconvert.app.service.onStartIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 服务收到 START intent 时的决策真值表。
 *
 * 修的缺陷：批次「可见转换」结束（isConverting=false，界面可点「开始转换」）到
 * 「收尾清理」结束（running=false）之间有一段可达数十秒的窗口——删原图、逐个移文件。
 * 这个窗口里的新 START 曾被 `!running` 守卫**直接吞掉**：用户点了、界面转圈、
 * 批次永远不来。现在排队（QUEUE），收尾后补跑。
 */
class StartIntentTest {

    @Test
    fun decisionTruthTable() {
        assertEquals(StartDecision.RUN_NOW, onStartIntent(ConvertService.ACTION_START, false))
        assertEquals(
            "收尾窗口里的 START 必须排队，绝不许吞",
            StartDecision.QUEUE, onStartIntent(ConvertService.ACTION_START, true))
        assertEquals(StartDecision.IGNORE, onStartIntent(null, false))
        assertEquals(StartDecision.IGNORE, onStartIntent(null, true))
        assertEquals(StartDecision.IGNORE, onStartIntent("other.action", false))
    }
}
