package com.vliveconvert.app

import androidx.compose.ui.graphics.Color
import com.vliveconvert.app.ui.theme.DarkStatusColors
import com.vliveconvert.app.ui.theme.LightStatusColors
import com.vliveconvert.app.ui.theme.StatusColors
import com.vliveconvert.app.ui.theme.themeColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 对比度回归测试（WCAG 2.1 相对亮度公式，纯 JVM 可跑，无需真机）。
 *
 * 背景：列表项状态此前硬编码 `SuccessGreen = #2E7D32` / `WarningAmber = #B26A00`，
 * **不随主题变化**。实测在深色主题（行底色 #1A1B20）下：
 * - 成功态文字对比度仅 **3.35:1**（WCAG AA 正文要求 ≥ 4.5:1）
 * - 失败徽标用主题 error（深色下为 #FFB4AB 浅珊瑚）配硬编码白字，仅 **1.70:1**，几乎不可读
 *
 * 本测试把「状态色必须随主题变化且达标」变成可执行断言：任何一次调色若让对比度跌破
 * 阈值，测试直接失败——无需依赖真机肉眼检查。
 */
class StatusContrastTest {

    /** WCAG 相对亮度 */
    private fun luminance(c: Color): Double {
        fun chan(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * chan(c.red) + 0.7152 * chan(c.green) + 0.0722 * chan(c.blue)
    }

    /** WCAG 对比度（1..21） */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** 正文/小字号文字阈值 */
    private val TEXT_MIN = 4.5

    /** 图形元素（徽标填充 vs 行底色）阈值 */
    private val GRAPHIC_MIN = 3.0

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val r = contrast(fg, bg)
        assertTrue(
            "$name 对比度 %.2f:1 低于阈值 %.1f:1".format(r, min),
            r >= min
        )
    }

    /** 逐主题校验列表项状态文字与行底色的对比度 */
    private fun assertStatusTextOnRows(label: String, status: StatusColors, light: Boolean) {
        val scheme = themeColorScheme(light)
        val rowBg = scheme.surfaceContainerLow
        assertContrast("$label 成功态文字 / 行底色", status.success, rowBg, TEXT_MIN)
        assertContrast("$label 警告态文字 / 行底色", status.warning, rowBg, TEXT_MIN)
        assertContrast("$label 进行中文字 / 行底色", status.info, rowBg, TEXT_MIN)
        assertContrast("$label 待转换文字 / 行底色", scheme.onSurfaceVariant, rowBg, TEXT_MIN)
        // 丢位置行 / 失败行：底色分别为 tertiaryContainer / errorContainer
        assertContrast("$label 无位置文字 / 行底色",
            scheme.onTertiaryContainer, scheme.tertiaryContainer, TEXT_MIN)
        assertContrast("$label 失败文字 / 行底色",
            scheme.onErrorContainer, scheme.errorContainer, TEXT_MIN)
    }

    /** 逐主题校验徽标（填充 vs 符号、填充 vs 行底色） */
    private fun assertBadges(label: String, status: StatusColors, light: Boolean) {
        val scheme = themeColorScheme(light)
        val rowBg = scheme.surfaceContainerLow
        assertContrast("$label ✓ 符号 / 徽标填充", status.onSuccess, status.success, TEXT_MIN)
        assertContrast("$label ! 符号 / 徽标填充", status.onWarning, status.warning, TEXT_MIN)
        // 失败徽标：填充用主题 error，符号用 onError（此前硬编码白字，深色下仅 1.70:1）
        assertContrast("$label ✕ 符号 / 徽标填充", scheme.onError, scheme.error, TEXT_MIN)
        // 徽标本身要能从行底色上分辨出来
        assertContrast("$label ✓ 徽标填充 / 行底色", status.success, rowBg, GRAPHIC_MIN)
        assertContrast("$label ! 徽标填充 / 行底色", status.warning, rowBg, GRAPHIC_MIN)
        assertContrast("$label ✕ 徽标填充 / 行底色", scheme.error, rowBg, GRAPHIC_MIN)
    }

    @Test
    fun lightThemeStatusColorsMeetContrast() {
        assertStatusTextOnRows("浅色", LightStatusColors, light = true)
        assertBadges("浅色", LightStatusColors, light = true)
    }

    @Test
    fun darkThemeStatusColorsMeetContrast() {
        assertStatusTextOnRows("深色", DarkStatusColors, light = false)
        assertBadges("深色", DarkStatusColors, light = false)
    }

    /**
     * 状态色必须随主题变化：若两套主题用同一组固定色，
     * 必然在其中一套上不达标（正是修复前的状态）。
     */
    @Test
    fun statusColorsDifferBetweenThemes() {
        assertTrue("成功色应随主题变化", LightStatusColors.success != DarkStatusColors.success)
        assertTrue("警告色应随主题变化", LightStatusColors.warning != DarkStatusColors.warning)
        assertTrue("进行中色应随主题变化", LightStatusColors.info != DarkStatusColors.info)
        assertTrue("成功徽标符号色应随主题变化",
            LightStatusColors.onSuccess != DarkStatusColors.onSuccess)
        assertTrue("警告徽标符号色应随主题变化",
            LightStatusColors.onWarning != DarkStatusColors.onWarning)
    }

    /** 品牌蓝主色仍满足正文对比（顶栏/按钮文字用） */
    @Test
    fun brandPrimaryMeetsContrastOnSurfaces() {
        val light = themeColorScheme(light = true)
        val dark = themeColorScheme(light = false)
        assertContrast("浅色 primary / background",
            light.primary, light.background, TEXT_MIN)
        assertContrast("浅色 onPrimary / primary",
            light.onPrimary, light.primary, TEXT_MIN)
        assertContrast("深色 primary / background",
            dark.primary, dark.background, TEXT_MIN)
        assertContrast("深色 onPrimary / primary",
            dark.onPrimary, dark.primary, TEXT_MIN)
    }

    /**
     * 「重转」按钮：实色 tertiary 背景上的前景必须用配套的 onTertiary。
     *
     * 回归背景：该处原误用 onTertiaryContainer（那是配 tertiaryContainer 的前景），
     * 深色主题下 onTertiaryContainer(#FDD7FC) 叠在实色 tertiary(#E9BBE0) 上仅 1.29:1，
     * 文字几乎不可见；而当时的用例只校验了 onTertiaryContainer/tertiaryContainer
     * 这一正确配对，恰好漏掉它，测试给了虚假的安全感。
     */
    @Test
    fun solidTertiarySurfaceUsesMatchingOnTertiary() {
        val light = themeColorScheme(light = true)
        val dark = themeColorScheme(light = false)
        assertContrast("浅色 重转按钮文字 / 实色 tertiary",
            light.onTertiary, light.tertiary, TEXT_MIN)
        assertContrast("深色 重转按钮文字 / 实色 tertiary",
            dark.onTertiary, dark.tertiary, TEXT_MIN)
    }
}
