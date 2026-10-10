package com.shijiu.wearmusic.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Text
import com.ohmusic.app.data.remote.api.PlayRecordSong
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.data.UiResult
import com.shijiu.wearmusic.data.safeApi
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.components.SongListPane
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 周/总两份榜单一起拉取（单曲「播放数据」需要同时展示本周与累计次数）。 */
internal data class RecordBundle(
    val week: List<PlayRecordSong>,
    val all: List<PlayRecordSong>
)

/**
 * 听歌排行：最近一周 / 所有时间两榜切换。
 *
 * - 每行显示网易云统计的播放次数；
 * - 云端接口没有首次播放时间，完整的单曲数据（含云端「第一次听」）
 *   走歌曲菜单的「歌曲百科」（songWiki 接口）。
 */
@Composable
fun RecordScreen(nav: NavHostController) {
    val container = ServiceLocator.container
    val accountRepo = container.accountRepo
    var tab by remember { mutableStateOf(0) } // 0=最近一周 1=所有时间

    ScreenScaffold {
        LoadScreen(
            key = "record",
            fetch = {
                if (!accountRepo.isLoggedIn) {
                    UiResult.Failure("查看听歌排行需要登录", needLogin = true)
                } else {
                    safeApi {
                        val uid = accountRepo.uid
                        coroutineScope {
                            val week = async { container.recordApi.weekRecord(uid) }
                            val all = async { container.recordApi.allRecord(uid) }
                            RecordBundle(week.await(), all.await())
                        }
                    }
                }
            },
            onLogin = { nav.navigate(Routes.LOGIN) }
        ) { bundle ->
            val records = if (tab == 0) bundle.week else bundle.all
            SongListPane(
                nav = nav,
                title = "听歌排行",
                subtitle = if (tab == 0) "最近一周 · 按播放次数排序" else "所有时间 · 播放最多的 100 首",
                songs = records.map { it.song },
                queueTag = "record",
                headerExtra = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        listOf("最近一周", "所有时间").forEachIndexed { i, label ->
                            val selected = tab == i
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (selected) NeteaseRed else Color(0xFF2B2B31),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { tab = i }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(label, fontSize = 11.sp, color = if (selected) Color.White else TextSecondary)
                            }
                        }
                    }
                },
                trailingText = { song ->
                    records.find { it.song.songId == song.songId }
                        ?.playCount?.let { "$it 次" }
                }
            )
        }
    }
}
