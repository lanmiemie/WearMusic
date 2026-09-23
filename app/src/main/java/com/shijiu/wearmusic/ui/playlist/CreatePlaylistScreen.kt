package com.shijiu.wearmusic.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.login.InputField
import kotlinx.coroutines.launch

/** 新建歌单：名称 + 私密开关。 */
@Composable
fun CreatePlaylistScreen(nav: NavHostController) {
    val repo = ServiceLocator.container.musicRepo
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    ScreenScaffold {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { SectionTitle("新建歌单") }
            item { InputField(name, { name = it }, "歌单名称") }
            item {
                Text(
                    if (isPrivate) "当前：私密歌单" else "当前：公开歌单",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { isPrivate = true },
                        modifier = Modifier.height(34.dp),
                        colors = buttonColors(isPrivate)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Lock, null, Modifier.height(15.dp))
                            Text("私密", fontSize = 11.sp)
                        }}
                    Button(
                        onClick = { isPrivate = false },
                        modifier = Modifier.height(34.dp),
                        colors = buttonColors(!isPrivate)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Public, null, Modifier.height(15.dp))
                            Text("公开", fontSize = 11.sp)
                        }}
                }
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            when (val res = repo.createPlaylist(name.trim(), isPrivate)) {
                                is UiResult.Success -> {
                                    val newId = res.data
                                    if (newId > 0) {
                                        nav.navigate(Routes.playlist(newId)) {
                                            popUpTo(Routes.CREATE_PLAYLIST) { inclusive = true }
                                        }
                                    } else {
                                        message = "创建失败"
                                        busy = false
                                    }
                                }
                                is UiResult.Failure -> {
                                    message = res.message
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = name.isNotBlank() && !busy,
                    modifier = Modifier.height(40.dp),
                    colors = buttonColors(true)
                ) { Text("创建歌单", fontSize = 13.sp) }
            }
            message?.let { item { InlineNotice(it) } }
            item {
                Text(
                    "创建成功后可在歌单内添加歌曲",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
