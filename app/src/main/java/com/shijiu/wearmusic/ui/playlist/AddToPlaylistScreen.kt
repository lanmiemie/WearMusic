package com.shijiu.wearmusic.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.NavData
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.MediaRow
import kotlinx.coroutines.launch

/** 收藏歌曲到自己的歌单。 */
@Composable
fun AddToPlaylistScreen(nav: NavHostController, songId: Long) {
    val repo = ServiceLocator.container.musicRepo
    val accountRepo = ServiceLocator.container.accountRepo
    val scope = rememberCoroutineScope()

    var songTitle by remember { mutableStateOf(NavData.pendingSong?.title ?: "所选歌曲") }
    var playlists by remember { mutableStateOf<List<com.ohmusic.app.data.remote.api.CloudPlaylist>?>(null) }
    var failure by remember { mutableStateOf<UiResult.Failure?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var addedTo by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(songId, tick) {
        // 进程被杀导致 NavData 丢失时，回退按 id 拉取歌曲信息
        if (NavData.pendingSong == null && songId > 0) {
            repo.songDetail(songId)?.let { NavData.pendingSong = it }
        }
        NavData.pendingSong?.let { songTitle = it.title }
        val r = repo.userPlaylists()
        when (r) {
            is UiResult.Success ->
                playlists = r.data.filter {
                    it.creatorUserId == accountRepo.uid && !it.isLikedPlaylist
                }
            is UiResult.Failure -> failure = r
        }
    }

    fun addTo(playlistId: Long, name: String) {
        val song = NavData.pendingSong ?: return
        val id = song.songId ?: return
        scope.launch {
            val res = repo.addPlaylistTracks(playlistId, listOf(id))
            message = when {
                res.isSuccess -> {
                    addedTo = addedTo + playlistId
                    "已添加到「$name」"
                }
                else -> (res as UiResult.Failure).message
            }
        }
    }

    ScreenScaffold {
        when {
            failure != null -> ErrorBox(
                message = failure!!.message,
                needLogin = failure!!.needLogin,
                onRetry = { tick++ },
                onLogin = { nav.navigate(Routes.LOGIN) }
            )
            playlists == null -> LoadingBox("加载我的歌单…")
            else -> {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item { SectionTitle("收藏到歌单") }
                    item {
                        Text(
                            "「$songTitle」",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                    item {
                        Button(
                            onClick = { nav.navigate(Routes.CREATE_PLAYLIST) },
                            colors = com.shijiu.wearmusic.ui.chipColors(false),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = NeteaseRed)
                            Spacer(Modifier.width(8.dp))
                            Text("新建歌单", fontSize = 12.sp)
                        }
                    }
                    message?.let { item { Text(it, fontSize = 11.sp, color = NeteaseRed) } }
                    items(playlists!!.size) { i ->
                        val pl = playlists!![i]
                        val added = addedTo.contains(pl.id)
                        MediaRow(
                            title = pl.name,
                            subtitle = "${pl.trackCount}首",
                            coverUrl = pl.coverUrl,
                            badge = if (added) "已加" else null,
                            onClick = {
                                if (!added) addTo(pl.id, pl.name)
                            }
                        )
                    }
                    if (playlists!!.isEmpty()) {
                        item {
                            Text(
                                "还没有自己的歌单\n先新建一个吧",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
