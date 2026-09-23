package com.shijiu.wearmusic.ui.mine

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.ohmusic.app.data.remote.api.CloudAlbumSummary
import com.ohmusic.app.data.remote.api.CloudDj
import com.ohmusic.app.data.remote.api.CloudPlaylist
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.AccountState
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.components.MediaRow
import com.shijiu.wearmusic.util.TimeFmt

private data class MineData(
    val playlists: List<CloudPlaylist> = emptyList(),
    val albums: List<CloudAlbumSummary> = emptyList(),
    val djs: List<CloudDj> = emptyList()
)

/** 我的音乐：个人歌单 / 收藏专辑 / 收藏播客。 */
@Composable
fun MineScreen(nav: NavHostController) {
    val repo = ServiceLocator.container.musicRepo
    val accountRepo = ServiceLocator.container.accountRepo
    val accountState by accountRepo.state.collectAsState()

    var data by remember { mutableStateOf<MineData?>(null) }
    var failure by remember { mutableStateOf<UiResult.Failure?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick, accountState) {
        if (accountState !is AccountState.LoggedIn) return@LaunchedEffect
        failure = null
        val playlists = repo.userPlaylists()
        val albums = repo.mineAlbums()
        val djs = repo.mineDjs()
        if (playlists is UiResult.Failure && albums is UiResult.Failure && djs is UiResult.Failure) {
            failure = playlists as UiResult.Failure
        } else {
            data = MineData(
                playlists = (playlists as? UiResult.Success)?.data.orEmpty(),
                albums = (albums as? UiResult.Success)?.data.orEmpty(),
                djs = (djs as? UiResult.Success)?.data.orEmpty()
            )
        }
    }

    ScreenScaffold {
        when {
            accountState !is AccountState.LoggedIn && failure == null && data == null -> {
                ErrorBox(
                    message = "查看个人音乐需要登录",
                    needLogin = true,
                    onLogin = { nav.navigate(Routes.LOGIN) }
                )
            }
            failure != null -> ErrorBox(
                message = failure!!.message,
                needLogin = failure!!.needLogin,
                onRetry = { tick++ },
                onLogin = { nav.navigate(Routes.LOGIN) }
            )
            data == null -> LoadingBox("正在同步我的音乐…")
            else -> {
                val mine = data!!
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item { SectionTitle("我的音乐") }

                    // ── 歌单 ──
                    item {
                        Text(
                            "歌单 · ${mine.playlists.size}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    item {
                        Button(
                            onClick = { nav.navigate(Routes.CREATE_PLAYLIST) },
                            colors = com.shijiu.wearmusic.ui.chipColors(false),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            androidx.wear.compose.material3.Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = NeteaseRed
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("新建歌单", fontSize = 12.sp)
                        }
                    }
                    items(mine.playlists.size) { i ->
                        val pl = mine.playlists[i]
                        MediaRow(
                            title = pl.name,
                            subtitle = buildString {
                                append("${pl.trackCount}首")
                                if (pl.creatorUserId == accountRepo.uid) append(" · 我创建的")
                                else append(" · ${pl.creatorNickname}")
                            },
                            coverUrl = pl.coverUrl,
                            badge = if (pl.isLikedPlaylist) "喜欢" else null,
                            onClick = { nav.navigate(Routes.playlist(pl.id)) }
                        )
                    }

                    // ── 专辑 ──
                    item {
                        Text(
                            "收藏专辑 · ${mine.albums.size}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    items(mine.albums.size) { i ->
                        val album = mine.albums[i]
                        MediaRow(
                            title = album.name,
                            subtitle = "${album.artistName} · ${album.songCount}首",
                            coverUrl = album.coverUrl,
                            onClick = { nav.navigate(Routes.album(album.id)) }
                        )
                    }

                    // ── 播客 ──
                    item {
                        Text(
                            "收藏播客 · ${mine.djs.size}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    items(mine.djs.size) { i ->
                        val dj = mine.djs[i]
                        MediaRow(
                            title = dj.name,
                            subtitle = "${dj.djNickname} · ${dj.programCount}期",
                            coverUrl = dj.coverUrl,
                            onClick = { nav.navigate(Routes.dj(dj.id)) }
                        )
                    }
                    if (mine.playlists.isEmpty() && mine.albums.isEmpty() && mine.djs.isEmpty()) {
                        item {
                            Text("这里还空空的\n去收藏一些内容吧", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
