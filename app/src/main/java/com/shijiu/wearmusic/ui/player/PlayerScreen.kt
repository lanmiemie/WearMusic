package com.shijiu.wearmusic.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Slider
import com.shijiu.wearmusic.ServiceLocator
import com.ohmusic.app.data.model.Song
import com.shijiu.wearmusic.data.MusicRepository
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.playback.PlaybackManager
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.NavData
import com.shijiu.wearmusic.ui.EmptyBox
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.components.MenuDialog
import com.shijiu.wearmusic.ui.components.MenuItem
import com.shijiu.wearmusic.util.LrcLine
import com.shijiu.wearmusic.util.LrcParser
import com.shijiu.wearmusic.util.TimeFmt
import com.shijiu.wearmusic.util.YrcLine
import com.shijiu.wearmusic.util.YrcParser
import com.shijiu.wearmusic.util.rememberLyricPositionMs
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val playback = container.playbackManager
    val musicRepo = container.musicRepo
    val accountRepo = container.accountRepo
    val scope = rememberCoroutineScope()

    // 只收集低频状态；position/isPlaying 等高频状态下沉到 ProgressPane / PlayControlPane，
    // 避免整个播放列表每秒重组造成卡顿
    val queue by playback.queue.collectAsState()
    val index by playback.currentIndex.collectAsState()
    val notice by playback.notice.collectAsState()
    val likedIds by musicRepo.likedIds.collectAsState()

    val song = queue.getOrNull(index)

    LaunchedEffect(Unit) {
        if (accountRepo.isLoggedIn) musicRepo.ensureLikedLoaded()
    }

    if (song == null) {
        ScreenScaffold {
            EmptyBox("暂无播放内容\n回首页挑一首歌吧")
        }
        return
    }

    val liked = musicRepo.isLiked(song.songId)
    var showMenu by remember { mutableStateOf(false) }

    ScreenScaffold(showTimeText = false) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 30.dp, bottom = 46.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                CoverImage(
                    song.coverUrl,
                    size = 118.dp,
                    corner = 10.dp
                )
            }
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        song.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        song.artist,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
            item { ProgressPane(playback) }
            item { PlayControlPane(playback) }
            item { InlineLyricsPane(playback, musicRepo, song) }
            item { VolumePane() }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallAction(
                        icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        label = if (liked) "已红心" else "红心",
                        tint = if (liked) NeteaseRed else TextSecondary,
                        onClick = {
                            val id = song.songId ?: return@SmallAction
                            if (!accountRepo.isLoggedIn) {
                                playback.notify("登录后才能收藏红心")
                                return@SmallAction
                            }
                            scope.launch {
                                val target = musicRepo.toggleLike(id)
                                playback.notify(if (target) "已加入我喜欢" else "已取消红心")
                            }
                        }
                    )
                    SmallAction(
                        icon = Icons.Filled.Audiotrack,
                        label = "歌词",
                        onClick = { nav.navigate(Routes.LYRICS) }
                    )
                    SmallAction(
                        icon = Icons.Filled.ChatBubble,
                        label = "评论",
                        onClick = {
                            val id = song.songId ?: return@SmallAction
                            nav.navigate(Routes.comments(0, id, song.title))
                        }
                    )
                    SmallAction(
                        icon = Icons.Filled.LibraryAdd,
                        label = "收藏",
                        onClick = {
                            NavData.pendingSong = song
                            nav.navigate(Routes.addToPlaylist(song.songId ?: -1L))
                        }
                    )
                    SmallAction(
                        icon = Icons.Filled.Whatshot,
                        label = "心动",
                        onClick = {
                            val seed = song.songId ?: return@SmallAction
                            val pid = playback.queueContextTag
                                ?.removePrefix("playlist:")
                                ?.toLongOrNull() ?: -1L
                            NavData.heartSeed = seed to pid
                            nav.navigate(Routes.HEART)
                        }
                    )
                }
            }
            notice?.let {
                item { InlineNotice(it) }
            }
            item {
                Button(
                    onClick = { showMenu = true },
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B31))
                ) { Text("更多操作", fontSize = 11.sp) }
            }
        }
    }

    if (showMenu) {
        MenuDialog(
            showDialog = true,
            title = song.title,
            onDismiss = { showMenu = false },
            items = buildList {
                song.albumId.takeIf { it > 0 }?.let {
                    add(
                        MenuItem(Icons.Filled.Audiotrack, "查看专辑「${song.album}」") {
                            nav.navigate(Routes.album(it))
                        }
                    )
                }
                song.artistId?.takeIf { it > 0 }?.let {
                    add(
                        MenuItem(Icons.Filled.Whatshot, "查看歌手「${song.artist}」") {
                            nav.navigate(Routes.artist(it))
                        }
                    )
                }
                song.songId?.let { id ->
                    add(
                        MenuItem(Icons.Filled.ChatBubble, "查看歌曲评论") {
                            nav.navigate(Routes.comments(0, id, song.title))
                        }
                    )
                }
            }
        )
    }
}

