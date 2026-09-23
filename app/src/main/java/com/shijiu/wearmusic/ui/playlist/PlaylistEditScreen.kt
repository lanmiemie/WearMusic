package com.shijiu.wearmusic.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.ui.ErrorBox
import com.shijiu.wearmusic.ui.LoadingBox
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.SectionTitle
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.buttonColors
import com.shijiu.wearmusic.ui.chipColors
import com.shijiu.wearmusic.ui.components.ConfirmDialog
import com.shijiu.wearmusic.ui.components.InlineNotice
import com.shijiu.wearmusic.ui.login.InputField
import kotlinx.coroutines.launch

/**
 * 编辑歌单（仅创建者可用）：改名/简介、隐私设置、删除歌单。
 */
@Composable
fun PlaylistEditScreen(nav: NavHostController, playlistId: Long) {
    val repo = ServiceLocator.container.musicRepo
    val scope = rememberCoroutineScope()

    var result by remember { mutableStateOf<UiResult<com.ohmusic.app.data.remote.api.CloudPlaylistDetail>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPrivacy by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(playlistId, tick) {
        val r = repo.playlistDetail(playlistId)
        result = r
        if (r is UiResult.Success && !loadedOnce) {
            name = r.data.playlist.name
            desc = r.data.playlist.description
            loadedOnce = true
        }
    }

    fun saveMeta() {
        scope.launch {
            busy = true
            val res = repo.updatePlaylistMeta(playlistId, name.trim(), desc.trim())
            message = if (res.isSuccess) "已保存" else res.let { (it as UiResult.Failure).message }
            busy = false
        }
    }

    ScreenScaffold {
        when (val r = result) {
            null -> LoadingBox()
            is UiResult.Failure -> ErrorBox(message = r.message, onRetry = { tick++ })
            is UiResult.Success -> {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 44.dp, bottom = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item { SectionTitle("编辑歌单") }
                    item {
                        InputField(name, { name = it }, "歌单名称")
                    }
                    item {
                        InputField(desc, { desc = it }, "简介（可选）")
                    }
                    item {
                        Button(
                            onClick = ::saveMeta,
                            enabled = name.isNotBlank() && !busy,
                            modifier = Modifier.height(38.dp),
                            colors = buttonColors(true)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Filled.Save, null, Modifier.height(16.dp))
                                Text("保存名称与简介", fontSize = 12.sp)
                            }}
                    }

                    item { SectionTitle("隐私设置") }
                    item {
                        Text(
                            "私密歌单仅自己可见",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { confirmPrivacy = true },
                                modifier = Modifier.height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Filled.Lock, null, Modifier.height(15.dp))
                                    Text("设为私密", fontSize = 11.sp)
                                }}
                            Button(
                                onClick = { confirmPrivacy = false },
                                modifier = Modifier.height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Filled.Public, null, Modifier.height(15.dp))
                                    Text("设为公开", fontSize = 11.sp)
                                }}
                        }
                    }

                    item { SectionTitle("危险操作") }
                    item {
                        Button(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.height(34.dp),
                            colors = buttonColors(false)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Filled.Delete, null, Modifier.height(15.dp), tint = NeteaseRed)
                                Text("删除整个歌单", fontSize = 11.sp, color = NeteaseRed)
                            }}
                    }
                    message?.let { item { InlineNotice(it) } }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    ConfirmDialog(
        showDialog = confirmPrivacy != null,
        title = if (confirmPrivacy == true) "设为私密歌单" else "设为公开歌单",
        message = if (confirmPrivacy == true) "确认将歌单设为仅自己可见？" else "确认将歌单公开？",
        onConfirm = {
            scope.launch {
                val target = confirmPrivacy == true
                val res = repo.setPlaylistPrivacy(playlistId, target)
                message = if (res.isSuccess) {
                    if (target) "已设为私密" else "已设为公开"
                } else (res as UiResult.Failure).message
            }
        },
        onDismiss = { confirmPrivacy = null }
    )

    ConfirmDialog(
        showDialog = confirmDelete,
        title = "删除歌单",
        message = "删除后歌单与曲目列表不可恢复，确定删除？",
        confirmText = "删除",
        danger = true,
        onConfirm = {
            scope.launch {
                val res = repo.deletePlaylist(playlistId)
                if (res.isSuccess) {
                    nav.popBackStack()
                } else {
                    message = (res as UiResult.Failure).message
                }
            }
        },
        onDismiss = { confirmDelete = false }
    )
}
