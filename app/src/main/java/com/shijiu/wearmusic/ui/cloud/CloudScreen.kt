package com.shijiu.wearmusic.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PlayArrow
import com.ohmusic.app.data.remote.api.CloudDriveSong
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.components.MediaRow
import com.shijiu.wearmusic.ui.components.MenuDialog
import com.shijiu.wearmusic.ui.components.MenuItem
import com.shijiu.wearmusic.ui.components.ConfirmDialog
import com.shijiu.wearmusic.ui.components.deleteMenuItem
import com.shijiu.wearmusic.util.TimeFmt
import kotlinx.coroutines.launch

/** 音乐云盘：查看 / 播放 / 删除自己上传的歌曲。 */
@Composable
fun CloudScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val repo = container.musicRepo
    val playback = container.playbackManager
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<UiResult<Pair<List<CloudDriveSong>, Int>>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var menuSong by remember { mutableStateOf<CloudDriveSong?>(null) }
    var deleteTarget by remember { mutableStateOf<CloudDriveSong?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) { result = repo.cloudDrive() }

    ScreenScaffold {
        when (val r = result) {
            null -> LoadingBox("正在读取云盘…")
            is UiResult.Failure -> ErrorBox(
                message = r.message,
                needLogin = r.needLogin,
                onRetry = { tick++ },
                onLogin = { nav.navigate(Routes.LOGIN) }
            )
            is UiResult.Success -> {
                val (items, total) = r.data
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item { SectionTitle("音乐云盘") }
                    item {
                        Text(
                            "共 $total 首歌曲" + if (message != null) " · $message" else "",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    item { Spacer(Modifier.height(2.dp)) }
                    items(items.size) { i ->
                        val item = items[i]
                        MediaRow(
                            title = item.song.title,
                            subtitle = buildString {
                                append(item.song.artist)
                                if (item.fileSize > 0) append(" · ${TimeFmt.fileSize(item.fileSize)}")
                            },
                            coverUrl = item.song.smallCoverUrl ?: item.song.coverUrl,
                            badge = if (!item.matched) "未匹配" else null,
                            onClick = {
                                playback.playQueue(
                                    items.map { it.song }, i, "cloud"
                                )
                            },
                            onMenu = { menuSong = item }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    menuSong?.let { target ->
        MenuDialog(
            showDialog = true,
            title = target.song.title,
            onDismiss = { menuSong = null },
            items = buildList {
                add(
                    MenuItem(
                        Icons.Filled.PlayArrow,
                        "播放"
                    ) {
                        val all = (result as? UiResult.Success)?.data?.first.orEmpty()
                        val idx = all.indexOfFirst { it.cloudSongId == target.cloudSongId }
                        playback.playQueue(all.map { it.song }, idx.coerceAtLeast(0), "cloud")
                    }
                )
                if (target.matched) {
                    target.song.songId?.let { id ->
                        add(
                            MenuItem(
                                Icons.Filled.ChatBubble,
                                "歌曲评论"
                            ) {
                                nav.navigate(Routes.comments(0, id, target.song.title))
                            }
                        )
                    }
                }
                add(deleteMenuItem("从云盘删除") { deleteTarget = target })
            }
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            showDialog = true,
            title = "删除云盘文件",
            message = "「${target.song.title}」将从云盘移除，且不可恢复",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                scope.launch {
                    val res = repo.deleteCloudSong(target.cloudSongId)
                    message = if (res.isSuccess) "已删除" else "删除失败"
                    tick++
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }
}
