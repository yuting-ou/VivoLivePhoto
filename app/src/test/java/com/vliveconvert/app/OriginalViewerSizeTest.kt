package com.vliveconvert.app

import androidx.compose.ui.unit.IntSize
import com.vliveconvert.app.picker.computeOriginalDecodeSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** 查看器解码尺寸：长边封顶、保持比例、小图不放大。 */
class OriginalViewerSizeTest {

    @Test
    fun capsLandscapeLongSide() {
        assertEquals(IntSize(2048, 1536), computeOriginalDecodeSize(4000, 3000, 2048))
    }

    @Test
    fun capsPortraitLongSide() {
        assertEquals(IntSize(1536, 2048), computeOriginalDecodeSize(3000, 4000, 2048))
    }

    @Test
    fun neverUpscalesSmallImages() {
        assertEquals(IntSize(1000, 800), computeOriginalDecodeSize(1000, 800, 2048))
    }

    @Test
    fun invalidDimensionsDoNotCrash() {
        assertEquals(IntSize(1, 1), computeOriginalDecodeSize(0, 800, 2048))
    }
}
