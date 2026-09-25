package com.shijiu.wearmusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.api.CloudFmApi
import com.ohmusic.app.data.remote.api.CloudSongApi
import com.ohmusic.app.data.remote.NeteaseConstants
import com.shijiu.wearmusic.MainActivity
import com.shijiu.wearmusic.R
import com.shijiu.wearmusic.data.AppPrefs
import com.shijiu.wearmusic.data.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** 播放模式：顺序播放 / 随机播放 / 单曲循环。 */
enum class PlayMode { ORDER, SHUFFLE, REPEAT_ONE }

/**
 * 播放管理器：队列 / 直链解析 / 打卡上报 / 私人漫游尾部追加。
 *
 * - ExoPlayer 由 [PlaybackService] 通过 [obtainPlayer] 创建（进程内单例），
 *   UI 直接调用本类方法，无需 MediaController；
 * - 整个播放队列镜像为 ExoPlayer 的 playlist：系统媒体面板据此展示"正在播放列表"；
 *   网易云直链时效很短，未播放的条目先挂占位 URI，接近播放时实时解析并
 *   [ExoPlayer.replaceMediaItem] 原位替换，切歌全程无网络空窗（后台不休眠断播）；
 * - 播放进度过半或超过 30 秒时向云端 scrobble 打卡一次。
 */
class PlaybackManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val songApi: CloudSongApi,
    private val fmApi: CloudFmApi,
    private val musicRepo: MusicRepository,
    private val prefs: AppPrefs
) {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.ORDER)

    /** 当前播放模式（顺序 / 随机 / 单曲循环），播放页循环切换。 */
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    /** 用户主动点播后请求打开播放页的事件（切歌/自动连播不发射）。 */
    private val _openPlayerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val openPlayerRequests: SharedFlow<Unit> = _openPlayerRequests.asSharedFlow()

    /** 当前是否处于私人漫游（FM）队列。 */
    private var queueTag: String? = null
    private var tailLoader: (suspend () -> List<Song>)? = null
    private var tailLoading = false

    val currentSong: Song?
        get() = _queue.value.getOrNull(_currentIndex.value)

    val queueContextTag: String? get() = queueTag

    // 直链缓存：songId -> (抓取时间, url)
    private val urlCache = HashMap<Long, Pair<Long, String>>()
    private val scrobbled = HashSet<Long>()
    private var consecutiveErrors = 0

    /** 单飞标记：同一时刻只允许一个"预解析下一首"协程。 */
    private val prefetchInFlight = AtomicBoolean(false)

    /** 后台切歌兜底用的部分唤醒锁（ExoPlayer wake lock 只覆盖播放/加载窗口）。 */
    private var advanceWakeLock: PowerManager.WakeLock? = null

    // ────────────────────────────────────────────────────────────
    // 服务绑定
    // ────────────────────────────────────────────────────────────

    /** UI 侧入口：确保前台服务已启动（首次播放前必须）。 */
    fun ensureServiceStarted() {
        runCatching {
            appContext.startForegroundService(Intent(appContext, PlaybackService::class.java))
        }
    }

    /** 服务创建播放器（进程内唯一实例）。 */
    fun obtainPlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(NeteaseConstants.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to NeteaseConstants.REFERER))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val newPlayer = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        newPlayer.addListener(playerListener)
        player = newPlayer
        startTicker()
        return newPlayer
    }

    /** 服务创建 MediaSession 后挂接。 */
    fun attachSession(mediaSession: MediaSession) {
        session = mediaSession
        // 服务起来后若有挂起的当前曲目则立即开播
        if (_currentIndex.value >= 0 && player?.currentMediaItem == null) {
            playCurrent()
        }
    }

    fun onServiceDestroyed() {
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
    }

    // ────────────────────────────────────────────────────────────
    // 播放控制
    // ────────────────────────────────────────────────────────────

    /**
     * 以 [songs] 为新队列从 [startIndex] 播放。
     *
     * @param tag 队列上下文（如 "fm"、"playlist:123"），用于行为分支与打卡 sourceid
     * @param tailLoader 队列接近末尾时的追加回调（私人漫游无限续播）
     */
    fun playQueue(
        songs: List<Song>,
        startIndex: Int = 0,
        tag: String? = null,
        tailLoader: (suspend () -> List<Song>)? = null
    ) {
        if (songs.isEmpty()) return
        ensureServiceStarted()
        _queue.value = songs
        _currentIndex.value = startIndex.coerceIn(0, songs.lastIndex)
        queueTag = tag
        this.tailLoader = tailLoader
        consecutiveErrors = 0
        playCurrent()
        // 用户主动点播：请求 UI 跳转到播放页
        _openPlayerRequests.tryEmit(Unit)
    }

    fun playAt(index: Int) {
        if (index < 0 || index > _queue.value.lastIndex) return
        val song = _queue.value[index]
        _currentIndex.value = index
        _positionMs.value = 0L
        _durationMs.value = song.duration
        _isBuffering.value = true
        scope.launch {
            try {
                playAtInternal(index, song)
            } catch (error: Exception) {
                postNotice(error.message ?: "获取播放链接失败")
                _isBuffering.value = false
                skipCurrent()
            }
        }
    }

    fun togglePlayPause() {
        val p = player
        if (p == null) {
            // 服务尚未起来：重新发起当前曲目
            if (currentSong != null) {
                ensureServiceStarted()
                playCurrent()
            }
            return
        }
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() {
        // 随机模式：在现有队列里随机挑一首不同的（不触发尾部加载）
        if (_playMode.value == PlayMode.SHUFFLE && _queue.value.size > 1) {
            playAt(randomIndexExceptCurrent())
            return
        }
        if (_currentIndex.value < _queue.value.lastIndex) {
            playAt(_currentIndex.value + 1)
        } else {
            loadMoreThenContinue()
        }
    }

    /** 循环切换播放模式：顺序 → 随机 → 单曲循环 → 顺序。 */
    fun cyclePlayMode() {
        _playMode.value = when (_playMode.value) {
            PlayMode.ORDER -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.ORDER
        }
        applyPlayModeToPlayer()
    }

    private fun applyPlayModeToPlayer() {
        player?.repeatMode = if (_playMode.value == PlayMode.REPEAT_ONE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    private fun randomIndexExceptCurrent(): Int {
        val size = _queue.value.size.coerceAtLeast(1)
        var target = _currentIndex.value
        while (target == _currentIndex.value) {
            target = Random.nextInt(size)
        }
        return target
    }

    fun previous() {
        val p = player
        if (p != null && p.currentPosition > 3_000) {
            p.seekTo(0)
            _positionMs.value = 0
        } else if (_currentIndex.value > 0) {
            playAt(_currentIndex.value - 1)
        } else {
            p?.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
        _positionMs.value = positionMs.coerceAtLeast(0)
    }

    fun currentPosition(): Long = player?.currentPosition ?: _positionMs.value

    /** 清空播放直链缓存（清理缓存入口；下次播放将重新解析）。 */
    fun clearUrlCache() {
        synchronized(urlCache) { urlCache.clear() }
    }

    /** 播放当前曲：重建整个 playlist 镜像（当前项带真直链，其余占位）。 */
    private fun playCurrent() {
        val song = currentSong ?: return
        val startIndex = _currentIndex.value
        _positionMs.value = 0L
        _durationMs.value = song.duration
        _isBuffering.value = true
        scope.launch {
            try {
                val url = resolveUrl(song)
                withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext
                    applyPlayModeToPlayer()
                    val items = _queue.value.map { s ->
                        val resolved = if (s.songId == song.songId) url else null
                        buildItem(s, resolved)
                    }
                    p.setMediaItems(items, startIndex, 0L)
                    p.prepare()
                    p.playWhenReady = true
                }
                // 实况窗：切歌后在表盘显示「正在播放 - 歌名 · 歌手」，点击直达应用
                updateOngoingActivity(song, true)
                maybePrefetchTail()
                prefetchNextItem()
            } catch (error: Exception) {
                // 任何解析/播放器异常都不允许把进程带崩，降级为跳过当前曲目
                postNotice(error.message ?: "获取播放链接失败")
                _isBuffering.value = false
                skipCurrent()
            }
        }
    }

    /** 定位并播放 playlist 中第 [index] 项（必要时先原位替换占位 URI）。 */
    private suspend fun playAtInternal(index: Int, song: Song) {
        val url = resolveUrl(song)
        withContext(Dispatchers.Main) {
            val p = player ?: return@withContext
            if (index < p.mediaItemCount && isPlaceholder(p.getMediaItemAt(index))) {
                p.replaceMediaItem(index, buildItem(song, url))
            }
            p.seekTo(index, 0)
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                p.prepare()
            }
            p.playWhenReady = true
        }
        updateOngoingActivity(song, true)
    }

    private fun buildItem(song: Song, url: String?): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(song.songId?.toString() ?: song.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(song.coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .build()
            )
        // 未解析项挂无效占位：ExoPlayer 播到它时快速失败，由 onPlayerError 兜底现场解析
        builder.setUri(url ?: PLACEHOLDER_URI)
        return builder.build()
    }

    private fun isPlaceholder(item: MediaItem?): Boolean =
        item?.localConfiguration?.uri?.toString() == PLACEHOLDER_URI

    private suspend fun resolveUrl(song: Song): String {
        val songId = song.songId ?: throw IllegalStateException("该歌曲不支持在线播放")
        val cached = synchronized(urlCache) { urlCache[songId] }
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < URL_TTL_MS) return cached.second
        val url = songApi.getSongUrl(songId, prefs.bitrate)
        synchronized(urlCache) { urlCache[songId] = now to url }
        return url
    }

    /**
     * 预解析下一首直链并原位替换占位项。
     * 后台播放的关键：自动切歌由 ExoPlayer 内部完成（wake lock 全程覆盖），
     * 提前把下一首的真直链填进 playlist，切换零网络请求、零休眠空窗。
     */
    private fun prefetchNextItem() {
        if (!prefetchInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val nextIdx = _currentIndex.value + 1
                if (nextIdx < 0 || nextIdx > _queue.value.lastIndex) return@launch
                val next = _queue.value[nextIdx]
                val url = resolveUrl(next) // 内部带 TTL 缓存，命中即秒回
                withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext
                    if (nextIdx < p.mediaItemCount && isPlaceholder(p.getMediaItemAt(nextIdx))) {
                        p.replaceMediaItem(nextIdx, buildItem(next, url))
                    }
                }
            } catch (_: Exception) {
                // 预取失败不致命：播到该项时 onPlayerError 兜底现场解析
            } finally {
                prefetchInFlight.set(false)
            }
        }
    }

    /** 无音源 / 出错时跳到下一首；连续失败过多则停下。 */
    private fun skipCurrent() {
        consecutiveErrors++
        if (consecutiveErrors >= 3 || _currentIndex.value >= _queue.value.lastIndex) {
            if (_currentIndex.value >= _queue.value.lastIndex) {
                _isPlaying.value = false
                _isBuffering.value = false
            }
            return
        }
        scope.launch {
            delay(400)
            playAt(_currentIndex.value + 1)
        }
    }

    private fun loadMoreThenContinue() {
        if (tailLoader == null) return
        acquireAdvanceWakeLock()
        scope.launch {
            val more = appendTail() ?: return@launch
            if (more) playAt(_currentIndex.value + 1)
        }
    }

    /** 队列接近末尾时预取下一批（私人漫游）。 */
    private suspend fun maybePrefetchTail() {
        if (tailLoader == null || tailLoading) return
        if (_currentIndex.value < _queue.value.size - 2) return
        appendTail()
    }

    private suspend fun appendTail(): Boolean {
        val loader = tailLoader ?: return false
        if (tailLoading) return false
        tailLoading = true
        try {
            val more = loader().filter { new -> _queue.value.none { it.songId == new.songId } }
            if (more.isEmpty()) return false
            _queue.value = _queue.value + more
            withContext(Dispatchers.Main) {
                player?.addMediaItems(more.map { buildItem(it, null) })
            }
            return true
        } finally {
            tailLoading = false
        }
    }

    // ────────────────────────────────────────────────────────────
    // Player 监听
    // ────────────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    consecutiveErrors = 0
                    _isBuffering.value = false
                    player?.duration?.takeIf { it > 0 }?.let { _durationMs.value = it }
                }
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_ENDED -> onEnded()
                Player.STATE_IDLE -> _isBuffering.value = false
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // playlist 模式：曲目播完由 ExoPlayer 自动推进到下一项，这里同步队列游标
            val p = player ?: return
            val idx = p.currentMediaItemIndex
            if (idx != _currentIndex.value && idx < _queue.value.size) {
                _currentIndex.value = idx
                _positionMs.value = 0
                _queue.value.getOrNull(idx)?.let {
                    _durationMs.value = it.duration
                    updateOngoingActivity(it, _isPlaying.value)
                }
            }
            // 立刻着手预解析再下一首
            prefetchNextItem()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            // 实况窗：播放/暂停状态变化时刷新表盘持续活动
            currentSong?.let { updateOngoingActivity(it, isPlaying) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: run { skipCurrent(); return }
            val idx = p.currentMediaItemIndex
            val song = _queue.value.getOrNull(idx)
            // 首次失败：占位 URI（预解析没赶上，含系统面板点队列/下一首直达占位项）
            // 或已播歌曲的直链过期 → 现场重新解析重播一次；再次失败交由 skipCurrent 推进
            if (consecutiveErrors == 0 && song != null && idx < p.mediaItemCount) {
                acquireAdvanceWakeLock()
                // 占位项不可播：顺序模式原位重解析重播；随机模式随机跳一首（自动续播随机化）
                playAt(if (_playMode.value == PlayMode.SHUFFLE) randomIndexExceptCurrent() else idx)
                return
            }
            postNotice("播放出错，已尝试跳过")
            skipCurrent()
        }
    }

    private fun onEnded() {
        acquireAdvanceWakeLock()
        if (_currentIndex.value < _queue.value.lastIndex) {
            playAt(_currentIndex.value + 1)
        } else if (tailLoader != null) {
            loadMoreThenContinue()
        } else {
            _isPlaying.value = false
        }
    }

    // ────────────────────────────────────────────────────────────
    // 进度轮询 + 听歌打卡
    // ────────────────────────────────────────────────────────────

    private fun startTicker() {
        scope.launch {
            while (isActive) {
                delay(1_000)
                // ExoPlayer 强制要求在创建线程（主线程）上访问，切回主线程再轮询
                withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext
                    if (p.isPlaying) {
                        val pos = p.currentPosition
                        _positionMs.value = pos
                        checkScrobble(pos, p.duration)
                        // 距结束不足 20 秒：提前解析并替换下一首的直链
                        val dur = p.duration
                        if (dur > 0 && dur - pos < PREFETCH_WINDOW_MS) {
                            prefetchNextItem()
                        }
                    }
                }
            }
        }
    }

    /** 听歌打卡：播放过半或满 30 秒即向云端上报一次。 */
    private fun checkScrobble(positionMs: Long, durationMs: Long) {
        val song = currentSong ?: return
        val songId = song.songId ?: return
        if (scrobbled.contains(songId)) return
        val reached = positionMs >= 30_000 ||
            (durationMs > 0 && positionMs >= durationMs / 2)
        if (!reached) return
        scrobbled.add(songId)
        val seconds = positionMs / 1000
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { fmApi.scrobble(songId, seconds) }.isSuccess
            if (ok) postNotice("已打卡上报：${song.title}")
        }
    }

    private fun postNotice(text: String) {
        _notice.value = text
        scope.launch {
            delay(4_000)
            if (_notice.value == text) _notice.value = null
        }
    }

    /** UI 层展示临时提示（打卡结果、收藏结果等）。 */
    fun notify(text: String) = postNotice(text)

    /** 后台换歌兜底唤醒锁：覆盖直链解析 + prepare 的网络窗口，防止 CPU 休眠导致卡死。 */
    private fun acquireAdvanceWakeLock() {
        runCatching {
            if (advanceWakeLock == null) {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                advanceWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "wearmusic:advance"
                ).apply { setReferenceCounted(false) }
            }
            advanceWakeLock?.acquire(30_000)
        }
    }

    // ────────────────────────────────────────────────────────────
    // Wear OS 实况窗（Ongoing Activity）
    // ────────────────────────────────────────────────────────────

    private var lastOngoingSongId: Long? = null
    private var lastOngoingPlaying: Boolean? = null

    /**
     * Wear OS 实况窗：发布一条携带 [MediaSession] token 的常驻媒体通知。
     * Wear OS 系统会自动把这类通知提升为表盘上的持续活动（正在播放指示），
     * 点击可直达应用；切歌 / 播放暂停切换时刷新内容。
     */
    private fun updateOngoingActivity(song: Song, playing: Boolean) {
        val songKey = song.songId ?: song.id
        if (lastOngoingSongId == songKey && lastOngoingPlaying == playing) return
        lastOngoingSongId = songKey
        lastOngoingPlaying = playing
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_LIVE, "正在播放", NotificationManager.IMPORTANCE_LOW)
                )
            }
            if (!nm.areNotificationsEnabled()) return

            val touchIntent = PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder =
                NotificationCompat.Builder(appContext, CHANNEL_LIVE)
                    .setSmallIcon(R.drawable.ic_stat_note)
                    .setContentTitle(if (playing) "正在播放" else "已暂停")
                    .setContentText("${song.title} · ${song.artist}")
                    .setSubText(song.album)
                    .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(touchIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 关联 MediaSession：系统据此在表盘展示媒体实况窗
            session?.let {
                builder.setStyle(MediaStyleNotificationHelper.MediaStyle(it))
            }
            val notification = builder.build()

            NotificationManagerCompat.from(appContext).notify(ONGOING_ID, notification)
        }
    }

    companion object {
        private const val URL_TTL_MS = 8 * 60 * 1000L
        private const val CHANNEL_LIVE = "live_update"
        private const val ONGOING_ID = 2001

        /** 未解析条目的占位 URI：不可达地址，播放到它时快速失败并触发兜底解析。 */
        private const val PLACEHOLDER_URI = "about:blank#wearmusic-unresolved"

        /** 距曲目结束不足该时长即预解析下一首（后台续播关键窗口）。 */
        private const val PREFETCH_WINDOW_MS = 20_000L
    }
}
