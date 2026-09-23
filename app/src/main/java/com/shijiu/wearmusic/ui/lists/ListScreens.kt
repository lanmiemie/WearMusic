package com.shijiu.wearmusic.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import com.ohmusic.app.data.remote.api.CloudPlaylist
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.components.MediaRow
import com.shijiu.wearmusic.util.TimeFmt

/** 歌单卡片流（雷达 / 推荐歌单共用）。 */
@Composable
internal fun PlaylistCardList(
    title: String,
    subtitle: String,
    playlists: List<CloudPlaylist>,
    nav: NavHostController
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { SectionTitle(title) }
        item { Text(subtitle, fontSize = 11.sp, color = TextSecondary) }
        item { Spacer(Modifier.height(2.dp)) }
        items(playlists.size) { i ->
            val pl = playlists[i]
            MediaRow(
                title = pl.name,
                subtitle = buildString {
                    append("${pl.trackCount}首")
                    if (pl.playCount > 0) append(" · ${TimeFmt.count(pl.playCount)}次播放")
                    if (pl.creatorNickname.isNotBlank()) append(" · ${pl.creatorNickname}")
                },
                coverUrl = pl.coverUrl,
                badge = if (pl.isLikedPlaylist) "喜欢" else null,
                onClick = { nav.navigate(Routes.playlist(pl.id)) }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 雷达歌单（需登录）。 */
@Composable
fun RadarScreen(nav: NavHostController) {
    val accountRepo = ServiceLocator.container.accountRepo
    val repo = ServiceLocator.container.musicRepo
    val loggedIn by accountRepo.state.collectAsState()

    ScreenScaffold {
        if (loggedIn !is com.shijiu.wearmusic.data.AccountState.LoggedIn) {
            com.shijiu.wearmusic.ui.ErrorBox(
                message = "雷达歌单需要登录网易云账号",
                needLogin = true,
                onLogin = { nav.navigate(Routes.LOGIN) }
            )
        } else {
            LoadScreen(
                key = "radar",
                fetch = { repo.radarPlaylists() },
                onLogin = { nav.navigate(Routes.LOGIN) }
            ) { playlists ->
                PlaylistCardList("雷达歌单", "为你定制的个性电台波束", playlists, nav)
            }
        }
    }
}

/** 推荐歌单（匿名可用）。 */
@Composable
fun PersonalizedScreen(nav: NavHostController) {
    val repo = ServiceLocator.container.musicRepo
    ScreenScaffold {
        LoadScreen(
            key = "personalized",
            fetch = { repo.personalizedPlaylists() }
        ) { playlists ->
            PlaylistCardList("推荐歌单", "根据全网热度为你精选", playlists, nav)
        }
    }
}

/** 排行榜入口。 */
@Composable
fun ToplistScreen(nav: NavHostController) {
    val repo = ServiceLocator.container.musicRepo
    ScreenScaffold {
        LoadScreen(
            key = "toplist",
            fetch = { repo.toplists() }
        ) { toplists ->
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item { SectionTitle("排行榜") }
                item { Text("官方权威榜单 · 持续更新", fontSize = 11.sp, color = TextSecondary) }
                item { Spacer(Modifier.height(2.dp)) }
                items(toplists.size) { i ->
                    val toplist = toplists[i]
                    MediaRow(
                        title = toplist.name,
                        subtitle = buildString {
                            append(toplist.updateFrequency)
                            if (toplist.trackCount > 0) append(" · ${toplist.trackCount}首")
                        },
                        coverUrl = toplist.coverUrl,
                        onClick = { nav.navigate(Routes.playlist(toplist.id)) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
