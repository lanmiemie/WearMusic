package com.shijiu.wearmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import coil.compose.AsyncImage
import com.shijiu.wearmusic.data.UiResult

/** 品牌色：网易云红。 */
val NeteaseRed = Color(0xFFE8383F)
val CardBg = Color(0xFF26262B)
val TextSecondary = Color(0xFFB9B9C0)

/** 选中 / 未选中两种 Button 配色（用于 wear Button 的 colors 参数）。 */
@Composable
fun buttonColors(selected: Boolean) =
    if (selected) {
        ButtonDefaults.buttonColors(
            containerColor = NeteaseRed,
            contentColor = Color.White
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = CardBg,
            contentColor = Color.White
        )
    }

/**
 * 选中 / 未选中两种 Chip 配色。
 * Material 3 移除了 Chip 组件，所有胶囊形操作统一由 Button 承担，
 * 保留此函数让既有调用点无缝过渡（返回 m3 ButtonColors）。
 */
@Composable
fun chipColors(selected: Boolean) = buttonColors(selected)

/** 页面脚手架：顶部时间 + 全屏内容。 */
@Composable
fun ScreenScaffold(
    showTimeText: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (showTimeText) {
            TimeText(modifier = Modifier.align(Alignment.TopCenter))
        }
        content()
    }
}

/** 通用加载态。 */
@Composable
fun LoadingBox(text: String = "加载中…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, fontSize = 12.sp, color = TextSecondary)
        }
    }
}

/** 通用错误 / 空态。 */
@Composable
fun ErrorBox(
    message: String,
    needLogin: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onLogin: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 18.dp)
        ) {
            Text(
                message,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = Color(0xFFDDDDDE)
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) {
                    Button(onClick = onRetry, modifier = Modifier.height(36.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重试", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("重试", fontSize = 12.sp)
                        }}
                }
                if (needLogin && onLogin != null) {
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.height(36.dp),
                        colors = buttonColors(selected = true)
                    ) {
                        Text("去登录", fontSize = 12.sp)
                    }
                }
            }
            if (extra != null) {
                Spacer(Modifier.height(10.dp))
                extra()
            }
        }
    }
}

/** 通用空态。 */
@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Color(0xFF9A9AA2), textAlign = TextAlign.Center)
    }
}

/** 列表区块标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )
}

/**
 * 封面图：网易云 CDN 专用（Coil ImageLoader 已注入 Referer）。
 * 自动追加 CDN 缩放参数（?param=WxH），避免列表小图下载原尺寸大图造成卡顿。
 */
@Composable
fun CoverImage(
    url: String?,
    size: Dp,
    corner: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(CardBg),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = cdnScaledUrl(url, size),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("♪", color = Color(0xFF6E6E76), fontSize = 16.sp)
        }
    }
}

/** 网易云 CDN 支持按像素缩放；给目标尺寸（2 倍屏密余量）生成缩放 URL。 */
private fun cdnScaledUrl(url: String, size: Dp): String {
    if (!url.contains("music.126.net")) return url
    if (url.contains("param=")) return url
    val px = (size.value * 2).toInt().coerceIn(60, 1024)
    val sep = if (url.contains('?')) '&' else '?'
    return "$url${sep}param=${px}y$px"
}

/**
 * 一次性拉取页面数据的通用容器：Loading / Error(可重试、可引导登录) / 内容。
 */
@Composable
fun <T> LoadScreen(
    key: Any?,
    fetch: suspend () -> UiResult<T>,
    onLogin: (() -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<UiResult<T>?>(null) }
    LaunchedEffect(key, refreshTick) {
        result = fetch()
    }
    when (val r = result) {
        null -> LoadingBox()
        is UiResult.Failure -> ErrorBox(
            message = r.message,
            needLogin = r.needLogin,
            onRetry = { refreshTick++ },
            onLogin = onLogin
        )
        is UiResult.Success -> content(r.data)
    }
}
