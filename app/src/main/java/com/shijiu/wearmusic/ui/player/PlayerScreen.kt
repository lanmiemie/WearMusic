package com.shijiu.wearmusic.ui.player

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Slider
import coil.compose.AsyncImage
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.playback.PlaybackManager
import com.shijiu.wearmusic.playback.PlayMode
import com.shijiu.wearmusic.ui.EmptyBox
import com.shijiu.wearmusic.ui.NavData
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.components.MenuDialog
import com.shijiu.wearmusic.ui.components.MenuItem
import com.shijiu.wearmusic.ui.components.artistMenuItems
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 播放页（沉浸式）：歌曲封面高斯模糊铺满全屏做底，
 * 上层依次为标题/歌手 → 上一首/播放/下一首三大键 → 细进度条 →
 * 音量 / 播放模式 / 更多 三图标。向右滑进入全屏歌词页，
 * 收藏、评论、专辑、歌手等入口全部收进「更多」菜单。
 */
@Composable
fun PlayerScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val playback = container.playbackManager
    val musicRepo = container.musicRepo
    val accountRepo = container.accountRepo
    val scope = rememberCoroutineScope()

    // 只收集低频状态；position/isPlaying 等高频状态下沉到子组件，
    // 避免整个页面每秒重组
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
    var showVolume by remember { mutableStateOf(false) }

    // 圆形表盘：进度条改为环绕播放键的圆环；方形屏幕保持横条
    val isRoundScreen = LocalConfiguration.current.screenLayout and
        Configuration.SCREENLAYOUT_ROUND_MASK == Configuration.SCREENLAYOUT_ROUND_YES

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 向左滑进入歌词页；进度条/音量条上的横向拖动已被子级消费，不误触
            .pointerInput(Unit) {
                var totalX = 0f
                val threshold = 90.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (totalX < -threshold) nav.navigate(Routes.LYRICS) },
                    onHorizontalDrag = { change, amount ->
                        if (!change.isConsumed) totalX += amount
                        change.consume()
                    }
                )
            }
    ) {
        BlurredCoverBackdrop(song.coverUrl)

        // 圆形表盘：进度环贴屏幕边缘一圈
        if (isRoundScreen) {
            EdgeProgressRing(playback, Modifier.fillMaxSize())
        }

        // 顶部：标题 + 歌手（贴顶，圆形表盘多留出边缘环的距离）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isRoundScreen) 46.dp else 32.dp, start = 24.dp, end = 24.dp)
        ) {
            Text(
                song.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().basicMarquee()
            )
            Text(
                song.artist,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().basicMarquee()
            )
            notice?.let { InlineNotice(it) }
        }

        // 中央：控制键严格对齐表盘中心（方形屏幕时进度条跟随其正下方，整体居中）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            TransportControls(playback)
            if (!isRoundScreen) {
                Spacer(Modifier.height(18.dp))
                SlimProgressBar(playback, Modifier.padding(horizontal = 40.dp))
            }
        }

        // 底部：音量 / 播放模式 / 更多（贴底，圆形表盘避开边缘环）
        BottomBar(
            playback = playback,
            onVolumeClick = { showVolume = true },
            onMenuClick = { showMenu = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isRoundScreen) 34.dp else 26.dp)
        )
    }

    if (showVolume) {
        VolumeOverlay(onDismiss = { showVolume = false })
    }

    if (showMenu) {
        MenuDialog(
            showDialog = true,
            title = song.title,
            onDismiss = { showMenu = false },
            items = buildList {
                add(
                    MenuItem(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (liked) "取消红心" else "红心"
                    ) {
                        val id = song.songId ?: return@MenuItem
                        if (!accountRepo.isLoggedIn) {
                            playback.notify("登录后才能收藏红心")
                            return@MenuItem
                        }
                        scope.launch {
                            val target = musicRepo.toggleLike(id)
                            playback.notify(if (target) "已加入我喜欢" else "已取消红心")
                        }
                    }
                )
                add(MenuItem(Icons.Filled.Whatshot, "心动模式") {
                    val seed = song.songId ?: return@MenuItem
                    val pid = playback.queueContextTag
                        ?.removePrefix("playlist:")
                        ?.toLongOrNull() ?: -1L
                    NavData.heartSeed = seed to pid
                    nav.navigate(Routes.HEART)
                })
                add(MenuItem(Icons.Filled.LibraryAdd, "收藏到歌单") {
                    NavData.pendingSong = song
                    nav.navigate(Routes.addToPlaylist(song.songId ?: -1L))
                })
                song.albumId.takeIf { it > 0 }?.let {
                    add(MenuItem(Icons.Filled.Audiotrack, "查看专辑「${song.album}」") {
                        nav.navigate(Routes.album(it))
                    })
                }
                addAll(artistMenuItems(song, nav) { name -> "查看歌手「$name」" })
                song.songId?.let { id ->
                    add(MenuItem(Icons.Filled.ChatBubble, "查看歌曲评论") {
                        nav.navigate(Routes.comments(0, id, song.title))
                    })
                }
            }
        )
    }
}

