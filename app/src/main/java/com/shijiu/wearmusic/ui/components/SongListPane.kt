package com.shijiu.wearmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import com.ohmusic.app.data.model.Song
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.NavData
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.chipColors

/**
 * 通用「头部 + 歌曲列表」页面骨架：自动处理播放队列、当前行高亮与歌曲菜单。
 */
@Composable
fun SongListPane(
    nav: NavHostController,
    title: String,
    subtitle: String? = null,
    coverUrl: String? = null,
    songs: List<Song>,
    queueTag: String? = null,
    tailLoader: (suspend () -> List<Song>)? = null,
    headerExtra: (@Composable () -> Unit)? = null,
    extraMenuActions: (Song) -> List<MenuItem> = { emptyList() },
    footer: (@Composable () -> Unit)? = null,
    onCoverClick: (() -> Unit)? = null
) {
    val playback = ServiceLocator.container.playbackManager
    var menuSong by remember { mutableStateOf<Song?>(null) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (coverUrl != null) {
                    CoverImage(
                        coverUrl,
                        84.dp,
                        corner = 10.dp,
                        modifier = if (onCoverClick != null) {
                            Modifier.clickable(onClick = onCoverClick)
                        } else Modifier
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .basicMarquee()
                )
                subtitle?.let {
                    // 多行副标题拆行：每行超宽时独立滚动（歌手名等不被截断）
                    it.split("\n").forEach { line ->
                        Text(
                            line,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .basicMarquee()
                        )
                    }
                }
            }
        }
        item {
            if (songs.isNotEmpty()) {
                Button(
                    onClick = {
                        playback.playQueue(songs, 0, queueTag, tailLoader)
                    },
                    colors = chipColors(true),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "播放全部 · ${songs.size}首",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        headerExtra?.let { item { it() } }
        items(songs.size) { i ->
            val song = songs[i]
            SongRow(
                song = song,
                isCurrent = playback.currentSong?.songId == song.songId,
                indexLabel = "${i + 1}",
                onPlay = { playback.playQueue(songs, i, queueTag, tailLoader) },
                onMenu = { menuSong = song }
            )
        }
        footer?.let { item { it() } }
    }

    menuSong?.let { song ->
        MenuDialog(
            showDialog = true,
            title = song.title,
            onDismiss = { menuSong = null },
            items = buildList {
                addAll(artistMenuItems(song, nav))
                song.albumId.takeIf { it > 0 }?.let {
                    add(MenuItem(Icons.Filled.Album, "专辑：${song.album}") {
                        nav.navigate(Routes.album(it))
                    })
                }
                song.songId?.let { id ->
                    add(MenuItem(Icons.Filled.ChatBubble, "歌曲评论") {
                        nav.navigate(Routes.comments(0, id, song.title))
                    })
                }
                add(MenuItem(Icons.Filled.LibraryAdd, "收藏到歌单") {
                    NavData.pendingSong = song
                    nav.navigate(Routes.addToPlaylist(song.songId ?: -1L))
                })
                addAll(extraMenuActions(song))
            }
        )
    }
}

/** 「删除类」菜单项快捷构造。 */
fun deleteMenuItem(label: String, action: () -> Unit): MenuItem =
    MenuItem(Icons.Filled.Delete, label, danger = true, action = action)

/** 「移出歌单」菜单项。 */
fun removeMenuItem(action: () -> Unit): MenuItem =
    MenuItem(Icons.Filled.RemoveCircleOutline, "从歌单移除", danger = true, action = action)