/** 进度条：内部收集 position/duration，每秒只重组本组件。 */
@Composable
private fun ProgressPane(playback: PlaybackManager) {
    val position by playback.positionMs.collectAsState()
    val duration by playback.durationMs.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val progress = if (duration > 0) {
        (if (dragging) dragValue else position.toFloat() / duration).coerceIn(0f, 1f)
    } else 0f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 14.dp)
    ) {
        Text(
            TimeFmt.mmss(if (dragging) (dragValue * duration).toLong() else position),
            fontSize = 10.sp,
            color = TextSecondary
        )
        Slider(
            value = progress,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                if (duration > 0) playback.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(22.dp)
        )
        Text(
            TimeFmt.mmss(duration),
            fontSize = 10.sp,
            color = TextSecondary
        )
    }
}

/** 音量调节：媒体音量滑条，拖动即时生效。 */
@Composable
private fun VolumePane() {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 14.dp)
    ) {
        Icon(
            Icons.Filled.VolumeUp,
            contentDescription = "音量",
            modifier = Modifier.size(16.dp),
            tint = TextSecondary
        )
        Slider(
            value = if (maxVolume > 0) volume.toFloat() / maxVolume else 0f,
            onValueChange = {
                val newValue = (it * maxVolume).toInt().coerceIn(0, maxVolume)
                if (newValue != volume) {
                    volume = newValue
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newValue, 0)
                }
            },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(22.dp)
        )
        Text(
            "$volume",
            fontSize = 10.sp,
            color = TextSecondary
        )
    }
}

/** 播放控制三键：内部收集播放状态。 */
@Composable
private fun PlayControlPane(playback: PlaybackManager) {
    val isPlaying by playback.isPlaying.collectAsState()
    val isBuffering by playback.isBuffering.collectAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = { playback.previous() },
            modifier = Modifier.size(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B31))
        ) {
            Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(24.dp))
        }
        Button(
            onClick = { playback.togglePlayPause() },
            modifier = Modifier.size(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeteaseRed)
        ) {
            if (isBuffering && !isPlaying) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
            } else {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Button(
            onClick = { playback.next() },
            modifier = Modifier.size(46.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B31))
        ) {
            Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = TextSecondary,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B31))
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = tint)
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 9.sp, color = TextSecondary)
    }
}

/**
 * 播放页内嵌迷你歌词：上一行（小灰）→ 当前行（逐字点亮）→ 下一行（小灰）。
 * 有逐字数据时当前行卡拉OK高亮，否则退回整行红色高亮；无歌词不占位。
 */
@Composable
private fun InlineLyricsPane(
    playback: PlaybackManager,
    musicRepo: MusicRepository,
    song: Song
) {
    val songId = song.songId ?: return
    val positionState = rememberLyricPositionMs(playback)

    var yrcLines by remember { mutableStateOf<List<YrcLine>>(emptyList()) }
    var lrcLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var translations by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(songId) {
        when (val r = musicRepo.lyric(songId)) {
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
            else -> Unit
        }
    }

    if (yrcLines.isEmpty() && lrcLines.isEmpty()) return

    val activeState = remember(yrcLines, lrcLines) {
        derivedStateOf {
            val pos = positionState.value
            if (yrcLines.isNotEmpty()) {
                YrcParser.activeIndex(yrcLines, pos)
            } else {
                LrcParser.activeIndex(lrcLines, pos)
            }
        }
    }
    val active by activeState
    if (active < 0) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        val prevText = when {
            yrcLines.isNotEmpty() -> yrcLines.getOrNull(active - 1)?.text
            else -> lrcLines.getOrNull(active - 1)?.text
        }
        val nextText = when {
            yrcLines.isNotEmpty() -> yrcLines.getOrNull(active + 1)?.text
            else -> lrcLines.getOrNull(active + 1)?.text
        }
        prevText?.let {
            Text(
                it,
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (yrcLines.isNotEmpty()) {
            KaraokeLineText(
                line = yrcLines[active],
                positionState = positionState,
                fontSize = 14.sp,
                sungColor = NeteaseRed,
                unsungColor = Color(0xFFCFCFD4),
                maxLines = 2
            )
        } else {
            Text(
                lrcLines[active].text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = NeteaseRed,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        translations.getOrNull(active)?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                fontSize = 9.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        nextText?.let {
            Text(
                it,
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
