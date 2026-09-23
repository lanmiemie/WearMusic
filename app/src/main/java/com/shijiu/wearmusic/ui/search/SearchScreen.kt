package com.shijiu.wearmusic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import com.ohmusic.app.data.model.Song
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.AppPrefs
import com.shijiu.wearmusic.data.SearchResults
import com.shijiu.wearmusic.data.SearchType
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.MediaRow
import com.shijiu.wearmusic.ui.components.MenuDialog
import com.shijiu.wearmusic.ui.components.MenuItem
import com.shijiu.wearmusic.ui.components.SongRow
import kotlinx.coroutines.delay

/** 内容搜索：单曲 / 歌单 / 歌手 / 专辑 / 播客 + 热搜、联想与历史。 */
@Composable
fun SearchScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val repo = container.musicRepo
    val playback = container.playbackManager
    val prefs: AppPrefs = container.prefs

    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SearchType.SONGS) }
    var submitted by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UiResult<SearchResults>?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var hotWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf(prefs.searchHistory()) }
    var loading by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(Unit) {
        hotWords = (repo.hotSearch() as? UiResult.Success)?.data.orEmpty()
    }
    LaunchedEffect(query) {
        if (query.isBlank() || query == submitted) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        suggestions = (repo.searchSuggest(query) as? UiResult.Success)?.data.orEmpty()
    }
    LaunchedEffect(submitted, type) {
        if (submitted.isBlank()) return@LaunchedEffect
        loading = true
        result = repo.search(submitted, type)
        loading = false
    }

    fun submit(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        query = kw
        submitted = kw
        suggestions = emptyList()
        prefs.addSearchHistory(kw)
        history = prefs.searchHistory()
    }

    ScreenScaffold {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF2B2B31))
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) {
                                        Text("搜索音乐", fontSize = 13.sp, color = Color(0xFF77777F))
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { submit(query) },
                        enabled = query.isNotBlank(),
                        modifier = Modifier.size(40.dp),
                        colors = buttonColors(true)
                    ) {
                        Icon(Icons.Filled.Search, "搜索", Modifier.size(18.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SearchType.entries.forEach { t ->
                        Box(
                            modifier = Modifier
                                .background(
                                    if (t == type) NeteaseRed else Color(0xFF2B2B31),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { type = t }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Text(
                                t.label,
                                fontSize = 11.sp,
                                color = if (t == type) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            when {
                submitted.isBlank() -> {
                    if (suggestions.isNotEmpty()) {
                        item { SectionTitle("猜你想搜") }
                        items(suggestions.size) { i ->
                            SuggestionRow(suggestions[i]) { submit(suggestions[i]) }
                        }
                    }
                    if (hotWords.isNotEmpty()) {
                        item { SectionTitle("热搜榜") }
                        items(hotWords.size.coerceAtMost(10)) { i ->
                            SuggestionRow("${i + 1}. ${hotWords[i]}") { submit(hotWords[i]) }
                        }
                    } else {
                        item { Text("热搜加载中…", fontSize = 11.sp, color = TextSecondary) }
                    }
                    if (history.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Box(Modifier.weight(1f)) { SectionTitle("搜索历史") }
                                Box(
                                    Modifier
                                        .clickable {
                                            prefs.clearSearchHistory()
                                            history = emptyList()
                                        }
                                        .padding(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close, "清空历史",
                                        Modifier.size(14.dp), tint = TextSecondary
                                    )
                                }
                            }
                        }
                        items(history.size) { i ->
                            SuggestionRow(history[i]) { submit(history[i]) }
                        }
                    }
                }
                loading -> item { LoadingBox("搜索中…") }
                else -> when (val r = result) {
                    null -> item { LoadingBox() }
                    is UiResult.Failure -> item {
                        ErrorBox(
                            message = r.message,
                            needLogin = r.needLogin,
                            onRetry = { submitted = "" }
                        )
                    }
                    is UiResult.Success -> {
                        val data = r.data
                        when (type) {
                            SearchType.SONGS -> {
                                if (data.songs.isEmpty()) {
                                    item { ErrorBox(message = "没有找到相关单曲") }
                                } else {
                                    items(data.songs.size) { i ->
                                        SongRow(
                                            song = data.songs[i],
                                            indexLabel = "${i + 1}",
                                            onPlay = {
                                                playback.playQueue(data.songs, i, "search")
                                            },
                                            onMenu = { menuSong = data.songs[i] }
                                        )
                                    }
                                }
                            }
                            SearchType.PLAYLISTS -> resultItems(data.playlists.isNotEmpty()) {
                                items(data.playlists.size) { i ->
                                    val pl = data.playlists[i]
                                    MediaRow(
                                        title = pl.name,
                                        subtitle = "${pl.trackCount}首 · ${pl.creatorNickname}",
                                        coverUrl = pl.coverUrl,
                                        onClick = { nav.navigate(Routes.playlist(pl.id)) }
                                    )
                                }
                            }
                            SearchType.ARTISTS -> resultItems(data.artists.isNotEmpty()) {
                                items(data.artists.size) { i ->
                                    val artist = data.artists[i]
                                    MediaRow(
                                        title = artist.name,
                                        subtitle = buildString {
                                            if (artist.alias.isNotBlank()) append(artist.alias + " · ")
                                            append("${artist.musicSize}首 · ${artist.albumSize}专辑")
                                        },
                                        coverUrl = artist.avatarUrl,
                                        onClick = { nav.navigate(Routes.artist(artist.id)) }
                                    )
                                }
                            }
                            SearchType.ALBUMS -> resultItems(data.albums.isNotEmpty()) {
                                items(data.albums.size) { i ->
                                    val album = data.albums[i]
                                    MediaRow(
                                        title = album.name,
                                        subtitle = "${album.artistName} · ${album.songCount}首",
                                        coverUrl = album.coverUrl,
                                        onClick = { nav.navigate(Routes.album(album.id)) }
                                    )
                                }
                            }
                            SearchType.DJS -> resultItems(data.djs.isNotEmpty()) {
                                items(data.djs.size) { i ->
                                    val dj = data.djs[i]
                                    MediaRow(
                                        title = dj.name,
                                        subtitle = "${dj.djNickname} · ${dj.programCount}期",
                                        coverUrl = dj.coverUrl,
                                        onClick = { nav.navigate(Routes.dj(dj.id)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    menuSong?.let { song ->
        MenuDialog(
            showDialog = true,
            title = song.title,
            onDismiss = { menuSong = null },
            items = buildList {
                song.artistId?.takeIf { it > 0 }?.let {
                    add(MenuItem(Icons.Filled.Person, "歌手：${song.artist}") {
                        nav.navigate(Routes.artist(it))
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

/** 结果区包装：空结果时显示提示。 */
private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.resultItems(
    hasData: Boolean,
    content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit
) {
    if (hasData) content(this)
    else item { ErrorBox(message = "没有找到相关内容") }
}

@Composable
private fun SuggestionRow(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF222228), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
