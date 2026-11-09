package com.vliveconvert.app

import com.vliveconvert.app.convert.LocationHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断报告默认脱敏：只报坐标有无，不报精确值；主动选择后才带坐标。 */
class ProbeRedactionTest {

    private val probe = LocationHealth.Probe(
        exif = 22.8389 to 108.1780,
        xmp = null,
        video = 22.5 to 108.0
    )

    @Test
    fun redactedEvidenceHidesExactCoordinates() {
        val text = probe.evidence(redact = true)
        assertTrue(text.contains("EXIF=有（已脱敏）"))
        assertTrue(text.contains("XMP=无"))
        assertTrue(text.contains("视频段=有（已脱敏）"))
        assertFalse("脱敏后不得出现精确坐标", text.contains("22.8389"))
        assertFalse(text.contains("108.1780"))
    }

    @Test
    fun fullEvidenceKeepsCoordinates() {
        val text = probe.evidence(redact = false)
        assertTrue(text.contains("22.8389"))
        assertTrue(text.contains("108.1780"))
    }

    @Test
    fun defaultEvidenceIsFullForOnScreenDisplay() {
        // 无参调用（本机界面展示）保持完整坐标；脱敏只作用于主动导出的报告
        assertTrue(probe.evidence().contains("22.8389"))
    }
}