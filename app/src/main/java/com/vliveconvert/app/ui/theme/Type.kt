package com.vliveconvert.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 中文排版层级。
 *
 * 此前只覆写 bodyLarge，且其值与 Material 默认完全相同（等于没写）——界面各处
 * 直接吃默认值，标题与正文层级拉不开、中文行高偏紧。
 *
 * 本次按「中文界面」重定：标题加重（W700/W600）并略收紧字距（负值，大字更聚拢），
 * 正文字号回落到 14sp 级别、行高放宽到约 1.5 倍（中文字面率高，比西文需要更多行距），
 * 标签类统一 W500 + 极小正字距（避免小字号下字距过散）。
 *
 * 字族统一 SansSerif：各 OEM 中文无衬线（HarmonyOS Sans / MiSans 等）由系统映射，
 * 显式声明可避免 Default 与系统 UI 字体族不一致导致的混排观感差异。
 */
private val CjkFamily = FontFamily.SansSerif

val Typography = Typography(
    // 权限引导页大标题
    headlineSmall = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    // 顶栏 / 页面标题
    titleLarge = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp
    ),
    // 列表项文件名 / 区块标题 / 对话框标题
    titleMedium = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    // 正文与说明文字
    bodyMedium = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    // 状态行 / 次要说明（中文行高放宽到约 1.5 倍）
    bodySmall = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),
    // 主按钮
    labelLarge = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    // 标签 / 徽标
    labelMedium = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    // 辅助说明
    labelSmall = TextStyle(
        fontFamily = CjkFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp
    )
)
