package com.vliveconvert.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.vliveconvert.app.picker.DOUBLE_TAP_SCALE
import com.vliveconvert.app.picker.doubleTapZoomTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双击缩放的锚点：手指点下的那个内容点，缩放前后必须停在同一屏幕位置。
 * 屏幕位置 = 容器中心 + (内容点-容器中心)×scale + offset，故 offset = (中心-手指)×(scale-1)。
 */
class DoubleTapZoomAnchorTest {

    private val container = IntSize(1080, 2400)
    private val center = Offset(540f, 1200f)

    private fun screenOf(point: Offset, scale: Float, offset: Offset) =
        center + (point - center) * scale + offset

    @Test
    fun tappedPointStaysUnderFinger() {
        val tap = Offset(300f, 900f)
        val image = IntSize(1536, 2048)
        val offset = doubleTapZoomTarget(tap, container, image)
        val moved = screenOf(tap, DOUBLE_TAP_SCALE, offset)
        assertEquals("锚点 X 必须留在手指下", tap.x, moved.x, 0.5f)
        assertEquals("锚点 Y 必须留在手指下", tap.y, moved.y, 0.5f)
    }

    @Test
    fun offsetIsClampedToImageEdges() {
        // 竖图在 2.5× 时高度仍不足屏高 ⇒ 纵向不许平移，只能保持居中
        val landscape = IntSize(2048, 1536)
        val offset = doubleTapZoomTarget(Offset(120f, 300f), container, landscape)
        assertEquals("放不下整屏的轴不许偏移", 0f, offset.y, 0.5f)
        assertTrue(offset.x >= 0f)
    }

    @Test
    fun degenerateImageSizeFallsBackToCentered() {
        val offset = doubleTapZoomTarget(Offset(300f, 900f), container, IntSize.Zero)
        assertEquals(0f, offset.x, 0.5f)
        assertEquals(0f, offset.y, 0.5f)
    }
}
