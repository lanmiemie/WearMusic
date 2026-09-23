package com.shijiu.wearmusic.ui.heart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.NavData
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.components.SongListPane

/**
 * 心动歌单（心动模式 / 智能推荐）。
 *
 * 种子来源优先级：NavData（播放页/歌单页入口）→ 正在播放的歌曲 →
 * 每日推荐第一首；参照歌单：入口歌单 → 第一个自建歌单。
 */
@Composable
fun HeartScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val repo = container.musicRepo
    val accountRepo = container.accountRepo
    val playback = container.playbackManager

    var seed by remember { mutableStateOf<Pair<Long, Long>?>(NavData.heartSeed) }
    var resolved by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        NavData.heartSeed = null
        val current = seed ?: playback.currentSong?.songId?.let { it to -1L }
        if (current == null) {
            // 没有任何种子：尝试拿每日推荐第一首
            val firstDaily = runCatching {
                (repo.dailySongs(null) as? com.shijiu.wearmusic.data.UiResult.Success)?.data?.firstOrNull()
            }.getOrNull()
            if (firstDaily?.songId == null) {
                resolveError = "先播放一首歌，再来开启心动模式"
                resolved = true
                return@LaunchedEffect
            }
            seed = (firstDaily.songId ?: 0L) to -1L
        }
        val currentSeed = seed!!
        if (currentSeed.second <= 0) {
            val pid = accountRepo.firstOwnPlaylistId() ?: -1L
            seed = currentSeed.first to pid
        }
        resolved = true
    }

    ScreenScaffold {
        when {
            !resolved -> LoadingBox("正在准备心动歌单…")
            resolveError != null -> ErrorBox(
                message = resolveError!!,
                onRetry = { nav.popBackStack() }
            )
            seed == null || seed!!.second <= 0 -> ErrorBox(
                message = "心动模式需要登录并至少有一个自建歌单",
                needLogin = true,
                onLogin = { nav.navigate(Routes.LOGIN) },
                onRetry = {
                    resolved = false
                    resolveError = null
                }
            )
            else -> LoadScreen(
                key = seed,
                fetch = { repo.heartSongs(seed!!.first, seed!!.second) }
            ) { songs ->
                SongListPane(
                    nav = nav,
                    title = "心动歌单",
                    subtitle = "根据红心口味智能生成",
                    songs = songs,
                    queueTag = "heart"
                )
            }
        }
    }
}
