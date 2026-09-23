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

    private companion object {
        const val KEY_BITRATE = "bitrate"
        const val KEY_SEARCH_HISTORY = "search_history"
        const val MAX_HISTORY = 8
    }
}
