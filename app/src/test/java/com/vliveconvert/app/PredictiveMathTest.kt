package com.vliveconvert.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预测性返回的手势变换数学。
 *
 * 结构对齐官方 platform-samples（decelerate 插值、跟手、边随位移、双层相向运动），
 * 幅度比规范原版大——原版参数（缩 0.9、移 1/20）在两个全屏页面之间逐帧渲染验收时
 * 几乎读不出来（用户原话：不好看）。本版：顶层缩到 0.88 + 上浮 + 尾段 25% 淡出；
 * 底层从 0.90 缩放、1/16 反向位移、65% 透明度迎上来。
 * 钉端点、单调性、淡出区间与两层**反向**位移；手感看截图帧。
 */
class PredictiveMathTest {

    @Test
    fun outgoingEndpointsAndMonotonicity() {
        val at0 = predictiveOutgoing(0f)
        val atHalf = predictiveOutgoing(0.5f)
        val at1 = predictiveOutgoing(1f)

        assertEquals("进度 0 时顶层必须是原样", 1f, at0.scale, 1e-6f)
        assertEquals("进度 0 无位移", 0f, at0.shiftFraction, 1e-6f)
        assertEquals("进度 0 不透明", 1f, at0.alpha, 1e-6f)
        assertEquals("结束时缩放到 0.88", 0.88f, at1.scale, 1e-4f)
        assertEquals("结束时位移 = 窗宽 1/14", 1f / 14f, at1.shiftFraction, 1e-4f)
        assertEquals("收尾淡出：提交瞬间顶层必须已全透明（换层无消失感）", 0f, at1.alpha, 1e-6f)
        assertEquals("淡出只发生在尾段：进度 75% 前不得开始", 1f, predictiveOutgoing(0.7f).alpha, 1e-6f)

        // decelerate（缓出）：前半程走得比后半程远
        assertTrue("缓出插值应让前半程位移过半", atHalf.shiftFraction > 0.5f / 14f)
        assertTrue("顶层缩放应随进度单调递减",
            at0.scale > atHalf.scale && atHalf.scale > at1.scale)
        assertTrue("顶层透明度应随进度单调递减（只在尾段）",
            at0.alpha >= atHalf.alpha && atHalf.alpha > at1.alpha)
    }

    @Test
    fun incomingRisesTowardIdentity() {
        val at0 = predictiveIncoming(0f)
        val atHalf = predictiveIncoming(0.5f)
        val at1 = predictiveIncoming(1f)

        assertTrue("揭底层初始应处于缩小状态（有'迎上来'的余地）", at0.scale < 1f)
        assertTrue("揭底层初始应半透明（材质化的入场感）", at0.alpha < 1f)
        assertEquals("提交瞬间揭底层必须到位（否则换层会跳）", 1f, at1.scale, 1e-4f)
        assertEquals("提交瞬间揭底层位移必须归零", 0f, at1.shiftFraction, 1e-6f)
        assertEquals("提交瞬间揭底层必须完全不透明", 1f, at1.alpha, 1e-4f)
        assertTrue("揭底层缩放应随进度单调递增",
            at0.scale < atHalf.scale && atHalf.scale < at1.scale)
        assertTrue("揭底层位移应随进度单调归零",
            at0.shiftFraction > atHalf.shiftFraction && atHalf.shiftFraction > at1.shiftFraction)
        assertTrue("揭底层透明度应随进度单调递增",
            at0.alpha < atHalf.alpha && atHalf.alpha < at1.alpha)
    }

    @Test
    fun outOfRangeProgressIsClamped() {
        // 系统偶尔会在提交边界外补事件：负值与 >1 都必须钳制，不许把页面拉爆
        assertEquals(predictiveOutgoing(0f).scale, predictiveOutgoing(-0.3f).scale, 1e-6f)
        assertEquals(predictiveOutgoing(1f).scale, predictiveOutgoing(1.7f).scale, 1e-4f)
        assertEquals(0f, predictiveOutgoing(1.7f).alpha, 1e-6f)
        assertEquals(1f, predictiveIncoming(2f).alpha, 1e-4f)
        assertEquals(predictiveIncoming(1f).scale, predictiveIncoming(2f).scale, 1e-4f)
    }
}
