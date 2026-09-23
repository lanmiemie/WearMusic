package com.shijiu.wearmusic.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Whatshot
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.TextSecondary

private data class HomeEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val route: String
)

@Composable
fun HomeScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val playback = container.playbackManager
    val accountRepo = container.accountRepo

    val accountState by accountRepo.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val currentIndex by playback.currentIndex.collectAsState()
    val isPlaying by playback.isPlaying.collectAsState()
    val current = queue.getOrNull(currentIndex)

    val entries = remember {
        listOf(
            HomeEntry(Icons.Filled.DateRange, "每日推荐", "今日日推 · 历史回看", Routes.DAILY),
            HomeEntry(Icons.Filled.Radio, "私人漫游", "私人 FM 无限续播", Routes.FM),
            HomeEntry(Icons.Filled.Whatshot, "心动歌单", "基于心动的智能推荐", Routes.HEART),
            HomeEntry(Icons.Filled.TrackChanges, "雷达歌单", "私人 / 心情雷达", Routes.RADAR),
            HomeEntry(Icons.Filled.QueueMusic, "推荐歌单", "为你精选的歌单", Routes.PERSONALIZED),
            HomeEntry(Icons.Filled.BarChart, "排行榜", "官方权威榜单", Routes.TOPLIST),
            HomeEntry(Icons.Filled.Search, "搜索", "单曲 歌单 歌手 专辑 播客", Routes.SEARCH),
            HomeEntry(Icons.Filled.CloudQueue, "音乐云盘", "我上传的云盘歌曲", Routes.CLOUD),
            HomeEntry(Icons.Filled.LibraryMusic, "我的音乐", "歌单 · 专辑 · 播客", Routes.MINE)
        )
    }

    ScreenScaffold {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 44.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("WearMusic", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("腕上网易云音乐", fontSize = 11.sp, color = TextSecondary)
                }
            }
            if (current != null) {
                item {
                    Button(
                        onClick = { nav.navigate(Routes.PLAYER) },
                        colors = chipColors(selected = true),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                current.title,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (isPlaying) "正在播放" else "已暂停",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            entries.forEach { entry ->
                item {
                    Button(
                        onClick = { nav.navigate(entry.route) },
                        colors = chipColors(false),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(entry.icon, contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(entry.title, fontSize = 13.sp)
                            Text(entry.subtitle, fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
            }
            item {
                val accountTitle = when (accountState) {
                    is com.shijiu.wearmusic.data.AccountState.LoggedIn ->
                        "账号 · " + (accountState as com.shijiu.wearmusic.data.AccountState.LoggedIn).account.nickname
                    is com.shijiu.wearmusic.data.AccountState.Guest -> "游客模式 · 去登录"
                    else -> "未登录 · 去登录"
                }
                Button(
                    onClick = { nav.navigate(Routes.ACCOUNT) },
                    colors = chipColors(false),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(accountTitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
