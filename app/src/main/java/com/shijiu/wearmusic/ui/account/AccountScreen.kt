package com.shijiu.wearmusic.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Replay
import coil.imageLoader
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.AccountState
import com.shijiu.wearmusic.data.AudioQuality
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.CoverImage
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音质档位 → 图标。 */
private fun qualityIcon(quality: AudioQuality) = when (quality) {
    AudioQuality.STANDARD -> Icons.Filled.GraphicEq
    AudioQuality.HIGHER -> Icons.Filled.MusicNote
    AudioQuality.EXHIGH -> Icons.Filled.HighQuality
    AudioQuality.LOSSLESS -> Icons.Filled.Album
}

@Composable
fun AccountScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val accountRepo = container.accountRepo
    val musicRepo = container.musicRepo
    val playback = container.playbackManager
    val prefs = container.prefs
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by accountRepo.state.collectAsState()
    var bitrate by remember { mutableStateOf(prefs.bitrate) }
    var showLogout by remember { mutableStateOf(false) }
    var showClearCache by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    ScreenScaffold {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 44.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                AccountState.Loading -> {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("正在获取账号…", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
                is AccountState.LoggedIn -> {
                    val account = (state as AccountState.LoggedIn).account
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (account.avatarUrl.isNotBlank()) {
                                CoverImage(account.avatarUrl, 48.dp, corner = 24.dp)
                            } else {
                                Icon(
                                    Icons.Filled.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = NeteaseRed
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(account.nickname, fontSize = 16.sp, color = Color.White)
                            Text(
                                buildString {
                                    append("UID ${account.userId}")
                                    if (account.isVip) append(" · 黑胶 VIP")
                                },
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
                is AccountState.Guest -> {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("游客模式", fontSize = 16.sp, color = Color.White)
                            Text(
                                "可浏览 / 搜索 / 试听\n登录后解锁完整功能",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { nav.navigate(Routes.LOGIN) },
                            colors = chipColors(true),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("登录网易云账号", fontSize = 13.sp)
                        }
                    }
                }
                is AccountState.LoggedOut -> {
                    item {
                        Text("未登录", fontSize = 16.sp, color = Color.White)
                    }
                    item {
                        Button(
                            onClick = { nav.navigate(Routes.LOGIN) },
                            colors = chipColors(true),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("去登录", fontSize = 13.sp)
                        }
                    }
                    item {
                        Button(
                            onClick = { scope.launch { accountRepo.loginGuest() } },
                            modifier = Modifier.height(34.dp)
                        ) { Text("游客模式", fontSize = 12.sp) }
                    }
                }
            }

            item { SectionTitle("音质") }
            // 按账号等级展现音质档位：无损仅对黑胶 VIP 展现
            val qualities = run {
                val visible = AudioQuality.entries.filter { !it.requireVip || accountRepo.isVip }
                val current = AudioQuality.fromBr(bitrate)
                // 存储档位超出当前等级（如会员已过期）时仍展现该行，便于切换回来
                if (current.requireVip && !visible.contains(current)) visible + current else visible
            }
            qualities.forEach { quality ->
                item {
                    Button(
                        onClick = {
                            prefs.bitrate = quality.br
                            bitrate = quality.br
                            // 清直链缓存：后续切歌立即按新音质解析，当前曲目不受影响
                            playback.clearUrlCache()
                        },
                        colors = chipColors(bitrate == quality.br),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(qualityIcon(quality), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(quality.label, fontSize = 12.sp, color = Color.White)
                                if (quality.requireVip) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "VIP",
                                        fontSize = 8.sp,
                                        color = NeteaseRed,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(quality.detail, fontSize = 9.sp, color = TextSecondary)
                        }
                    }
                }
            }
            if (!accountRepo.isVip) {
                item {
                    Text(
                        "无损音质需开通黑胶 VIP",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }

            if (state is AccountState.LoggedIn || state is AccountState.Guest) {
                item { SectionTitle("会话") }
                item {
                    Button(
                        onClick = { scope.launch { accountRepo.refresh() } },
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Replay, null, Modifier.size(16.dp))
                            Text("刷新账号状态", fontSize = 12.sp)
                        }}
                }
                item {
                    Button(
                        onClick = { showLogout = true },
                        modifier = Modifier.height(34.dp),
                        colors = buttonColors(false)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Logout, null, Modifier.size(16.dp), tint = NeteaseRed)
                            Text("退出登录", fontSize = 12.sp, color = NeteaseRed)
                        }}
                }
            }

            item { SectionTitle("其他") }
            item { com.shijiu.wearmusic.ui.components.InlineNotice(message) }
            item {
                Button(
                    onClick = { showClearCache = true },
                    modifier = Modifier.height(34.dp),
                    colors = buttonColors(false)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Filled.CleaningServices, null, Modifier.size(16.dp), tint = TextSecondary)
                        Text("清理缓存", fontSize = 12.sp, color = Color.White)
                    }}
            }
            item {
                Button(
                    onClick = { nav.navigate(Routes.ABOUT) },
                    modifier = Modifier.height(34.dp),
                    colors = buttonColors(false)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = TextSecondary)
                        Text("关于 WearMusic", fontSize = 12.sp, color = Color.White)
                    }}
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    ConfirmDialog(
        showDialog = showClearCache,
        title = "清理缓存",
        message = "将清除已缓存的图片、歌词与播放链接，下次浏览/播放会重新加载",
        confirmText = "清理",
        onConfirm = {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    // 图片缓存：内存 + 磁盘
                    context.imageLoader.memoryCache?.clear()
                    context.imageLoader.diskCache?.clear()
                }
                // 歌词缓存 + 播放直链缓存
                musicRepo.clearLyricCache()
                playback.clearUrlCache()
                withContext(Dispatchers.Main) { message = "缓存已清理" }
            }
        },
        onDismiss = { showClearCache = false }
    )

    ConfirmDialog(
        showDialog = showLogout,
        title = "退出登录",
        message = "将清除本机保存的登录凭据",
        confirmText = "退出",
        danger = true,
        onConfirm = {
            scope.launch { accountRepo.logout() }
        },
        onDismiss = { showLogout = false }
    )
}
