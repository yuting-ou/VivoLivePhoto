package com.vliveconvert.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vliveconvert.app.service.GlassQuality
import com.vliveconvert.app.ui.LocalGlassQuality
import com.vliveconvert.app.ui.LocalGlassScrollPaused
import com.vliveconvert.app.ui.glassOptics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 玻璃光学成本系数（画质档 × 滚动降成本）的纯组合逻辑合同。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassOpticsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun optics(q: GlassQuality, paused: Boolean): Triple<Float, Float, Boolean> {
        var result: Triple<Float, Float, Boolean>? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalGlassQuality provides q,
                LocalGlassScrollPaused provides paused
            ) { result = glassOptics() }
        }
        compose.waitForIdle()
        return result!!
    }

    @Test
    fun standardUnpausedIsFullCostZeroRegression() {
        assertEquals(Triple(1f, 1f, true), optics(GlassQuality.Standard, paused = false))
    }

    @Test
    fun saverDropsLensEntirelyButKeepsBlur() {
        // 去折射保模糊：lens=0（渲染层据此整体跳过 lens），blur 保留降半径
        assertEquals(Triple(0.6f, 0f, false), optics(GlassQuality.Saver, paused = false))
    }

    @Test
    fun scrollPauseScalesCostWithoutDroppingGlass() {
        // 滚动降成本：模糊/折射降档、色散关，但都不归零——玻璃观感不丢
        assertEquals(Triple(0.5f, 0.8f, false), optics(GlassQuality.Standard, paused = true))
    }

    @Test
    fun saverPausedStacksMultiplicatively() {
        // 流畅档 × 滚动暂停：两个系数相乘，折射仍整体关闭
        assertEquals(Triple(0.3f, 0f, false), optics(GlassQuality.Saver, paused = true))
    }

    @Test
    fun noProviderDefaultsToFullCost() {
        // 未提供档位/滚动信号的页面（如启动页）必须等价于标准档不滚动
        var result: Triple<Float, Float, Boolean>? = null
        compose.setContent { result = glassOptics() }
        compose.waitForIdle()
        assertEquals(Triple(1f, 1f, true), result)
    }
}