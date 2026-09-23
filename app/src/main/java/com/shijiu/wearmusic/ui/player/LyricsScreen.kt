package com.shijiu.wearmusic.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material3.Text
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.util.LrcLine
import com.shijiu.wearmusic.util.LrcParser
import com.shijiu.wearmusic.util.YrcLine
import com.shijiu.wearmusic.util.YrcParser
import com.shijiu.wearmusic.util.rememberLyricPositionMs

/**
 * 全屏歌词页：随播放进度自动滚动。
 *
 * 优先逐字歌词（yrc）：当前行卡拉OK式逐字点亮；
 * 无 yrc 时退回行级 LRC 高亮。活动行下标由帧级时钟派生，
 * 只在换行时才触发重组（derivedStateOf），行内推进仅重绘。
 */
@Composable
fun LyricsScreen() {
    val container = ServiceLocator.container
    val playback = container.playbackManager
    val musicRepo = container.musicRepo

    val queue by playback.queue.collectAsState()
    val index by playback.currentIndex.collectAsState()
    val song = queue.getOrNull(index)

    // 帧级插值位置：只在绘制层高频读取
    val positionState = rememberLyricPositionMs(playback)

    var yrcLines by remember { mutableStateOf<List<YrcLine>?>(null) } // null=加载中
    var lrcLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    /** 按行下标对齐的翻译文本（空串表示该行无翻译）。 */
    var translations by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadedFor by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(song?.songId) {
        val id = song?.songId ?: return@LaunchedEffect
        if (loadedFor == id) return@LaunchedEffect
        loadedFor = id
        yrcLines = null
        lrcLines = emptyList()
        translations = emptyList()
        when (val r = musicRepo.lyric(id)) {
            is UiResult.Success -> {
                val parsed = YrcParser.parse(r.data.yrc?.lyric.orEmpty())
                yrcLines = parsed
                if (parsed.isNotEmpty()) {
                    translations = LrcParser.alignTranslations(
                        parsed.map { it.startMs }, r.data.translatedLyric
                    )
                } else {
                    val lrc = LrcParser.parse(r.data.primaryLyric)
                    lrcLines = lrc
                    translations = LrcParser.alignTranslations(
                        lrc.map { it.timeMs }, r.data.translatedLyric
                    )
                }
            }
            else -> yrcLines = emptyList()
        }
    }

    val yrc = yrcLines
    val hasYrc = yrc != null && yrc.isNotEmpty()
    val hasLrc = !hasYrc && lrcLines.isNotEmpty()

    // 活动行下标：读帧级位置但只在换行时通知（避免逐帧重组）。
    // key 直接绑列表引用：切歌换列表时派生状态随之重建。
    val activeState = remember(yrc, lrcLines) {
        derivedStateOf {
            val pos = positionState.value
            when {
                yrc != null && yrc.isNotEmpty() -> YrcParser.activeIndex(yrc, pos)
                lrcLines.isNotEmpty() -> LrcParser.activeIndex(lrcLines, pos)
                else -> -1
            }
        }
    }
    val active by activeState

    val listState: ScalingLazyListState = rememberScalingLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) {
            runCatching { listState.animateScrollToItem(active + 1) } // +1：标题占位行
        }
    }

    ScreenScaffold(showTimeText = false) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = 90.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                ) {
                    Text(
                        song?.title ?: "歌词",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
            when {
                yrc == null -> item {
                    Text("歌词加载中…", fontSize = 13.sp, color = TextSecondary)
                }
                hasYrc -> items(yrc.size) { i ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (i == active) {
                            KaraokeLineText(
                                line = yrc[i],
                                positionState = positionState,
                                fontSize = 15.sp,
                                sungColor = NeteaseRed,
                                unsungColor = Color(0xFFC9C9CE),
                                maxLines = 3
                            )
                        } else {
                            Text(
                                yrc[i].text,
                                fontSize = if (i == active + 1 || i == active - 1) 12.sp else 11.sp,
                                color = when {
                                    i == active + 1 || i == active - 1 -> Color(0xFFDDDDDE)
                                    else -> TextSecondary.copy(alpha = 0.75f)
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                        TranslationText(
                            text = translations.getOrNull(i).orEmpty(),
                            isActive = i == active
                        )
                    }
                }
                hasLrc -> items(lrcLines.size) { i ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            lrcLines[i].text,
                            fontSize = if (i == active) 14.sp else 12.sp,
                            color = when {
                                i == active -> NeteaseRed
                                i == active + 1 || i == active - 1 -> Color(0xFFDDDDDE)
                                else -> TextSecondary.copy(alpha = 0.75f)
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        TranslationText(
                            text = translations.getOrNull(i).orEmpty(),
                            isActive = i == active
                        )
                    }
                }
                else -> item {
                    Text("暂无歌词", fontSize = 13.sp, color = TextSecondary)
                }
            }
        }
    }
}

/** 歌词翻译小字：无翻译时不占位；当前行稍大更亮。 */
@Composable
private fun TranslationText(text: String, isActive: Boolean) {
    if (text.isBlank()) return
    Text(
        text,
        fontSize = if (isActive) 10.sp else 9.sp,
        color = if (isActive) Color(0xFFE8E8EC) else TextSecondary.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 24.dp, end = 24.dp)
    )
}
