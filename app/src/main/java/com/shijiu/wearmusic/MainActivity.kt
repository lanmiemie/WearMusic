package com.shijiu.wearmusic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.Text
import com.shijiu.wearmusic.data.AccountState
import com.shijiu.wearmusic.ui.AppNavHost
import com.shijiu.wearmusic.ui.CardBg
import com.shijiu.wearmusic.ui.NeteaseRed
import com.shijiu.wearmusic.ui.TextSecondary

/** 网易云红的 dim 变体（选中按压 / 低强调场景）。 */
private val NeteaseRedDim = Color(0xFFB0272E)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        setContent {
            // Material 3 Expressive：品牌色注入 + expressive 动效方案（弹性过渡、进出场曲线）。
            MaterialTheme(
                colorScheme = ColorScheme(
                    primary = NeteaseRed,
                    primaryDim = NeteaseRedDim,
                    primaryContainer = NeteaseRed,
                    onPrimary = Color.White,
                    onPrimaryContainer = Color.White,
                    background = Color.Black,
                    onBackground = Color.White,
                    surfaceContainerLow = Color(0xFF17171A),
                    surfaceContainer = CardBg,
                    surfaceContainerHigh = Color(0xFF313137),
                    onSurface = Color.White,
                    onSurfaceVariant = TextSecondary
                ),
                motionScheme = MotionScheme.expressive()
            ) {
                StartupGate()
            }
        }
    }
}

/**
 * 启动分流：按「上次退出时是否为登录态」决定首屏。
 * - 登录态 + 会话恢复中：显示登录进度屏，恢复完成自动进入主界面；
 * - 登录态但恢复失败（Guest/LoggedOut）：弹登录页引导重新登录；
 * - 上次就不是登录态：不显示进度屏，直接弹登录页；
 * - 用户在登录页主动选择「游客模式」：放行进入主界面。
 */
@Composable
private fun StartupGate() {
    val container = ServiceLocator.container
    val accountState by container.accountRepo.state.collectAsState()

    when {
        // 上次是登录态、会话还在恢复 → 登录进度屏
        accountState is AccountState.Loading && container.prefs.lastSessionLoggedIn ->
            StartupPane()

        // 已登录，或用户已主动选择游客模式 → 主界面
        accountState is AccountState.LoggedIn ||
            (accountState is AccountState.Guest && container.accountRepo.userPickedGuest) ->
            AppNavHost()

        // 其余情况（未登录 / 非主动游客）→ 引导到登录页
        else -> AppNavHost(forceLogin = true)
    }
}

/** 登录进度屏：品牌标识 + 「正在登录」可视化。 */
@Composable
private fun StartupPane() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(NeteaseRed, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text("WearMusic", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text("正在登录网易云账号…", fontSize = 11.sp, color = TextSecondary)
        }
    }
}
