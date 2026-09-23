package com.shijiu.wearmusic.ui.daily

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Text
import com.shijiu.wearmusic.ServiceLocator
import com.shijiu.wearmusic.ui.LoadScreen
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.Routes
import com.shijiu.wearmusic.ui.ScreenScaffold
import com.shijiu.wearmusic.ui.TextSecondary
import com.shijiu.wearmusic.ui.components.SongListPane
import com.shijiu.wearmusic.util.TimeFmt

/** 每日推荐：支持最近 14 天历史日推回看。 */
@Composable
fun DailyScreen(nav: NavHostController) {
    val repo = ServiceLocator.container.musicRepo
    val dates = remember { TimeFmt.recentDates(14) }
    var selected by remember { mutableStateOf(dates.first()) }
    val isToday = selected == dates.first()

    ScreenScaffold {
        LoadScreen(
            key = selected,
            fetch = { repo.dailySongs(if (isToday) null else selected) },
            onLogin = { nav.navigate(Routes.LOGIN) }
        ) { songs ->
            SongListPane(
                nav = nav,
                title = if (isToday) "每日推荐" else "历史日推",
                subtitle = if (isToday) "根据你的口味每日生成" else selected,
                songs = songs,
                queueTag = "daily",
                headerExtra = {
                    // 日期选择条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dates.forEach { date ->
                            val selectedDate = date == selected
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (selectedDate) NeteaseRed else Color(0xFF2B2B31),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { selected = date }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    if (date == dates.first()) "今天" else date.substring(5),
                                    fontSize = 11.sp,
                                    color = if (selectedDate) Color.White else TextSecondary
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}
