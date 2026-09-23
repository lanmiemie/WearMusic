package com.shijiu.wearmusic.data

import android.content.Context
import com.ohmusic.app.data.remote.CookieStore
import com.ohmusic.app.data.remote.api.AuthApi
import com.ohmusic.app.data.remote.api.CloudPlaylistApi
import com.ohmusic.app.data.remote.api.NeteaseAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 账号状态。 */
sealed interface AccountState {
    data object Loading : AccountState
    data object LoggedOut : AccountState

    /** 游客（NMTID 匿名 cookie）。 */
    data object Guest : AccountState

    /** 已登录的真实账号。 */
    data class LoggedIn(val account: NeteaseAccount) : AccountState
}

/**
 * 账号仓库：游客自动登录、二维码 / 验证码登录、账号信息、登出。
 */
class AccountRepository(
    private val scope: CoroutineScope,
    private val authApi: AuthApi,
    private val cookieStore: CookieStore,
    private val playlistApi: CloudPlaylistApi
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    val isLoggedIn: Boolean get() = _state.value is AccountState.LoggedIn
    val uid: Long get() = (_state.value as? AccountState.LoggedIn)?.account?.userId ?: 0L

    /** 当前账号 vipType（0=非会员，>0=拥有会员权益；仅登录态有效）。 */
    val vipType: Int get() = (_state.value as? AccountState.LoggedIn)?.account?.vipType ?: 0

    /** 是否拥有会员权益（无损等高品质音质的展现门槛）。 */
    val isVip: Boolean get() = vipType > 0
    val nickname: String
        get() = when (val s = _state.value) {
            is AccountState.LoggedIn -> s.account.nickname
            is AccountState.Guest -> "游客"
            else -> "未登录"
        }

    init {
        scope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        if (cookieStore.getCookie().isNotEmpty()) {
            refresh()
        }
        // 仍无会话则静默游客登录，保证搜索 / 榜单 / 试听开箱可用
        if (_state.value is AccountState.Loading || _state.value is AccountState.LoggedOut) {
            loginGuestInternal()
        }
    }

    /** 用现有 cookie 拉取账号信息。 */
    suspend fun refresh() {
        runCatching { authApi.getAccount() }.fold(
            onSuccess = { account ->
                _state.value =
                    if (account.anonymous || account.userId <= 0) AccountState.Guest
                    else AccountState.LoggedIn(account)
            },
            onFailure = {
                cookieStore.clear()
                _state.value = AccountState.LoggedOut
            }
        )
    }

    /** 游客登录（供手动重试）。 */
    suspend fun loginGuest(): Boolean = loginGuestInternal()

    private suspend fun loginGuestInternal(): Boolean {
        val cookie = runCatching { authApi.loginAnonymously() }.getOrNull()
        if (cookie.isNullOrBlank()) {
            _state.value = AccountState.LoggedOut
            return false
        }
        cookieStore.saveCookie(cookie)
        _state.value = AccountState.Guest
        return true
    }

    /** 发送手机验证码。 */
    suspend fun sendCaptcha(phone: String): Boolean =
        runCatching { authApi.sendCaptcha(phone) }.isSuccess

    /** 验证码登录；成功后刷新账号状态。 */
    suspend fun loginByPhone(phone: String, captcha: String): Boolean =
        runCatching {
            val cookie = authApi.loginByCellphone(phone, captcha)
            cookieStore.saveCookie(cookie)
        }.fold(
            onSuccess = {
                refresh()
                isLoggedIn
            },
            onFailure = { false }
        )

    /** 二维码登录成功后保存 cookie。 */
    suspend fun saveQrCookie(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        cookieStore.saveCookie(cookie)
        refresh()
        return isLoggedIn
    }

    suspend fun logout() {
        authApi.logout()
        cookieStore.clear()
        _state.value = AccountState.LoggedOut
    }

    /** 第一个自己创建的歌单 id（心动模式默认参照歌单）。 */
    suspend fun firstOwnPlaylistId(): Long? {
        if (uid <= 0) return null
        return runCatching { playlistApi.getUserPlaylists(uid) }.getOrNull()
            ?.firstOrNull { it.creatorUserId == uid && !it.isLikedPlaylist }?.id
            ?: runCatching { playlistApi.getUserPlaylists(uid) }.getOrNull()
                ?.firstOrNull { it.creatorUserId == uid }?.id
    }
}
