package com.shijiu.wearmusic.ui.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChatBubble
import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.api.CloudAlbumSummary
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.MediaRow
import com.shijiu.wearmusic.ui.components.MenuItem
import com.shijiu.wearmusic.ui.components.MenuDialog
import com.shijiu.wearmusic.ui.components.SongRow
import kotlinx.coroutines.launch

private enum class ArtistTab(val label: String) {
    HOT("热门单曲"),
    ALL("全部单曲"),
    ALBUMS("专辑")
}

/** 歌手页：热门单曲 / 全部单曲（最热/最新，分页）/ 专辑列表。 */
@Composable
fun ArtistScreen(nav: NavHostController, artistId: Long) {
    val repo = ServiceLocator.container.musicRepo
    val playback = ServiceLocator.container.playbackManager
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(ArtistTab.HOT) }
    var hotSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var order by remember { mutableStateOf("hot") }
    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var allLoading by remember { mutableStateOf(false) }
    var allError by remember { mutableStateOf<String?>(null) }
    var albums by remember { mutableStateOf<List<CloudAlbumSummary>>(emptyList()) }
    var albumOffset by remember { mutableIntStateOf(0) }
    var albumHasMore by remember { mutableStateOf(false) }
    var albumLoading by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }

    fun loadAllSongs(reset: Boolean) {
        if (allLoading) return
        allLoading = true
        allError = null
        val offset = if (reset) 0 else allSongs.size
        scope.launch {
            when (val r = repo.artistSongs(artistId, order, offset)) {
                is com.shijiu.wearmusic.data.UiResult.Success -> {
                    val fresh = r.data
                    allSongs = if (reset) fresh else (allSongs + fresh).distinctBy { it.songId }
                }
                is com.shijiu.wearmusic.data.UiResult.Failure -> allError = r.message
            }
            allLoading = false
        }
    }

    fun loadAlbums(reset: Boolean) {
        if (albumLoading) return
        albumLoading = true
        val offset = if (reset) 0 else albumOffset
        scope.launch {
            when (val r = repo.artistAlbums(artistId, offset)) {
                is com.shijiu.wearmusic.data.UiResult.Success -> {
                    val fresh = r.data
                    albums = if (reset) fresh else (albums + fresh).distinctBy { it.id }
                    albumOffset = offset + fresh.size
                    albumHasMore = fresh.size >= 30
                }
                else -> albumHasMore = false
            }
            albumLoading = false
        }
    }

    LaunchedEffect(tab) {
        if (tab == ArtistTab.ALL && allSongs.isEmpty()) loadAllSongs(true)
        if (tab == ArtistTab.ALBUMS && albums.isEmpty()) loadAlbums(true)
    }

    LaunchedEffect(artistId) {
        when (val r = repo.artistHotSongs(artistId)) {
            is com.shijiu.wearmusic.data.UiResult.Success -> hotSongs = r.data
            else -> Unit
        }
    }

    ScreenScaffold {
        LoadScreen(
            key = artistId,
            fetch = { repo.artistDetail(artistId) }
        ) { detail ->
            val artist = detail.artist
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CoverImage(artist.avatarUrl, 76.dp, corner = 38.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            artist.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (artist.alias.isNotBlank()) {
                            Text(
                                artist.alias,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "${artist.musicSize}首单曲 · ${artist.albumSize}张专辑",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ArtistTab.entries.forEach { t ->
                            Button(
                                onClick = { tab = t },
                                modifier = Modifier.height(32.dp),
                                colors = buttonColors(tab == t)
                            ) {
                                Text(t.label, fontSize = 10.sp)
                            }
                        }
                    }
                }

                if (tab == ArtistTab.HOT) {
                    items(hotSongs.size) { i ->
                        val song = hotSongs[i]
                        SongRow(
                            song = song,
                            isCurrent = playback.currentSong?.songId == song.songId,
                            indexLabel = "${i + 1}",
                            onPlay = {
                                playback.playQueue(hotSongs, i, "artist:$artistId")
                            },
                            onMenu = { menuSong = song }
                        )
                    }
                    if (hotSongs.isEmpty()) {
                        item { Text("暂无热门单曲", fontSize = 12.sp, color = TextSecondary) }
                    }
                }

                if (tab == ArtistTab.ALL) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = {
                                    if (order != "hot") {
                                        order = "hot"
                                        loadAllSongs(true)
                                    }
                                },
                                modifier = Modifier.height(30.dp),
                                colors = buttonColors(order == "hot")
                            ) { Text("最热", fontSize = 10.sp) }
                            Button(
                                onClick = {
                                    if (order != "time") {
                                        order = "time"
                                        loadAllSongs(true)
                                    }
                                },
                                modifier = Modifier.height(30.dp),
                                colors = buttonColors(order == "time")
                            ) { Text("最新", fontSize = 10.sp) }
                        }
                    }
                    items(allSongs.size) { i ->
                        val song = allSongs[i]
                        SongRow(
                            song = song,
                            isCurrent = playback.currentSong?.songId == song.songId,
                            indexLabel = "${i + 1}",
                            onPlay = {
                                playback.playQueue(allSongs, i, "artist:$artistId")
                            },
                            onMenu = { menuSong = song }
                        )
                    }
                    allError?.let {
                        item { Text(it, fontSize = 11.sp, color = TextSecondary) }
                    }
                    item {
                        Button(
                            onClick = { loadAllSongs(false) },
                            enabled = !allLoading,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                if (allLoading) "加载中…" else "加载更多",
                                fontSize = 11.sp,
                                color = if (allLoading) TextSecondary else Color.White
                            )
                        }
                    }
                }

                if (tab == ArtistTab.ALBUMS) {
                    items(albums.size) { i ->
                        val album = albums[i]
                        MediaRow(
                            title = album.name,
                            subtitle = "${album.songCount}首 · 发布于 ${year(album.publishTimeMs)}",
                            coverUrl = album.coverUrl,
                            onClick = { nav.navigate(Routes.album(album.id)) }
                        )
                    }
                    if (albumHasMore) {
                        item {
                            Button(
                                onClick = { loadAlbums(false) },
                                enabled = !albumLoading,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    if (albumLoading) "加载中…" else "加载更多专辑",
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    if (albums.isEmpty() && !albumLoading) {
                        item { Text("暂无专辑", fontSize = 12.sp, color = TextSecondary) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    menuSong?.let { song ->
        MenuDialog(
            showDialog = true,
            title = song.title,
            onDismiss = { menuSong = null },
            items = buildList {
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
            }
        )
    }
}

private fun year(ms: Long): String =
    if (ms <= 0) "未知" else java.text.SimpleDateFormat("yyyy", java.util.Locale.US).format(java.util.Date(ms))

@Suppress("unused")
private val unusedKeep = NeteaseRed
