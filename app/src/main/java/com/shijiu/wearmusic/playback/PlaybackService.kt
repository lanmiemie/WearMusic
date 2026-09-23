package com.shijiu.wearmusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.shijiu.wearmusic.ServiceLocator

/**
 * 前台媒体会话服务：承载 ExoPlayer 与 MediaSession。
 *
 * Wear OS 的媒体控制面板通过 MediaSessionService 的 intent-filter 发现本服务；
 * onCreate 立即 startForeground，避免「startForegroundService 后未及时前台化」崩溃，
 * 之后 Media3 的 DefaultMediaNotificationProvider 会接管为真正的媒体通知。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNow()
        val manager = ServiceLocator.container.playbackManager
        val player: ExoPlayer = manager.obtainPlayer(this)
        mediaSession = MediaSession.Builder(this, player).build()
        manager.attachSession(mediaSession!!)
    }

    /** 立即进入前台态：占位通知，稍后被 Media3 媒体通知替换。 */
    private fun startForegroundNow() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("WearMusic")
                .setContentText("音乐播放服务运行中")
                .setOngoing(true)
                .build()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        ServiceLocator.container.playbackManager.onServiceDestroyed()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playback_service"
        private const val NOTIFICATION_ID = 1001
    }
}
