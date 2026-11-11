package com.vliveconvert.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 冷启动交棒的内容入场缩放：减少动效时不得有缩放（淡入保留）。 */
class ColdStartRevealTest {

    @Test
    fun normalProgressScalesFrom985() {
        assertEquals(0.985f, revealScale(0f, reduceMotion = false), 1e-6f)
        assertEquals(1f, revealScale(1f, reduceMotion = false), 1e-6f)
    }

    @Test
    fun reduceMotionStaysFullScale() {
        assertEquals(1f, revealScale(0f, reduceMotion = true), 1e-6f)
        assertEquals(1f, revealScale(0.5f, reduceMotion = true), 1e-6f)
    }
}