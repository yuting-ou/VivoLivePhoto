package com.vliveconvert.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vliveconvert.app.ui.continuousShape
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连续曲率圆角的几何回归：squircle 的轮廓必须与标准 RoundedCornerShape 不同
 * （证明替换真的生效，不是空操作），且是落在边界内的有效闭合路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContinuousShapeTest {

    private fun outlinePath(shape: androidx.compose.ui.graphics.Shape): Path {
        val outline = shape.createOutline(
            Size(200f, 200f), LayoutDirection.Ltr, Density(1f))
        val path = Path()
        when (outline) {
            is androidx.compose.ui.graphics.Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is androidx.compose.ui.graphics.Outline.Generic -> path.addPath(outline.path)
            is androidx.compose.ui.graphics.Outline.Rectangle -> path.addRect(outline.rect)
        }
        return path
    }

    @Test
    fun squircleDiffersFromCircularCorner() {
        val squircle = outlinePath(continuousShape(48.dp)).asAndroidPath()
        val circular = outlinePath(RoundedCornerShape(48.dp)).asAndroidPath()
        val lenS = android.graphics.PathMeasure(squircle, false).length
        val lenC = android.graphics.PathMeasure(circular, false).length
        // 连续曲率角部的弧长与四分之一圆弧不同（切点附近两者重合，故用总长判别）
        assertNotEquals("squircle 轮廓总长应与圆弧不同", lenS, lenC, 1.0f)
    }

    @Test
    fun squircleOutlineStaysWithinBounds() {
        val path = outlinePath(continuousShape(48.dp)).asAndroidPath()
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        assertTrue("轮廓不得越界", bounds.left >= -0.5f && bounds.top >= -0.5f &&
            bounds.right <= 200.5f && bounds.bottom <= 200.5f)
        assertTrue("轮廓应接近满幅", bounds.width() > 199f && bounds.height() > 199f)
    }
}