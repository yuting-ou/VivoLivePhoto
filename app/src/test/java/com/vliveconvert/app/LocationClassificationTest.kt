package com.vliveconvert.app

import com.vliveconvert.app.convert.LocationHealth
import com.vliveconvert.app.convert.LocationHealth.Classification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定矩阵（[LocationHealth.classify]）的全组合回归：
 * 「需要修复」的定义 = **相册里不显示位置**（不是「文件无 EXIF」、不是「列空」）。
 * 矩阵每一条规则独立钉死——任何一条被改动（比如把 UNKNOWN 改成全列 REPAIR_NEEDED，
 * 即 test27「100 张全列出」的病根）都会被对应断言抓住。
 */
class LocationClassificationTest {

    // ── 规则 1：文件读失败 → UNREADABLE（读失败 ≠ 无坐标，不许混进任何结论）──
    @Test
    fun unreadableFileIsNeverConflatedWithAnything() {
        assertEquals(Classification.UNREADABLE,
            LocationHealth.classify(true, fileHasExifGps = true, hasFallbackCoords = true,
                columnsReadable = true, rowHasCoords = true))
        assertEquals(Classification.UNREADABLE,
            LocationHealth.classify(true, fileHasExifGps = false, hasFallbackCoords = false,
                columnsReadable = false, rowHasCoords = null))
    }

    // ── 规则 2：文件 EXIF + 行坐标在位 → OK（默认不列出，只计数）──
    @Test
    fun exifAndRowCoordsIsOk() {
        assertEquals(Classification.OK,
            LocationHealth.classify(false, true, false, columnsReadable = true, rowHasCoords = true))
    }

    // ── 规则 3：文件 EXIF + 行坐标缺失 → REPAIR_NEEDED（文件有坐标但相册不显示）──
    @Test
    fun exifButRowMissingNeedsRepair() {
        assertEquals(Classification.REPAIR_NEEDED,
            LocationHealth.classify(false, true, false, columnsReadable = true, rowHasCoords = false))
    }

    // ── 规则 4：文件 EXIF + 列可读但单行读失败 → UNKNOWN（不许当 REPAIR_NEEDED）──
    @Test
    fun exirRowReadFailureIsUnknown() {
        assertEquals(Classification.UNKNOWN,
            LocationHealth.classify(false, true, false, columnsReadable = true, rowHasCoords = null))
    }

    // ── 规则 5：文件 EXIF + 列不可读 → UNKNOWN（大概率正常，不许全列成需修复）──
    @Test
    fun exifWithOpaqueColumnsIsUnknownNotRepair() {
        assertEquals(Classification.UNKNOWN,
            LocationHealth.classify(false, true, false, columnsReadable = false, rowHasCoords = null))
        // 行坐标传什么都不影响：列不可读时行信号不可信
        assertEquals(Classification.UNKNOWN,
            LocationHealth.classify(false, true, false, columnsReadable = false, rowHasCoords = true))
        assertEquals(Classification.UNKNOWN,
            LocationHealth.classify(false, true, false, columnsReadable = false, rowHasCoords = false))
    }

    // ── 规则 6：文件无 EXIF + 有备用坐标 → REPAIR_NEEDED（扫描会抹行坐标；可注入+重建）──
    @Test
    fun noExifWithFallbackAlwaysNeedsRepair() {
        // 列可读、行坐标在位也照样：文件无 EXIF 是硬伤（重扫即丢），修复动作相同
        assertEquals(Classification.REPAIR_NEEDED,
            LocationHealth.classify(false, false, true, columnsReadable = true, rowHasCoords = true))
        assertEquals(Classification.REPAIR_NEEDED,
            LocationHealth.classify(false, false, true, columnsReadable = true, rowHasCoords = false))
        assertEquals(Classification.REPAIR_NEEDED,
            LocationHealth.classify(false, false, true, columnsReadable = false, rowHasCoords = null))
    }

    // ── 规则 7：文件无 EXIF + 无备用坐标 → NO_LOCATION（源无坐标，修不了）──
    @Test
    fun noExifNoFallbackIsNoLocation() {
        assertEquals(Classification.NO_LOCATION,
            LocationHealth.classify(false, false, false, columnsReadable = true, rowHasCoords = false))
        assertEquals(Classification.NO_LOCATION,
            LocationHealth.classify(false, false, false, columnsReadable = false, rowHasCoords = null))
    }

    /** 判定依据必须与分类一一对应且非空（UI 与诊断报告的可解释性） */
    @Test
    fun reasonsAreNonBlankForEachClassification() {
        val combos = listOf(
            listOf(true, true, true, true, true),
            listOf(false, true, false, true, true),
            listOf(false, true, false, true, false),
            listOf(false, true, false, true, null as Boolean?),
            listOf(false, true, false, false, null),
            listOf(false, false, true, true, false),
            listOf(false, false, true, false, null),
            listOf(false, false, false, true, false),
            listOf(false, false, false, false, null)
        )
        val seen = mutableSetOf<Classification>()
        for (c in combos) {
            val cls = LocationHealth.classify(
                c[0] as Boolean, c[1] as Boolean, c[2] as Boolean,
                c[3] as Boolean, c[4] as Boolean?)
            seen.add(cls)
            val reason = LocationHealth.classificationReason(
                cls, c[1] as Boolean, c[2] as Boolean, c[3] as Boolean, c[4] as Boolean?)
            assertTrue("分类 ${cls.label} 的判定依据不能为空", reason.isNotBlank())
        }
        assertEquals("组合必须覆盖全部五种分类", 5, seen.size)
    }

    /** 分类标签用户可读（UI 直接展示） */
    @Test
    fun labelsAreUserFacing() {
        assertEquals("需修复", Classification.REPAIR_NEEDED.label)
        assertEquals("已正常", Classification.OK.label)
        assertEquals("待确认", Classification.UNKNOWN.label)
        assertEquals("无位置", Classification.NO_LOCATION.label)
        assertEquals("读取失败", Classification.UNREADABLE.label)
    }
}
