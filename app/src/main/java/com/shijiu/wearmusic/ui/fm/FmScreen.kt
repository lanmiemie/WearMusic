package com.shijiu.wearmusic.ui.fm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.SongListPane
import com.shijiu.wearmusic.ui.components.deleteMenuItem

/** 私人漫游（私人 FM）：无限续播，可把不喜欢的歌扔进垃圾桶。 */
@Composable
fun FmScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val repo = container.musicRepo
    val playback = container.playbackManager
    val fmApi = container.fmApi

    ScreenScaffold {
        LoadScreen(
            key = "fm",
            fetch = { repo.fmSongs() }
        ) { songs ->
            SongListPane(
                nav = nav,
                title = "私人漫游",
                subtitle = "根据口味无限续播",
                songs = songs,
                queueTag = "fm",
                tailLoader = { repo.fmSongsRaw() },
                headerExtra = {
                    Button(
                        onClick = {
                            playback.playQueue(songs, 0, "fm") { repo.fmSongsRaw() }
                            nav.navigate(Routes.PLAYER)
                        },
                        modifier = Modifier.height(36.dp),
                        colors = buttonColors(true)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("立即开始漫游", fontSize = 12.sp)
                        }}
                },
                extraMenuActions = { song ->
                    song.songId?.let { id ->
                        listOf(
                            deleteMenuItem("不感兴趣（扔进垃圾桶）") {
                                Thread {
                                    kotlinx.coroutines.runBlocking { fmApi.trashSong(id) }
                                }.start()
                            }
                        )
                    } ?: emptyList()
                }
            )
        }
    }
}
