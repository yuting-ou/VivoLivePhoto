package com.vliveconvert.app.ui

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle

/**
 * 苹果连续曲率圆角（G2 连续 squircle）。
 *
 * 标准 [androidx.compose.foundation.shape.RoundedCornerShape] 是「直线 + 四分之一圆弧」，
 * 在切点处曲率从 0 突变到 1/r（G1 不连续），大圆角下边缘会显得「硬」。
 * 这里用 kyant/shapes 的连续曲率构造（Apache-2.0，见 com/kyant/shapes/LICENSE）：
 * 曲率沿角平滑过渡，与 iOS 图标/卡片同一套几何。全 UI 的圆角矩形统一走此入口，
 * 圆形元素（徽章/复选框/圆钮）仍用 CircleShape。
 */
internal fun continuousShape(radius: Dp): Shape =
    RoundedRectangle(cornerRadius = radius, style = RoundedCornerStyle.Continuous)
