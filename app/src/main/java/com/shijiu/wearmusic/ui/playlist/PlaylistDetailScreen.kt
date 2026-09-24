package com.shijiu.wearmusic.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Whatshot
import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.api.CloudPlaylistDetail
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.NavData
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.components.MenuDialog
import com.shijiu.wearmusic.ui.components.MenuItem
import com.shijiu.wearmusic.ui.components.SongRow
import com.shijiu.wearmusic.ui.components.artistMenuItems
import com.shijiu.wearmusic.ui.components.deleteMenuItem
import com.shijiu.wearmusic.util.TimeFmt
import kotlinx.coroutines.launch

/**
 * 歌单详情：收藏/取消收藏（他人歌单）、编辑信息与隐私（自己的歌单）、
 * 心动模式、评论、移除歌曲。
 */
@Composable
fun PlaylistDetailScreen(nav: NavHostController, playlistId: Long) {
    val container = ServiceLocator.container
    val repo = container.musicRepo
    val playback = container.playbackManager
    val accountRepo = container.accountRepo
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<UiResult<CloudPlaylistDetail>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var subscribed by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var removeTarget by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(playlistId, tick) {
        val r = repo.playlistDetail(playlistId)
        result = r
        if (r is UiResult.Success) subscribed = r.data.playlist.subscribed
    }

    fun post(msg: String) {
        message = msg
    }

    ScreenScaffold {
        when (val r = result) {
            null -> LoadingBox("加载歌单…")
            is UiResult.Failure -> ErrorBox(
                message = r.message,
                needLogin = r.needLogin,
                onRetry = { tick++ },
                onLogin = { nav.navigate(Routes.LOGIN) }
            )
            is UiResult.Success -> {
                val detail = r.data
                val pl = detail.playlist
                val isMine = pl.creatorUserId > 0 && pl.creatorUserId == accountRepo.uid

                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CoverImage(pl.coverUrl, 88.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                pl.name,
                                fontSize = 16.sp,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 18.dp)
                            )
                            Text(
                                buildString {
                                    append("${pl.trackCount}首 · ${TimeFmt.count(pl.playCount)}次播放")
                                    if (pl.creatorNickname.isNotBlank()) {
                                        append("\nby ${pl.creatorNickname}")
                                    }
                                },
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 14.sp
                            )
                        }
                    }
                    if (pl.description.isNotBlank()) {
                        item {
                            Text(
                                pl.description,
                                fontSize = 10.sp,
                                color = TextSecondary.copy(alpha = 0.8f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 22.dp)
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    playback.playQueue(detail.songs, 0, "playlist:$playlistId")
                                    nav.navigate(Routes.PLAYER)
                                },
                                modifier = Modifier.height(38.dp),
                                colors = buttonColors(true)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                                    Text("播放", fontSize = 12.sp)
                                }}
                            if (!isMine) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val target = !subscribed
                                            val res = repo.subscribePlaylist(playlistId, target)
                                            if (res.isSuccess) {
                                                subscribed = target
                                                post(if (target) "已收藏歌单" else "已取消收藏")
                                            } else post("操作失败")
                                        }
                                    },
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Filled.ThumbUp, null, Modifier.size(15.dp))
                                        Text(if (subscribed) "已收藏" else "收藏", fontSize = 12.sp)
                                    }}
                            } else {
                                Button(
                                    onClick = { nav.navigate(Routes.playlistEdit(playlistId)) },
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Filled.Edit, null, Modifier.size(15.dp))
                                        Text("编辑", fontSize = 12.sp)
                                    }}
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    val first = detail.songs.firstOrNull()
                                    if (first?.songId != null) {
                                        NavData.heartSeed = first.songId!! to playlistId
                                        nav.navigate(Routes.HEART)
                                    } else post("歌单里还没有歌曲")
                                },
                                modifier = Modifier.height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Filled.Whatshot, null, Modifier.size(15.dp), tint = NeteaseRed)
                                    Text("心动模式", fontSize = 11.sp)
                                }}
                            Button(
                                onClick = {
                                    nav.navigate(
                                        Routes.comments(2, playlistId, pl.name)
                                    )
                                },
                                modifier = Modifier.height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Filled.ChatBubble, null, Modifier.size(15.dp))
                                    Text("评论", fontSize = 11.sp)
                                }}
                        }
                    }
                    message?.let { item { InlineNotice(it) } }
                    item {
                        Text(
                            "歌曲列表 · ${detail.songs.size}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    items(detail.songs.size) { i ->
                        val song = detail.songs[i]
                        SongRow(
                            song = song,
                            isCurrent = playback.currentSong?.songId == song.songId,
                            indexLabel = "${i + 1}",
                            onPlay = {
                                playback.playQueue(detail.songs, i, "playlist:$playlistId")
                            },
                            onMenu = { menuSong = song }
                        )
                    }
                    if (detail.songs.isEmpty()) {
                        item { Text("歌单暂无歌曲", fontSize = 12.sp, color = TextSecondary) }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    // 歌曲菜单
    val detail = (result as? UiResult.Success)?.data
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
                add(MenuItem(Icons.Filled.ThumbUp, "收藏到歌单") {
                    NavData.pendingSong = song
                    nav.navigate(Routes.addToPlaylist(song.songId ?: -1L))
                })
                if (detail != null && detail.playlist.creatorUserId == accountRepo.uid &&
                    detail.playlist.creatorUserId > 0
                ) {
                    add(deleteMenuItem("从本歌单移除") { removeTarget = song })
                }
            }
        )
    }

    // 移除确认
    removeTarget?.let { song ->
        com.shijiu.wearmusic.ui.components.ConfirmDialog(
            showDialog = true,
            title = "移出歌单",
            message = "把「${song.title}」从歌单中移除？",
            confirmText = "移除",
            danger = true,
            onConfirm = {
                scope.launch {
                    val id = song.songId
                    if (id != null) {
                        val res = repo.removePlaylistTracks(playlistId, listOf(id))
                        post(if (res.isSuccess) "已移除" else "移除失败")
                        tick++
                    }
                }
            },
            onDismiss = { removeTarget = null }
        )
    }
}
