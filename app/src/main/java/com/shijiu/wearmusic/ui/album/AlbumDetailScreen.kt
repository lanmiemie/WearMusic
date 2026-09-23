package com.shijiu.wearmusic.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ThumbUp
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.components.SongListPane
import com.shijiu.wearmusic.util.TimeFmt
import kotlinx.coroutines.launch

/** 专辑详情：曲目、收藏、评论。 */
@Composable
fun AlbumDetailScreen(nav: NavHostController, albumId: Long) {
    val repo = ServiceLocator.container.musicRepo
    val accountRepo = ServiceLocator.container.accountRepo
    val scope = rememberCoroutineScope()
    var subscribed by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    ScreenScaffold {
        LoadScreen(
            key = albumId,
            fetch = { repo.albumDetail(albumId) }
        ) { detail ->
            val album = detail.album
            LaunchedEffect(album.id) {
                if (subscribed == null) subscribed = album.subscribed
            }
            // 点击封面：弹出专辑详情（简介 / 发行信息）
            if (showDetail) {
                Dialog(
                    onDismissRequest = { showDetail = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .background(Color(0xFF1C1C22), RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 12.dp)
                        ) {
                            CoverImage(album.coverUrl, 56.dp, corner = 8.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                album.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                            Text(album.artistName, fontSize = 11.sp, color = TextSecondary)
                            if (album.publishTimeMs > 0 || album.company.isNotBlank()) {
                                Text(
                                    buildString {
                                        if (album.publishTimeMs > 0) {
                                            append("发布于 ${TimeFmt.date(album.publishTimeMs)}")
                                        }
                                        if (album.company.isNotBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(album.company)
                                        }
                                    },
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                detail.description.ifBlank { "暂无专辑简介" },
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = Color(0xFFDDDDDE),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 170.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { showDetail = false },
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("关闭", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            SongListPane(
                nav = nav,
                title = album.name,
                subtitle = buildString {
                    append(album.artistName)
                    if (album.songCount > 0) append(" · ${album.songCount}首")
                    if (album.publishTimeMs > 0) append("\n发布于 ${TimeFmt.date(album.publishTimeMs)}")
                    if (album.company.isNotBlank()) append(" · ${album.company}")
                },
                coverUrl = album.coverUrl,
                songs = detail.songs,
                queueTag = "album:$albumId",
                onCoverClick = { showDetail = true },
                headerExtra = {
                    InlineNotice(message)
                    Row {
                        Button(
                            onClick = {
                                if (!accountRepo.isLoggedIn) {
                                    message = "登录后才能收藏专辑"
                                    return@Button
                                }
                                scope.launch {
                                    val target = !(subscribed ?: false)
                                    val res = repo.subscribeAlbum(albumId, target)
                                    message = if (res.isSuccess) {
                                        subscribed = target
                                        if (target) "已收藏专辑" else "已取消收藏"
                                    } else "操作失败"
                                }
                            },
                            modifier = Modifier.height(34.dp).padding(horizontal = 3.dp),
                            colors = buttonColors(subscribed == true)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Filled.ThumbUp, null, Modifier.size(15.dp))
                                Text(if (subscribed == true) "已收藏" else "收藏", fontSize = 11.sp)
                            }}
                        Button(
                            onClick = {
                                nav.navigate(Routes.comments(3, albumId, album.name))
                            },
                            modifier = Modifier.height(34.dp).padding(horizontal = 3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Filled.ChatBubble, null, Modifier.size(15.dp))
                                Text("评论", fontSize = 11.sp)
                            }}
                    }
                },
                extraMenuActions = { song ->
                    buildList {
                        song.songId?.let { id ->
                            add(
                                com.shijiu.wearmusic.ui.components.MenuItem(
                                    Icons.Filled.ChatBubble, "歌曲评论"
                                ) {
                                    nav.navigate(Routes.comments(0, id, song.title))
                                }
                            )
                        }
                    }
                }
            )
        }
    }
}
