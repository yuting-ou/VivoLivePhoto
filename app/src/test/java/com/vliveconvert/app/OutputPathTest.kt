package com.vliveconvert.app

import com.vliveconvert.app.core.outputPathErrorMessage
import com.vliveconvert.app.core.sanitizeOutputRelPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 输出目录表单的纯校验：实时提示与提交清洗必须同源 */
class OutputPathTest {

    @Test
    fun normalizesSlashesAndTrims() {
        assertEquals("Pictures/VLiveConvert", sanitizeOutputRelPath("  Pictures/VLiveConvert  "))
        assertEquals("Pictures/VLiveConvert", sanitizeOutputRelPath("Pictures\\VLiveConvert"))
        assertEquals("Pictures/VLiveConvert", sanitizeOutputRelPath("/Pictures/VLiveConvert/"))
    }

    @Test
    fun rejectsTraversalEmptySegmentsAndIllegalChars() {
        listOf(
            "..", "Pictures/../DCIM", "Pictures//Camera", "Pictures/./Camera",
            "Pictures/Cam:era", "Pictures/Cam*era", "Pictures/Cam?era",
            "Pictures/Cam\"era", "Pictures/Cam<era", "Pictures/Cam>era",
            "Pictures/Cam|era"
        ).forEach { assertNull("应拒绝：$it", sanitizeOutputRelPath(it)) }
    }

    @Test
    fun liveErrorIsSilentForBlankDefault() {
        assertNull(outputPathErrorMessage(""))
        assertNull(outputPathErrorMessage("   "))
    }

    @Test
    fun liveErrorMatchesSubmitValidation() {
        assertNull(outputPathErrorMessage("Pictures/VLiveConvert"))
        assertEquals(
            "路径无效：不能包含 \\ : * ? \" < > | 或 ..",
            outputPathErrorMessage("Pictures/../DCIM")
        )
    }
}
