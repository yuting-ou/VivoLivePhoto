package com.vliveconvert.app

import com.vliveconvert.app.ui.glassSurfaceAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转场收浓：veil=0 时稳态值一个都不许动；抬到 1 时收浓到几乎不透，
 * 否则上一页会从玻璃底下透出来闪一下（用户报障，120fps 录屏逐帧分析确认
 * 静止态无变化、变化全在进出的 ~250ms 转场里）。
 */
class GlassTransitionVeilTest {

    @Test
    fun steadyStateAlphasAreUnchanged() {
        assertEquals(0.52f, glassSurfaceAlpha(false, false, false, 0f), 0.001f)
        assertEquals(0.66f, glassSurfaceAlpha(true, false, false, 0f), 0.001f)
        assertEquals(0.46f, glassSurfaceAlpha(false, false, true, 0f), 0.001f)
        assertEquals(0.78f, glassSurfaceAlpha(false, true, false, 0f), 0.001f)
        assertEquals(0.78f, glassSurfaceAlpha(true, true, false, 0f), 0.001f)
    }

    @Test
    fun fullVeilIsNearlyOpaque() {
        for (dark in listOf(false, true)) for (liquid in listOf(false, true)) {
            val a = glassSurfaceAlpha(dark, false, liquid, 1f)
            assertTrue("转场中必须收浓（dark=$dark liquid=$liquid 当前 $a）", a >= 0.92f)
        }
    }

    @Test
    fun veilRisesMonotonicallyAndNeverBelowSteady() {
        for (dark in listOf(false, true)) {
            val steady = glassSurfaceAlpha(dark, false, true, 0f)
            var prev = steady
            for (veil in listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.5f)) {
                val a = glassSurfaceAlpha(dark, false, true, veil)
                assertTrue("veil=$veil 不得低于稳态", a >= steady - 0.0001f)
                assertTrue("veil=$veil 必须单调变浓", a >= prev - 0.0001f)
                prev = a
            }
        }
    }
}
