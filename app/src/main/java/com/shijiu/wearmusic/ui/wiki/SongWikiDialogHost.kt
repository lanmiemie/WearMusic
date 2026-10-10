package com.shijiu.wearmusic.ui.wiki

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.data.safeApi
import com.shijiu.wearmusic.ui.NavData
import com.shijiu.wearmusic.ui.components.MessageDialog
import com.shijiu.wearmusic.util.TimeFmt
import kotlinx.coroutines.flow.filterNotNull

/**
 * 全局「歌曲百科」弹窗宿主：挂在 AppNavHost 根部。
 *
 * 任何歌曲菜单（列表页 / 播放页）把目标歌曲放进 NavData.songWikiSong
 * 后，这里按需请求 songWiki 接口并弹出。第一次听时间 / 累计播放次数
 * 均为网易云云端记录，比听歌排行的 /user/record 覆盖更全。
 */
@Composable
fun SongWikiDialogHost() {
    val container = ServiceLocator.container
    var detail by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 用 snapshotFlow 收集请求：不能把 NavData.songWikiSong 直接作为
        // LaunchedEffect 的 key——effect 内部将其置回 null 会立刻取消自己
        // （表现为「coroutine scope left the composition」的假失败）。
        snapshotFlow { NavData.songWikiSong }
            .filterNotNull()
            .collect { song ->
                NavData.songWikiSong = null // 立即消费，避免重复弹出
                val songId = song.songId
                if (songId == null) {
                    detail = "本地歌曲没有云端百科数据"
                    return@collect
                }
                if (!container.accountRepo.isLoggedIn) {
                    detail = "查看歌曲百科需要登录网易云账号"
                    return@collect
                }
                loading = true
                val result = safeApi { container.wikiApi.wiki(songId) }
                loading = false
                detail = when (result) {
                    is UiResult.Failure -> "百科查询失败：${result.message}"
                    is UiResult.Success -> buildWikiText(result.data)
                }
            }
    }

    MessageDialog(
        showDialog = loading || detail != null,
        title = "歌曲百科",
        message = detail ?: "正在查询歌曲百科…",
        onDismiss = {
            loading = false
            detail = null
        }
    )
}

/** 组装百科文本：第一次听 + 累计播放 + 创作信息。 */
private fun buildWikiText(info: com.ohmusic.app.data.remote.api.SongWikiInfo): String =
    buildString {
        if (info.firstListenAt != null) {
            append("第一次听 ").append(TimeFmt.date(info.firstListenAt!!))
            info.firstListenDesc?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
            append("\n\n")
        }
        append("累计播放 ").append(info.totalPlayCount?.let { "$it 次" } ?: "暂无记录").append('\n')
        if (info.credits.isNotEmpty()) {
            append("\n创作信息：\n")
            info.credits.forEach { (title, names) ->
                append("· ").append(title).append("：").append(names).append('\n')
            }
        }
    }.trim()
