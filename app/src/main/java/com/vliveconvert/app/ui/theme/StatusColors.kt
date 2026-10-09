package com.vliveconvert.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 状态语义色（成功 / 警告 / 进行中）。
 *
 * 为什么不直接用固定色：列表项状态此前硬编码了 `SuccessGreen = #2E7D32` 与
 * `WarningAmber = #B26A00`，**不随主题变化**。实测在深色主题下行底色为 #1A1B20 时：
 * - 成功态文字 #2E7D32 对比度仅 **3.35:1**（WCAG AA 正文要求 ≥ 4.5:1）
 * - 失败徽标在深色下用主题 error（#FFB4AB 浅珊瑚）配白字，仅 **1.70:1**，几乎不可读
 *
 * 故每个语义色都给出浅色/深色两套，并各自配套一个 `onXxx`（徽标内符号色），
 * 保证「状态文字 vs 行底色」与「符号 vs 徽标填充」两个组合都达标。
 * 所有配色均通过 `StatusContrastTest` 以 WCAG 公式计算校验。
 */
data class StatusColors(
    /** 成功态文字 / 图标（成功行、已完成徽标填充） */
    val success: Color,
    /** 成功徽标内的符号色 */
    val onSuccess: Color,
    /** 警告态（丢位置）文字 / 徽标填充 */
    val warning: Color,
    /** 警告徽标内的符号色 */
    val onWarning: Color,
    /** 进行中态文字（转换中） */
    val info: Color
)

/** 浅色主题：深色调，保证在浅色行底上有足够对比 */
val LightStatusColors = StatusColors(
    success = Color(0xFF1E6B2E),
    onSuccess = Color(0xFFFFFFFF),
    warning = Color(0xFF8A5300),
    onWarning = Color(0xFFFFFFFF),
    info = Color(0xFF3651E0)
)

/** 深色主题：亮色调，保证在深色行底上可读 */
val DarkStatusColors = StatusColors(
    success = Color(0xFF86E29A),
    onSuccess = Color(0xFF0A2E13),
    warning = Color(0xFFFFD08A),
    onWarning = Color(0xFF3A2500),
    info = Color(0xFFBAC3FF)
)

internal val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** 当前主题下的状态语义色 */
val statusColors: StatusColors
    @Composable @ReadOnlyComposable
    get() = LocalStatusColors.current
