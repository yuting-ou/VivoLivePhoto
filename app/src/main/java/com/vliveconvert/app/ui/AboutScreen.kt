package com.vliveconvert.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.R
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 关于 / 开源许可。
 *
 * GPL-3.0 合规：本项目以 GPL-3.0 分发二进制，据此应向接收者提供许可证文本、
 * 版权声明与源码获取方式。此前 APK 内没有任何许可入口（仅 GitHub 仓库页有），
 * 故新增本页——完整许可证文本随包内置（res/raw/license_gpl3.txt）。
 */
@Composable
fun AboutScreen(
    appName: String,
    versionName: String,
    onBack: () -> Unit,
    hazeState: HazeState = rememberGlassHazeState()
) {
    // 许可证全文约 35KB：读取放到 IO 线程，避免在组合（主线程）阶段做资源 I/O；
    // null = 读取中，读取失败回退为指向官方链接的提示。
    // 用 LocalResources（而非 LocalContext.current.resources）以正确处理配置变更
    val resources = LocalResources.current
    val licenseText by produceState<String?>(initialValue = null, resources) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resources.openRawResource(R.raw.license_gpl3)
                    .bufferedReader().use { it.readText() }
            }.getOrDefault("（许可证文本读取失败，请访问 https://www.gnu.org/licenses/gpl-3.0.txt）")
        }
    }
    // Haze 背景模糊（Apache-2.0）的完整许可文本随包内置；与 GPL 文本分开加载
    val apacheText by produceState<String?>(initialValue = null, resources) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resources.openRawResource(R.raw.license_apache2)
                    .bufferedReader().use { it.readText() }
            }.getOrDefault("（许可证文本读取失败，请访问 https://www.apache.org/licenses/LICENSE-2.0）")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .glassWallpaper()
            .glassBackdropSource(hazeState)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                size = 38.dp
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "关于与开源许可",
                style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                appName,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "版本 $versionName · 全程本地处理，不联网、不上传任何数据",
                style = glassSubtitleStyle()
            )

            Spacer(Modifier.height(14.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 24.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Text(
                    "本应用是 brovast/VLiveConvert 的修改版，依据 GNU 通用公共许可证第 3 版" +
                        "（GPL-3.0）分发。\n\n" +
                        "本程序是自由软件：您可以依据自由软件基金会发布的 GPL-3.0" +
                        "（或您选择的任何更新版本）的条款重新发布和/或修改它。\n\n" +
                        "本程序分发时希望它有用，但不提供任何担保，甚至不提供适销性或" +
                        "特定用途适用性的默示担保。详见 GNU 通用公共许可证。\n\n" +
                        "您应当已随本程序收到一份 GNU 通用公共许可证；" +
                        "若未收到，请访问 https://www.gnu.org/licenses/。",
                    style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(12.dp))
            val context = LocalContext.current
            fun openUrl(url: String) {
                // 外部浏览器打开（应用本体仍不联网）；无浏览器时静默忽略
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
            }
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 24.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Text("原始项目",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface))
                Spacer(Modifier.height(4.dp))
                LicenseLink("https://github.com/brovast/VLiveConvert") {
                    openUrl("https://github.com/brovast/VLiveConvert")
                }
                Spacer(Modifier.height(10.dp))
                GlassDivider()
                Spacer(Modifier.height(10.dp))
                Text("本修改版源码（依 GPL-3.0 提供）",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface))
                Spacer(Modifier.height(4.dp))
                LicenseLink("https://github.com/yuting-ou/VivoLivePhoto") {
                    openUrl("https://github.com/yuting-ou/VivoLivePhoto")
                }
                Spacer(Modifier.height(10.dp))
                GlassDivider()
                Spacer(Modifier.height(10.dp))
                Text("第三方组件",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface))
                Spacer(Modifier.height(4.dp))
                Text(
                    "背景模糊：Haze 1.6.10（chrisbanes）· Apache License 2.0",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                LicenseLink("https://github.com/chrisbanes/haze") {
                    openUrl("https://github.com/chrisbanes/haze")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "液态玻璃折射：Backdrop 1.0.2（Kyant0）· Apache License 2.0",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                LicenseLink("https://github.com/Kyant0/AndroidLiquidGlass") {
                    openUrl("https://github.com/Kyant0/AndroidLiquidGlass")
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "GNU 通用公共许可证 第 3 版（完整文本）",
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            )
            Spacer(Modifier.height(8.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 20.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Text(
                    licenseText ?: "正在读取许可证文本…",
                    style = TextStyle(fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Haze / Backdrop · Apache License 2.0（完整文本）",
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
            )
            Spacer(Modifier.height(8.dp))
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 20.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Text(
                    apacheText ?: "正在读取许可证文本…",
                    style = TextStyle(fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LicenseLink(url: String, onClick: () -> Unit) {
    Text(
        url,
        style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    )
}
