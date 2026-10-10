package com.shijiu.wearmusic.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放音质档位：与网易云 `/song/url` 的 `br` 参数一一对应。
 * 服务端对超出权益/无音源的请求会自动降级到可用码率。
 */
enum class AudioQuality(
    val label: String,
    val detail: String,
    val br: Int,
    /** 是否需要黑胶 VIP 权益（决定 UI 上是否向该账号展现）。 */
    val requireVip: Boolean = false
) {
    /** 标准：128 kbps，所有人可用。 */
    STANDARD("标准", "128 kbps", 128_000),

    /** 较高：192 kbps，所有人可用。 */
    HIGHER("较高", "192 kbps", 192_000),

    /** 极高：320 kbps，所有人可用（部分曲目服务端可能自动降级）。 */
    EXHIGH("极高", "320 kbps", 320_000),

    /** 无损：FLAC 音源（请求 br=999000，服务端按权益返回最优），仅黑胶 VIP。 */
    LOSSLESS("无损", "FLAC · 需黑胶 VIP", 999_000, requireVip = true);

    companion object {
        /** 由存储的码率值解析档位；未知值回落到「极高」。 */
        fun fromBr(br: Int): AudioQuality =
            entries.firstOrNull { it.br == br } ?: EXHIGH
    }
}

/** 轻量本地配置：音质、搜索历史。 */
class AppPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("wearmusic_prefs", Context.MODE_PRIVATE)

    /** 音频码率，取值见 [AudioQuality]（128000/192000/320000/999000）。 */
    var bitrate: Int
        get() = sp.getInt(KEY_BITRATE, 320_000)
        set(value) = sp.edit().putInt(KEY_BITRATE, value).apply()

    /**
     * 最近一次会话是否为已登录账号（随账号状态实时写入）。
     * 启动时据此分流：登录态 → 显示登录进度屏恢复会话；非登录态 → 直接进登录页。
     */
    var lastSessionLoggedIn: Boolean
        get() = sp.getBoolean(KEY_LAST_LOGGED_IN, false)
        set(value) = sp.edit().putBoolean(KEY_LAST_LOGGED_IN, value).apply()


    fun searchHistory(): List<String> =
        sp.getString(KEY_SEARCH_HISTORY, null)?.split("\u0001")?.filter { it.isNotBlank() }.orEmpty()

    fun addSearchHistory(keyword: String) {
        val updated = (listOf(keyword.trim()) + searchHistory())
            .distinct()
            .take(MAX_HISTORY)
        sp.edit().putString(KEY_SEARCH_HISTORY, updated.joinToString("\u0001")).apply()
    }

    fun clearSearchHistory() {
        sp.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    // ── 本地播放统计 ──

    /**
     * 记录一次「有效播放」（达到 30 秒 / 过半时打卡的同一口径）。
     *
     * 网易云接口只给播放次数，没有首次播放时间——首次播放由本 App 自行记录：
     * `songId -> 首次播放时间戳` 以 JSON map 存本地（几百首仅数 KB）。
     * 已记录过的曲目只刷新不覆盖，保证首播时间不被篡改。
     *
     * @return 该曲目是否为本 App 内首次播放
     */
    fun recordLocalPlay(songId: Long, playedAt: Long = System.currentTimeMillis()): Boolean {
        val map = localFirstPlays()
        if (map.containsKey(songId)) return false
        map[songId] = playedAt
        sp.edit().putString(KEY_LOCAL_FIRST_PLAYS, map.toString()).apply()
        return true
    }

    /** 查询某曲在本 App 的首次播放时间；无记录返回 null。 */
    fun localFirstPlayedAt(songId: Long): Long? = localFirstPlays()[songId]

    private fun localFirstPlays(): MutableMap<Long, Long> {
        val raw = sp.getString(KEY_LOCAL_FIRST_PLAYS, null) ?: return mutableMapOf()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val map = mutableMapOf<Long, Long>()
            for (key in obj.keys()) {
                val id = key.toLongOrNull() ?: continue
                map[id] = obj.getLong(key)
            }
            map
        }.getOrDefault(mutableMapOf())
    }

    private companion object {
        const val KEY_BITRATE = "bitrate"
        const val KEY_LAST_LOGGED_IN = "last_session_logged_in"
        const val KEY_SEARCH_HISTORY = "search_history"
        const val KEY_LOCAL_FIRST_PLAYS = "local_first_plays"
        const val MAX_HISTORY = 8
    }
}
