package com.vliveconvert.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 屏幕切换转场合同：减少动效必须去掉全部位移/缩放，只留短交叉淡入淡出。 */
class ScreenTransitionSpecTest {

    @Test
    fun reduceMotionStripsAllSlideAndScale() {
        val fadeEnter = fadeIn(tween<Float>(120))
        val fadeExit = fadeOut(tween<Float>(120))
        for (returning in listOf(false, true)) {
            val t = screenContentTransform(
                reduceMotion = true, predictiveCommit = false, returning = returning)
            assertEquals("减少动效时进入必须只剩淡入", fadeEnter, t.targetContentEnter)
            assertEquals("减少动效时退出必须只剩淡出", fadeExit, t.initialContentExit)
            assertEquals("减少动效时容器尺寸不得动画（不裁剪）",
                false, t.sizeTransform?.clip)
        }
    }

    @Test
    fun predictiveCommitStaysInstant() {
        val t = screenContentTransform(
            reduceMotion = false, predictiveCommit = true, returning = false)
        assertEquals(EnterTransition.None, t.targetContentEnter)
        assertEquals(ExitTransition.None, t.initialContentExit)
    }

    @Test
    fun predictiveCommitBeatsReduceMotion() {
        // 手势提交优先：揭底层已在原地，减少动效下也立即换层而不是再淡一次
        val t = screenContentTransform(
            reduceMotion = true, predictiveCommit = true, returning = true)
        assertEquals(EnterTransition.None, t.targetContentEnter)
        assertEquals(ExitTransition.None, t.initialContentExit)
    }

    @Test
    fun normalEnterKeepsMotion() {
        val t = screenContentTransform(
            reduceMotion = false, predictiveCommit = false, returning = false)
        assertNotEquals("正常模式的进入转场不能退化成纯淡入",
            fadeIn(tween<Float>(120)), t.targetContentEnter)
    }

    @Test
    fun normalReturnKeepsMotion() {
        val t = screenContentTransform(
            reduceMotion = false, predictiveCommit = false, returning = true)
        assertNotEquals("正常模式的返回转场不能退化成纯淡入",
            fadeIn(tween<Float>(120)), t.targetContentEnter)
    }
}