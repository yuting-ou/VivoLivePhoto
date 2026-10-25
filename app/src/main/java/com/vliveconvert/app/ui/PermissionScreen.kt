package com.vliveconvert.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vliveconvert.app.R
import dev.chrisbanes.haze.HazeState

/**
 * 权限引导界面（未授予媒体权限时显示）。
 */
@Composable
fun PermissionScreen(
    onRequest: () -> Unit,
    statusText: String,
    /** 应用内品牌名（统一取自 R.string.app_display_name） */
    appName: String,
    hazeState: HazeState = rememberGlassHazeState()
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .glassWallpaper()
            .glassBackdropSource(hazeState)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val viewportHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = 26.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    appName,
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.7).sp,
                        color = MaterialTheme.colorScheme.onBackground)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.app_tagline),
                    style = TextStyle(fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(Modifier.height(26.dp))

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    radius = 26.dp,
                    contentPadding = PaddingValues(18.dp)
                ) {
                    Text(
                        "本应用需要读取您设备上的照片和视频：",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(Modifier.height(12.dp))
                    PermissionLine(
                        "照片权限",
                        "扫描设备相册，识别其中的双文件实况照片"
                    )
                    Spacer(Modifier.height(10.dp))
                    PermissionLine(
                        "视频权限",
                        "查找双文件实况附带的伴生视频"
                    )
                    Spacer(Modifier.height(10.dp))
                    PermissionLine(
                        "位置权限",
                        "强烈建议授予——缺失时系统会在读取照片时剥离 GPS 位置信息，" +
                            "转换后的照片将丢失地点信息且无法找回"
                    )
                    Spacer(Modifier.height(14.dp))
                    GlassDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "转换全程字节级无损，不重编码，不会上传任何数据。",
                        style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Spacer(Modifier.height(26.dp))
                GlossyButton("授予权限", onRequest)

                if (statusText.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        statusText,
                        style = TextStyle(fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** 一条权限说明：主题色小标签 + 说明文字 */
@Composable
private fun PermissionLine(label: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            body,
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}
