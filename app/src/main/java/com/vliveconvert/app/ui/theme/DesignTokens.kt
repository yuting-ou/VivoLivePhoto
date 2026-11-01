package com.vliveconvert.app.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token（v2.6.0-test28 起收敛）：把液态玻璃视觉体系里**已经成立**的规格
 * 提取成显式常量——值全部来自既有页面的实际使用（选择器/主界面/弹窗），
 * 不引入新规格；新增/重做的页面一律引用这里，不再各自硬编码。
 *
 * 现状缺口（既有页面仍硬编码、未接线的地方，见 README test28 变更记录）：
 * - GlassDesign.kt 内部的玻璃材质参数（模糊半径/边缘渐变/阴影）仍是组件私有，
 *   属于「玻璃引擎」内部实现，不强制外提；
 * - PickerScreen/FixTimeScreen/BrokenRepairScreen 的既有字面量未替换为 token
 *   （避免无差别大改；它们的值与 token 一致，后续触碰时逐处接线）。
 */
object DesignTokens {

    // ── 栅格与边距 ──
    /** 页面水平栅格（标题行、内容卡片外缘；选择器同款） */
    val ScreenHMargin = 16.dp
    /** 内容卡片圆角（GlassCard 默认） */
    val CardRadius = 24.dp
    /** 浮动 Dock 圆角（HazeGlassCard） */
    val DockRadius = 28.dp
    /** Dock 内边距 */
    val DockPadding = 10.dp
    /** 子页面标题行：statusBarsPadding 后的上/下边距 */
    val TitleTop = 8.dp
    val TitleBottom = 4.dp

    // ── 照片网格（选择器语言） ──
    /** 网格列数 */
    const val GridColumns = 3
    /** 网格间距 */
    val GridSpacing = 2.dp
    /** 格子圆角（未选） */
    val CellRadius = 12.dp
    /** 格子圆角（选中，配 0.86 中心缩放） */
    val CellRadiusSelected = 16.dp
    /** 选中缩放（几何中心为定点） */
    const val CellScaleSelected = 0.86f
    /** 选中边框宽度 */
    val CellBorderSelected = 2.dp

    // ── 分段头 ──
    val SectionStart = 8.dp
    val SectionTop = 16.dp
    val SectionBottom = 8.dp

    // ── 角标（格子右下角状态角标，选择器「已添加/已转」同款） ──
    val CornerBadgeRadius = 6.dp
    val CornerBadgeHPadding = 5.dp
    val CornerBadgeVPadding = 2.dp
    val CornerBadgeFontSize = 10.sp
    val CornerBadgeLineHeight = 12.sp

    // ── 勾选徽章（格子右上角圆） ──
    val CheckBadgeSize = 22.dp

    // ── 字号阶梯 ──
    /** 子页面标题（找回位置/修复时间/破损修复） */
    val TitleSubpage = 28.sp
    val TitleSubpageTracking = (-0.5).sp
    /** 分段头主标题 */
    val SectionTitle = 16.sp
    /** 分段头副文本 / 工具行辅助文本 */
    val SectionSub = 12.sp
    /** 工具行主文本 */
    val ToolRowText = 13.sp
}
