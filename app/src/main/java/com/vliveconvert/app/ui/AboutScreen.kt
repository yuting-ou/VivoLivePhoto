package com.vliveconvert.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vliveconvert.app.R

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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 许可证文本随包内置，读取一次性完成（约 35KB）
    val licenseText = remember {
        runCatching {
            context.resources.openRawResource(R.raw.license_gpl3)
                .bufferedReader().use { it.readText() }
        }.getOrDefault("（许可证文本读取失败，请访问 https://www.gnu.org/licenses/gpl-3.0.txt）")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "关于与开源许可",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                appName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "版本 $versionName · 全程本地处理，不联网、不上传任何数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Card {
                Text(
                    "本应用是 brovast/VLiveConvert 的修改版，依据 GNU 通用公共许可证第 3 版" +
                        "（GPL-3.0）分发。\n\n" +
                        "本程序是自由软件：您可以依据自由软件基金会发布的 GPL-3.0" +
                        "（或您选择的任何更新版本）的条款重新发布和/或修改它。\n\n" +
                        "本程序分发时希望它有用，但不提供任何担保，甚至不提供适销性或" +
                        "特定用途适用性的默示担保。详见 GNU 通用公共许可证。\n\n" +
                        "您应当已随本程序收到一份 GNU 通用公共许可证；" +
                        "若未收到，请访问 https://www.gnu.org/licenses/。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(12.dp))
            Card {
                Text("原始项目", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "https://github.com/brovast/VLiveConvert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Text("本修改版源码（依 GPL-3.0 提供）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "https://github.com/yuting-ou/VivoLivePhoto",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "GNU 通用公共许可证 第 3 版（完整文本）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                licenseText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp)
    ) { content() }
}