/** 背景：封面铺满全屏 + 高斯模糊 + 暗化遮罩，保证前景文字可读（歌词页复用）。 */
@Composable
fun BlurredCoverBackdrop(url: String?) {
    Box(Modifier.fillMaxSize().background(Color(0xFF20232A))) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(14.dp, BlurredEdgeTreatment.Unbounded)
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
    }
}

/** 播放控制三键：无底色纯白图标（与 Wear OS 原生播放器一致），上一首 / 播放暂停 / 下一首。 */
@Composable
private fun TransportControls(playback: PlaybackManager) {
    val isPlaying by playback.isPlaying.collectAsState()
    val isBuffering by playback.isBuffering.collectAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .clickable { playback.previous() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(28.dp), tint = Color.White)
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .clickable { playback.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            if (isBuffering && !isPlaying) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(34.dp),
                    tint = Color.White
                )
            }
        }
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .clickable { playback.next() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(28.dp), tint = Color.White)
        }
    }
}

/**
 * 边缘进度环（圆形表盘专用）：沿屏幕边缘一圈显示播放进度，
 * 从顶部顺时针填充，不可拖动（拖动 seek 由方形屏幕的横条承担）。
 */
@Composable
private fun EdgeProgressRing(playback: PlaybackManager, modifier: Modifier = Modifier) {
    val position by playback.positionMs.collectAsState()
    val duration by playback.durationMs.collectAsState()
    val progress = if (duration > 0) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
    } else 0f

    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val radius = min(size.width, size.height) / 2 - stroke - 2.dp.toPx()
        if (radius <= 0f) return@Canvas
        val arcSize = Size(radius * 2, radius * 2)
        val topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius)
        drawArc(
            color = Color.White.copy(alpha = 0.16f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(stroke),
            size = arcSize,
            topLeft = topLeft
        )
        if (progress > 0f) {
            drawArc(
                color = Color.White.copy(alpha = 0.85f),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
                size = arcSize,
                topLeft = topLeft
            )
        }
    }
}

/**
 * 细进度条：低调的 3dp 圆角条，左右留白的 16dp 高触控带支持横向拖动 seek。
 * 不显示时间数字（与整体沉浸式风格一致）。
 */
@Composable
private fun SlimProgressBar(playback: PlaybackManager, modifier: Modifier = Modifier) {
    val position by playback.positionMs.collectAsState()
    val duration by playback.durationMs.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var barWidth by remember { mutableFloatStateOf(1f) }

    val progress = if (duration > 0) {
        (if (dragging) dragValue else position.toFloat() / duration).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .onSizeChanged { barWidth = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (duration > 0) {
                            dragging = true
                            dragValue = (offset.x / barWidth).coerceIn(0f, 1f)
                        }
                    },
                    onDragEnd = {
                        if (dragging && duration > 0) {
                            playback.seekTo((dragValue * duration).toLong())
                        }
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, amount ->
                        if (dragging) {
                            dragValue = (dragValue + amount / barWidth).coerceIn(0f, 1f)
                        }
                        change.consume()
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.28f))
        )
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f))
        )
    }
}

/** 底部功能条：音量（弹出调节层） / 播放模式（顺序→随机→单曲循环） / 更多菜单。 */
@Composable
private fun BottomBar(
    playback: PlaybackManager,
    onVolumeClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playMode by playback.playMode.collectAsState()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        Icon(
            Icons.Filled.VolumeUp,
            contentDescription = "音量",
            modifier = Modifier.size(24.dp).clickable(onClick = onVolumeClick),
            tint = Color.White
        )
        Icon(
            when (playMode) {
                PlayMode.ORDER -> Icons.Filled.Repeat
                PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne
            },
            contentDescription = "播放模式",
            modifier = Modifier
                .size(24.dp)
                .clickable {
                    playback.cyclePlayMode()
                    playback.notify(
                        when (playback.playMode.value) {
                            PlayMode.ORDER -> "顺序播放"
                            PlayMode.SHUFFLE -> "随机播放"
                            PlayMode.REPEAT_ONE -> "单曲循环"
                        }
                    )
                },
            tint = Color.White
        )
        Icon(
            Icons.Filled.Menu,
            contentDescription = "更多",
            modifier = Modifier.size(24.dp).clickable(onClick = onMenuClick),
            tint = Color.White
        )
    }
}

/** 音量调节覆盖层：点击任意空白处关闭。 */
@Composable
private fun VolumeOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE626262C))
                .clickable(enabled = false) { }
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Icon(Icons.Filled.VolumeUp, null, Modifier.size(20.dp), tint = Color.White)
            Spacer(Modifier.height(10.dp))
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
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "$volume / $maxVolume",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}
