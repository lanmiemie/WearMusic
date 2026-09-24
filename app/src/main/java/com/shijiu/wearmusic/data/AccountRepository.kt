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
    private val playlistApi: CloudPlaylistApi,
    private val prefs: AppPrefs
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

    /**
     * 用户是否在本会话中「主动」选择了游客模式（登录页按钮）。
     * 区别于启动兜底的静默游客登录：前者放行进入主界面，后者仍引导去登录页。
     */
    @Volatile
    var userPickedGuest: Boolean = false
        private set

    /** 统一的状态写入：同时把登录态落盘，供下次启动判断是否显示登录进度屏。 */
    private fun setState(s: AccountState) {
        _state.value = s
        if (s !is AccountState.Loading) {
            prefs.lastSessionLoggedIn = s is AccountState.LoggedIn
        }
    }

    init {
        scope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        if (!prefs.lastSessionLoggedIn) {
            // 上次退出时不是登录态：不自动登录，停在未登录，由 UI 引导到登录页
            setState(AccountState.LoggedOut)
            return
        }
        // 上次是登录态：用本地 cookie 恢复会话（期间 UI 显示登录进度屏）
        if (cookieStore.getCookie().isNotEmpty()) {
            refresh()
        }
        // 仍未建立会话（cookie 失效 / 网络失败）：静默降级游客会话保证浏览可用；
        // 但 userPickedGuest 仍为 false，UI 会引导用户重新登录
        if (_state.value is AccountState.Loading || _state.value is AccountState.LoggedOut) {
            loginGuestInternal()
        }
    }

    /** 用现有 cookie 拉取账号信息。 */
    suspend fun refresh() {
        runCatching { authApi.getAccount() }.fold(
            onSuccess = { account ->
                setState(
                    if (account.anonymous || account.userId <= 0) AccountState.Guest
                    else AccountState.LoggedIn(account)
                )
            },
            onFailure = {
                cookieStore.clear()
                setState(AccountState.LoggedOut)
            }
        )
    }

    /** 游客登录（登录页「游客模式」按钮：用户主动选择，放行进入主界面）。 */
    suspend fun loginGuest(): Boolean {
        userPickedGuest = true
        return loginGuestInternal()
    }

    private suspend fun loginGuestInternal(): Boolean {
        val cookie = runCatching { authApi.loginAnonymously() }.getOrNull()
        if (cookie.isNullOrBlank()) {
            setState(AccountState.LoggedOut)
            return false
        }
        cookieStore.saveCookie(cookie)
        setState(AccountState.Guest)
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
        setState(AccountState.LoggedOut)
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
