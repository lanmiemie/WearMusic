package com.shijiu.wearmusic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
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
                AppNavHost()
            }
        }
    }
}
