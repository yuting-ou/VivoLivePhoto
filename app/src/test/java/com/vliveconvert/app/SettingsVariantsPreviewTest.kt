package com.vliveconvert.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.ui.GlassCard
import com.vliveconvert.app.ui.GlassDivider
import com.vliveconvert.app.ui.GlassIconButton
import com.vliveconvert.app.ui.GlassIconTile
import com.vliveconvert.app.ui.glassWallpaper
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 设置页卡片式布局对照稿（只做视觉选型，不改正式页面） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1500dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVariantsPreviewTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { VLiveConvertTheme(darkTheme = false, dynamicColor = false) { content() } }
        shadowOf(Looper.getMainLooper()).idle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/ui-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val titleStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    private val subStyle = TextStyle(fontSize = 11.sp)
    private val groupStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

    @Composable
    private fun Header() {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", {}, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Text("设置", style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp))
        }
    }

    @Composable
    private fun Group(text: String) {
        Text(
            text, style = groupStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
    }

    @Composable
    private fun RowItem(icon: ImageVector, title: String, subtitle: String, switch: Boolean? = null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconTile(size = 34.dp) { Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = titleStyle)
                Text(subtitle, style = subStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (switch == null) Text("›", style = TextStyle(fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Switch(checked = switch, onCheckedChange = {})
        }
    }

    @Composable
    private fun Tile(icon: ImageVector, title: String, subtitle: String) {
        GlassCard(modifier = Modifier.fillMaxWidth(), radius = 20.dp, contentPadding = PaddingValues(14.dp)) {
            GlassIconTile(size = 34.dp) { Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(10.dp))
            Text(title, style = titleStyle)
            Text(subtitle, style = subStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }

    @Composable
    private fun StatusCard() {
        GlassCard(modifier = Modifier.fillMaxWidth(), radius = 24.dp, contentPadding = PaddingValues(16.dp)) {
            Text("已添加 3 张 · 待转换 0", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text("输出 DCIM/Camera · 删原图 关闭", style = subStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun sectionSpacer() = Unit

    @Composable
    private fun SettingsBody(scroll: Boolean = true) {
        Column(
            Modifier.fillMaxSize().glassWallpaper().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp)
        ) {
            Header()
            Column(Modifier.padding(top = 8.dp)) {
                StatusCard()
                Spacer(Modifier.height(18.dp))
                Group("输出")
                GlassCard(radius = 24.dp) { RowItem(Icons.Filled.Home, "输出目录", "Pictures/VLiveConvert") }
                Spacer(Modifier.height(18.dp))
                Group("转换后处理")
                GlassCard(radius = 24.dp) {
                    RowItem(Icons.AutoMirrored.Filled.ArrowForward, "移到相机相册", "删原图时可免序号", switch = true)
                    GlassDivider(startInset = 60.dp)
                    RowItem(Icons.Filled.Delete, "删除原图", "删除原 .jpg 与伴生 .mp4", switch = true)
                }
                Spacer(Modifier.height(18.dp))
                Group("外观")
                GlassCard(radius = 24.dp) {
                    RowItem(Icons.Filled.Star, "跟随系统取色", "用壁纸配色替换品牌蓝", switch = false)
                    GlassDivider(startInset = 60.dp)
                    RowItem(Icons.Filled.Star, "减少动效", "关闭玻璃高光与弹窗动画", switch = false)
                }
                Spacer(Modifier.height(18.dp))
                Group("工具")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(Icons.Filled.DateRange, "修复文件时间", "按拍摄时间校正修改时间")
                        Tile(Icons.Filled.Place, "找回位置", "检测缺位置的产物，选中重建")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(Icons.Filled.Build, "修复破损实况", "糊 JPG + MP4 合并成实况")
                        Tile(Icons.AutoMirrored.Filled.ArrowForward, "输出移到相机", "搬进 DCIM/Camera")
                    }
                }
                Spacer(Modifier.height(18.dp))
                Group("记录与诊断")
                GlassCard(radius = 24.dp) {
                    RowItem(Icons.Filled.List, "转换记录（3）", "最近 50 批的时间与结果")
                    GlassDivider(startInset = 60.dp)
                    RowItem(Icons.Filled.Share, "导出崩溃日志（0）", "导出供排查")
                    GlassDivider(startInset = 60.dp)
                    RowItem(Icons.Filled.Delete, "清空崩溃日志", "删除全部本地日志")
                }
                Spacer(Modifier.height(18.dp))
                Group("关于")
                GlassCard(radius = 24.dp) { RowItem(Icons.Filled.Info, "关于与开源许可", "版本信息与 GPL-3.0") }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    @Test
    fun shotVariantB() = shot("settings_vB_tiles") { SettingsBody() }

    @Composable
    private fun VariantC() {
        Column(Modifier.fillMaxSize().glassWallpaper().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Header()
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth(), radius = 24.dp, contentPadding = PaddingValues(vertical = 6.dp)) {
                Text("已添加 3 张 · 输出 DCIM/Camera", style = subStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                GlassDivider()
                CGroup("输出")
                RowItem(Icons.Filled.Home, "输出目录", "Pictures/VLiveConvert")
                GlassDivider(startInset = 60.dp)
                CGroup("转换后处理")
                RowItem(Icons.AutoMirrored.Filled.ArrowForward, "移到相机相册", "删原图时可免序号", switch = true)
                GlassDivider(startInset = 60.dp)
                RowItem(Icons.Filled.Delete, "删除原图", "删除原 .jpg 与伴生 .mp4", switch = true)
                GlassDivider(startInset = 60.dp)
                CGroup("外观")
                RowItem(Icons.Filled.Star, "跟随系统取色", "用壁纸配色替换品牌蓝", switch = false)
                GlassDivider(startInset = 60.dp)
                RowItem(Icons.Filled.Star, "减少动效", "关闭玻璃高光与弹窗动画", switch = false)
                GlassDivider(startInset = 60.dp)
                CGroup("工具")
                RowItem(Icons.Filled.DateRange, "修复文件时间", "按拍摄时间校正修改时间")
                GlassDivider(startInset = 60.dp)
                RowItem(Icons.Filled.Build, "修复破损实况", "糊 JPG + MP4 合并成实况")
                GlassDivider(startInset = 60.dp)
                RowItem(Icons.Filled.Place, "找回位置", "检测缺位置的产物，选中重建")
                GlassDivider(startInset = 60.dp)
                RowItem(Icons.AutoMirrored.Filled.ArrowForward, "输出移到相机", "搬进 DCIM/Camera")
                GlassDivider(startInset = 60.dp)
                CGroup("记录与诊断")
                RowItem(Icons.Filled.List, "转换记录（3）", "最近 50 批的时间与结果")
                GlassDivider(startInset = 60.dp)
                RowItem(Icons.Filled.Share, "导出崩溃日志（0）", "导出供排查")
                GlassDivider(startInset = 60.dp)
                RowItem(Icons.Filled.Delete, "清空崩溃日志", "删除全部本地日志")
                GlassDivider(startInset = 60.dp)
                CGroup("关于")
                RowItem(Icons.Filled.Info, "关于与开源许可", "版本信息与 GPL-3.0")
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    @Composable
    private fun CGroup(text: String) {
        Text(
            text, style = groupStyle,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp)
        )
    }

    @Test
    fun shotVariantC() = shot("settings_vC_singlecard") { VariantC() }
}
