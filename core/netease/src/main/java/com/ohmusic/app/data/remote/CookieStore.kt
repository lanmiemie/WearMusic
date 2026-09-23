package com.ohmusic.app.data.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网易云登录凭据（cookie）的本地持久化。
 *
 * 参照 oh-my-neteasemusic4harmonyos 的 `SessionStore`：登录成功后服务端把一长串
 * cookie 下发给我们，之后所有需要鉴权的接口都把它放进请求 JSON body 的 `cookie` 字段。
 *
 * cookie 存放在独立的 SharedPreferences 文件里，并建议宿主从云备份 / 设备迁移中
 * 排除该文件，避免登录凭据被同步出去。
 *
 * 模块不绑定任何 DI 框架：Hilt 宿主用一个 `@Provides`（`@ApplicationContext` 注入）
 * 提供实例即可，非 DI 宿主直接 `CookieStore(context)` 构造。
 */
class CookieStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _hasSession = MutableStateFlow(loadCookie().isNotEmpty())

    /** 当前是否持有 cookie（不区分真实登录与游客）。 */
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

    /** 读取 cookie；未登录时返回空串。 */
    fun getCookie(): String = loadCookie()

    fun saveCookie(cookie: String) {
        val normalized = cookie.trim()
        prefs.edit().putString(KEY_COOKIE, normalized).apply()
        _hasSession.value = normalized.isNotEmpty()
    }

    fun clear() {
        prefs.edit().remove(KEY_COOKIE).apply()
        _hasSession.value = false
    }

    private fun loadCookie(): String = prefs.getString(KEY_COOKIE, "").orEmpty()

    private companion object {
        const val PREFS_NAME = "ohmusic_netease_session"
        const val KEY_COOKIE = "netease_cookie"
    }
}
