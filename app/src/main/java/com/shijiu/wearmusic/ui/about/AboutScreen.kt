package com.shijiu.wearmusic.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary

/**
 * 关于页：应用信息、数据来源与免责声明。
 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    ScreenScaffold {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 44.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(NeteaseRed, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("♪", fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("WearMusic", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("腕上网易云音乐", fontSize = 11.sp, color = TextSecondary)
                    Text("版本 $version", fontSize = 10.sp, color = Color(0xFF9A9AA2))
                }
            }

            item { SectionTitle("关于本应用") }
            item {
                Text(
                    "WearMusic 是一款为 Wear OS 手表打造的第三方网易云音乐客户端，" +
                        "支持每日推荐、私人漫游、歌单收藏、云盘与逐字歌词，" +
                        "界面遵循 Material 3 Expressive 设计。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Color(0xFFDDDDDE),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            item { SectionTitle("数据来源") }
            item {
                Text(
                    "歌曲、歌词、封面等内容来自网易云音乐公开接口，数据仅供个人学习与研究使用。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Color(0xFFDDDDDE),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            item { SectionTitle("免责声明") }
            item {
                Text(
                    "本应用为非官方、非营利的个人学习项目，与网易云音乐官方无任何关联。" +
                        "音乐内容版权归网易云音乐及相应权利人所有，请支持正版音乐。",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Color(0xFF9A9AA2),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            item { SectionTitle("开发者") }
            item {
                Text("shijiu · © 2026", fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}
