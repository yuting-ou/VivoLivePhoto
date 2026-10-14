package com.vliveconvert.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预测性返回的决策真值表。
 *
 * 这里钉的是一条会出人命的约束：**「返回关哪一层」与「渲染显示哪一层」必须共享同一份优先级**。
 * pickScreen() 决定渲染（about > fixTime > picker > settings），
 * backDecision() 决定返回时弹掉哪一层 —— 两边一旦分叉，就是 v1.7.16 那个
 * 「第一次返回没反应、第二次直接跳两级」的变体。
 *
 * 所以对全部 16 种标志组合断言：只要 backDecision 给出决策，
 * 它揭示的层就必须等于 pickScreen 在「弹掉之后」的那个渲染结果。
 */
class BackDecisionTest {

    @Test
    fun revealMatchesPickScreen_afterPoppingTheTopLayer() {
        for (a in listOf(false, true)) for (f in listOf(false, true))
            for (p in listOf(false, true)) for (s in listOf(false, true)) {
                val d = backDecision(a, f, p, s)
                if (d == null) {
                    // 没有层可弹时不该有决策；渲染侧此时应停在主页
                    assertEquals("flags(a=$a,f=$f,p=$p,s=$s)", Screen.Main, pickScreen(true, a, f, p, s))
                    continue
                }
                // 揭示层是「弹掉之后」的渲染结果：先把决策对应的那层清掉再比
                val cleared = when (d) {
                    BackDecision.CLOSE_ABOUT -> listOf(false, f, p, s)
                    BackDecision.CLOSE_FIXTIME -> listOf(a, false, p, s)
                    BackDecision.CLOSE_PICKER -> listOf(a, f, false, s)
                    BackDecision.CLOSE_SETTINGS -> listOf(a, f, p, false)
                }
                assertEquals(
                    "flags(a=$a,f=$f,p=$p,s=$s): 弹层后揭示的页必须等于 pickScreen 的渲染页",
                    pickScreen(true, cleared[0], cleared[1], cleared[2], cleared[3]),
                    backRevealScreen(a, f, p, s)
                )
            }
    }

    @Test
    fun decisionPicksTheSameLayerPickScreenRenders() {
        // 返回决策的优先级与渲染优先级是同一份：决策选中的层 == 当前渲染的层
        for (a in listOf(false, true)) for (f in listOf(false, true))
            for (p in listOf(false, true)) for (s in listOf(false, true)) {
                val rendered = pickScreen(true, a, f, p, s)
                val d = backDecision(a, f, p, s)
                if (rendered == Screen.Main) {
                    assertNull("flags(a=$a,f=$f,p=$p,s=$s)", d)
                } else {
                    assertEquals(
                        "flags(a=$a,f=$f,p=$p,s=$s): 决策要弹的必须是渲染中的那层",
                        rendered,
                        when (d) {
                            BackDecision.CLOSE_ABOUT -> Screen.About
                            BackDecision.CLOSE_FIXTIME -> Screen.FixTime
                            BackDecision.CLOSE_PICKER -> Screen.Picker
                            BackDecision.CLOSE_SETTINGS -> Screen.Settings
                            null -> Screen.Main
                        }
                    )
                }
            }
    }

    @Test
    fun priorityIsAboutOverFixTimeOverPickerOverSettings() {
        assertEquals(BackDecision.CLOSE_ABOUT, backDecision(true, true, true, true))
        assertEquals(BackDecision.CLOSE_FIXTIME, backDecision(false, true, true, true))
        assertEquals(BackDecision.CLOSE_PICKER, backDecision(false, false, true, true))
        assertEquals(BackDecision.CLOSE_SETTINGS, backDecision(false, false, false, true))
        assertNull("全关时不得拦截返回，应让系统做跨任务预测动画", backDecision(false, false, false, false))
    }

    @Test
    fun nestedPagesRevealTheirParent() {
        assertEquals(Screen.Settings, backRevealScreen(true, false, false, true))
        assertEquals(Screen.Settings, backRevealScreen(false, true, false, true))
        assertEquals(Screen.Main, backRevealScreen(false, false, true, false))
        assertEquals(Screen.Main, backRevealScreen(false, false, false, true))
    }
}
