package com.shijiu.wearmusic.ui.dj

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.ThumbUp
import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.api.DjProgram
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.ConfirmDialog
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.components.MenuItem
import com.shijiu.wearmusic.ui.components.MenuDialog
import com.shijiu.wearmusic.ui.components.SongRow
import com.shijiu.wearmusic.util.TimeFmt
import kotlinx.coroutines.launch

/** 播客详情：节目列表、订阅、节目与电台评论。 */
@Composable
fun DjDetailScreen(nav: NavHostController, djId: Long) {
    val repo = ServiceLocator.container.musicRepo
    val playback = ServiceLocator.container.playbackManager
    val accountRepo = ServiceLocator.container.accountRepo
    val scope = rememberCoroutineScope()

    var subscribed by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var menuProgram by remember { mutableStateOf<DjProgram?>(null) }
    var confirmUnsub by remember { mutableStateOf(false) }

    val queue by playback.queue.collectAsState()
    val currentIndex by playback.currentIndex.collectAsState()
    val currentSongId = queue.getOrNull(currentIndex)?.songId

    ScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            LoadScreen(
                key = "dj-$djId",
                fetch = { repo.djDetail(djId) }
            ) { dj ->
                LaunchedEffect(dj.id) {
                    if (subscribed == null) {
                        // dj/detail 匿名响应不含订阅态，仅在登录态下展示为未订阅
                        subscribed = false
                    }
                }
                LoadScreen(
                    key = "dj-programs-$djId",
                    fetch = { repo.djPrograms(djId, 0) }
                ) { programs ->
                    DjContent(
                        nav = nav,
                        djId = djId,
                        djName = dj.name,
                        djNick = dj.djNickname,
                        category = dj.category,
                        programCount = dj.programCount,
                        coverUrl = dj.coverUrl,
                        programs = programs,
                        currentSongId = currentSongId,
                        subscribed = subscribed,
                        message = message,
                        onSubscribe = {
                            if (!accountRepo.isLoggedIn) {
                                message = "登录后才能订阅播客"
                                return@DjContent
                            }
                            if (subscribed == true) {
                                confirmUnsub = true
                                return@DjContent
                            }
                            scope.launch {
                                val res = repo.subscribeDj(djId, true)
                                message = if (res.isSuccess) {
                                    subscribed = true
                                    "已订阅"
                                } else "订阅失败"
                            }
                        },
                        onPlayProgram = { index, list ->
                            playback.playQueue(list, index, "dj:$djId")
                        },
                        onProgramMenu = { menuProgram = it }
                    )
                }
            }
        }
    }

    menuProgram?.let { program ->
        MenuDialog(
            showDialog = true,
            title = program.name,
            onDismiss = { menuProgram = null },
            items = buildList {
                program.song?.songId?.let { id ->
                    add(MenuItem(Icons.Filled.Headphones, "播放本期节目") {
                        // 单期独立队列
                        playback.playQueue(listOf(program.song!!), 0, "dj:$djId")
                    })
                    add(MenuItem(Icons.Filled.ChatBubble, "本期节目评论") {
                        nav.navigate(Routes.comments(4, id, program.name))
                    })
                }
                program.song?.songId?.let { id ->
                    add(MenuItem(Icons.Filled.ChatBubble, "歌曲评论") {
                        nav.navigate(Routes.comments(0, id, program.song!!.title))
                    })
                }
            }
        )
    }

    ConfirmDialog(
        showDialog = confirmUnsub,
        title = "取消订阅",
        message = "确定取消订阅该播客？",
        confirmText = "取消订阅",
        danger = true,
        onConfirm = {
            scope.launch {
                val res = repo.subscribeDj(djId, false)
                message = if (res.isSuccess) {
                    subscribed = false
                    "已取消订阅"
                } else "操作失败"
            }
        },
        onDismiss = { confirmUnsub = false }
    )
}

@Composable
private fun DjContent(
    nav: NavHostController,
    djId: Long,
    djName: String,
    djNick: String,
    category: String,
    programCount: Int,
    coverUrl: String?,
    programs: List<DjProgram>,
    currentSongId: Long?,
    subscribed: Boolean?,
    message: String?,
    onSubscribe: () -> Unit,
    onPlayProgram: (Int, List<Song>) -> Unit,
    onProgramMenu: (DjProgram) -> Unit
) {
    val playList = programs.mapNotNull { it.song }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CoverImage(coverUrl, 84.dp, corner = 10.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    djName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
                Text(
                    buildString {
                        append(djNick)
                        if (category.isNotBlank()) append(" · $category")
                        append(" · ${programCount}期")
                    },
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onSubscribe,
                    modifier = Modifier.height(34.dp),
                    colors = buttonColors(subscribed == true)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Filled.ThumbUp, null, Modifier.size(15.dp))
                        Text(if (subscribed == true) "已订阅" else "订阅", fontSize = 11.sp)
                    }}
                Button(
                    onClick = { nav.navigate(Routes.comments(7, djId, djName)) },
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Filled.ChatBubble, null, Modifier.size(15.dp))
                        Text("电台评论", fontSize = 11.sp)
                    }}
            }
        }
        message?.let { item { InlineNotice(it) } }
        item {
            Text("节目列表", fontSize = 12.sp, color = TextSecondary)
        }
        val songIndexMap = HashMap<Long, Int>()
        programs.forEachIndexed { idx, p ->
            p.song?.songId?.let { sid -> if (!songIndexMap.containsKey(sid)) songIndexMap[sid] = idx }
        }
        items(programs.size) { i ->
            val program = programs[i]
            val song = program.song
            if (song != null) {
                SongRow(
                    song = song,
                    isCurrent = currentSongId != null && currentSongId == song.songId,
                    indexLabel = "${i + 1}",
                    onPlay = {
                        onPlayProgram(songIndexMap[song.songId] ?: 0, playList)
                    },
                    onMenu = { onProgramMenu(program) }
                )
            } else {
                Text(
                    "${i + 1}. ${program.name}（暂无音源）",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
