package com.shijiu.wearmusic.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Send
import androidx.wear.compose.material3.Icon
import com.ohmusic.app.data.remote.api.CloudComment
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.onFailure
import com.shijiu.wearmusic.data.onSuccess
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.login.InputField
import com.shijiu.wearmusic.util.TimeFmt
import kotlinx.coroutines.launch

/**
 * 评论页：适用于歌曲(0) / 歌单(2) / 专辑(3) / 电台节目(4) / 电台(7)。
 * 支持热门/最新排序、点赞、发送评论、翻页加载。
 */
@Composable
fun CommentsScreen(nav: NavHostController, type: Int, id: Long, title: String) {
    val repo = ServiceLocator.container.musicRepo
    val accountRepo = ServiceLocator.container.accountRepo
    val scope = rememberCoroutineScope()

    var sortType by remember { mutableIntStateOf(2) } // 2 热度 / 3 时间
    var comments by remember { mutableStateOf<List<CloudComment>>(emptyList()) }
    var total by remember { mutableIntStateOf(-1) }
    var hasMore by remember { mutableStateOf(false) }
    var pageNo by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var likedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    suspend fun load(reset: Boolean) {
        if (reset) {
            loading = true
            error = null
            pageNo = 1
        } else {
            loadingMore = true
        }
        val page = pageNo
        val result = repo.comments(type, id, page, sortType)
        result.onSuccess { data ->
            comments = if (reset) data.comments else comments + data.comments
            total = data.total
            hasMore = data.hasMore
            if (data.hasMore) pageNo = page + 1
        }.onFailure { failure ->
            if (reset) {
                comments = emptyList()
                error = failure.message ?: "评论加载失败"
            } else {
                message = "加载更多失败"
            }
        }
        loading = false
        loadingMore = false
    }

    androidx.compose.runtime.LaunchedEffect(type, id, sortType) {
        load(reset = true)
    }

    fun toggleLike(comment: CloudComment) {
        if (!accountRepo.isLoggedIn) {
            message = "登录后才能点赞评论"
            return
        }
        val target = !(comment.liked || likedIds.contains(comment.commentId))
        scope.launch {
            val res = repo.likeComment(type, id, comment.commentId, target)
            if (res.isSuccess) {
                likedIds = if (target) likedIds + comment.commentId else likedIds - comment.commentId
            } else {
                message = if (target) "点赞失败" else "取消点赞失败"
            }
        }
    }

    fun sendComment() {
        val content = inputText.trim()
        if (content.isEmpty() || sending) return
        if (!accountRepo.isLoggedIn) {
            message = "登录后才能发表评论"
            return
        }
        sending = true
        scope.launch {
            val res = repo.sendComment(type, id, content)
            message = if (res.isSuccess) {
                inputText = ""
                showInput = false
                load(reset = true)
                "评论已发布"
            } else {
                "评论发送失败"
            }
            sending = false
        }
    }

    ScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            when {
                loading -> ErrorBoxFree()
                error != null -> ErrorBox(
                    message = error!!,
                    needLogin = false,
                    onRetry = { scope.launch { load(reset = true) } }
                )
                else -> {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 40.dp, bottom = 52.dp, start = 10.dp, end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (total >= 0) "共 ${TimeFmt.count(total.toLong())} 条评论" else "评论区",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { sortType = 2 },
                                    modifier = Modifier.height(30.dp),
                                    colors = buttonColors(sortType == 2)
                                ) { Text("热门", fontSize = 11.sp) }
                                Button(
                                    onClick = { sortType = 3 },
                                    modifier = Modifier.height(30.dp),
                                    colors = buttonColors(sortType == 3)
                                ) { Text("最新", fontSize = 11.sp) }
                                Button(
                                    onClick = {
                                        if (accountRepo.isLoggedIn) showInput = true
                                        else message = "登录后才能发表评论"
                                    },
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Filled.Send, null, Modifier.size(13.dp))
                                        Text("写评论", fontSize = 11.sp)
                                    }}
                            }
                        }
                        message?.let { item { InlineNotice(it) } }
                        if (comments.isEmpty()) {
                            item {
                                Text(
                                    "还没有评论，来抢沙发",
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 20.dp)
                                )
                            }
                        }
                        items(comments.size) { i ->
                            val comment = comments[i]
                            CommentRow(
                                comment = comment,
                                likedOverride = likedIds.contains(comment.commentId),
                                onLike = { toggleLike(comment) }
                            )
                        }
                        if (hasMore) {
                            item {
                                Button(
                                    onClick = { if (!loadingMore) scope.launch { load(reset = false) } },
                                    modifier = Modifier.height(34.dp),
                                    colors = buttonColors(false)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (loadingMore) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Text("加载更多", fontSize = 12.sp)
                                    }}
                            }
                        } else if (comments.isNotEmpty()) {
                            item {
                                Text("没有更多了", fontSize = 11.sp, color = Color(0xFF77777F))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInput) {
        Dialog(
            onDismissRequest = { showInput = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Text("发表评论", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    InputField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        hint = "说点什么…"
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showInput = false },
                            modifier = Modifier.height(34.dp),
                            colors = buttonColors(false)
                        ) { Text("取消", fontSize = 12.sp) }
                        Button(
                            onClick = { sendComment() },
                            modifier = Modifier.height(34.dp),
                            colors = buttonColors(true)
                        ) {
                            if (sending) CircularProgressIndicator(modifier = Modifier.size(14.dp))
                            else Text("发送", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBoxFree() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 单条评论：头像 + 昵称/时间 + 内容 + 点赞。 */
@Composable
private fun CommentRow(
    comment: CloudComment,
    likedOverride: Boolean,
    onLike: () -> Unit
) {
    val liked = comment.liked || likedOverride
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF26262B), RoundedCornerShape(12.dp))
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        CoverImage(comment.avatarUrl.ifBlank { null }, 30.dp, corner = 15.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.nickname.ifBlank { "云村用户" },
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    TimeFmt.dateTime(comment.time),
                    fontSize = 9.sp,
                    color = Color(0xFF77777F)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                comment.content,
                fontSize = 12.sp,
                color = Color.White
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.wear.compose.material3.Button(
                onClick = onLike,
                modifier = Modifier.size(40.dp),
                colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                )
            ) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "点赞",
                    modifier = Modifier.size(20.dp),
                    tint = if (liked) NeteaseRed else TextSecondary
                )
            }
            if (comment.likedCount > 0) {
                Text(TimeFmt.count(comment.likedCount.toLong()), fontSize = 10.sp, color = TextSecondary)
            }
        }
    }
}
