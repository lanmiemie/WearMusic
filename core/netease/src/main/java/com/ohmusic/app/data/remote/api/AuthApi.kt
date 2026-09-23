package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.data.remote.NeteaseConstants
import com.ohmusic.app.data.remote.NeteaseRawResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 二维码登录会话。 */
data class QrLoginSession(
    val key: String,
    val qrUrl: String,
    /** `data:image/png;base64,...` 形式的二维码图，可直接交给 Compose `Image` 渲染。 */
    val qrImage: String
)

/** 二维码轮询结果。 */
data class QrCheckResult(
    val code: Int,
    val message: String,
    /** 仅 [NeteaseConstants.QR_AUTHORIZED] 时非空。 */
    val cookie: String?
) {
    val isWaitingScan: Boolean get() = code == NeteaseConstants.QR_WAITING_SCAN
    val isWaitingConfirm: Boolean get() = code == NeteaseConstants.QR_WAITING_CONFIRM
    val isAuthorized: Boolean get() = code == NeteaseConstants.QR_AUTHORIZED
    val isExpired: Boolean get() = code == NeteaseConstants.QR_EXPIRED
}

/** `/user/account` 里用得到的账号信息。 */
data class NeteaseAccount(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    val backgroundUrl: String,
    val signature: String,
    val userName: String,
    val vipType: Int,
    /** 是否为游客（NMTID 匿名）身份。 */
    val anonymous: Boolean
) {
    /** 黑胶 VIP 判定：vipType > 0 即视为拥有会员权益。 */
    val isVip: Boolean get() = vipType > 0
}

/**
 * 账号与登录相关接口。
 *
 * 对应参考项目的 `AuthService` + `OnboardingApi`，路径与参数保持一致，
 * 详见 `docs/` 下各接口文档。
 */
@Singleton
class AuthApi @Inject constructor(
    private val client: NeteaseClient
) {

    // ── 二维码登录 ──────────────────────────────────────────────

    /** 生成二维码 key，再据此生成二维码图片。 */
    suspend fun createQrLogin(): QrLoginSession = withContext(Dispatchers.IO) {
        val keyResponse = client.post(
            path = "/login/qr/key",
            query = mapOf("timestamp" to now()),
            withCookie = false
        )
        val unikey = keyResponse["data"]?.jsonObject?.get("unikey")?.jsonPrimitive?.content
            ?: throw IllegalStateException("二维码 key 生成失败")

        val createResponse = client.post(
            path = "/login/qr/create",
            query = mapOf(
                "key" to unikey,
                "qrimg" to "true",
                "timestamp" to now()
            ),
            withCookie = false
        )
        val data = createResponse["data"]?.jsonObject
        QrLoginSession(
            key = unikey,
            qrUrl = data?.get("qrurl")?.jsonPrimitive?.content.orEmpty(),
            qrImage = data?.get("qrimg")?.jsonPrimitive?.content.orEmpty()
        )
    }

    /**
     * 轮询二维码状态。
     *
     * 800/801/802/803 都是正常状态而非错误，所以都要放进 `successCodes`，
     * 否则 [NeteaseClient] 会把它们当成失败抛出。
     */
    suspend fun checkQrLogin(key: String): QrCheckResult = withContext(Dispatchers.IO) {
        val response: NeteaseRawResponse = client.postForHeaders(
            path = "/login/qr/check",
            query = mapOf(
                "key" to key,
                "timestamp" to now(),
                "noCookie" to "true"
            ),
            successCodes = setOf(
                NeteaseConstants.QR_EXPIRED,
                NeteaseConstants.QR_WAITING_SCAN,
                NeteaseConstants.QR_WAITING_CONFIRM,
                NeteaseConstants.QR_AUTHORIZED
            )
        )
        val body = response.data
        QrCheckResult(
            code = body["code"]?.jsonPrimitive?.intOrNull ?: -1,
            message = body["message"]?.jsonPrimitive?.content.orEmpty(),
            cookie = body["cookie"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        )
    }

    // ── 手机号登录 ──────────────────────────────────────────────

    /** 发送手机验证码。 */
    suspend fun sendCaptcha(phone: String, countryCode: String = "86") {
        client.postForm(
            path = "/captcha/sent",
            query = mapOf(
                "phone" to phone,
                "ctcode" to countryCode,
                "timestamp" to now()
            ),
            withCookie = false
        )
    }

    /**
     * 校验手机验证码，返回可用于登录的一次性凭证。
     * 部分网关注册了 `/captcha/verify`，失败时调用方可以退回直接调用 [loginByCellphone]。
     */
    suspend fun verifyCaptcha(phone: String, captcha: String, countryCode: String = "86"): Boolean {
        val response = client.postForm(
            path = "/captcha/verify",
            query = mapOf(
                "phone" to phone,
                "captcha" to captcha,
                "ctcode" to countryCode,
                "timestamp" to now()
            ),
            withCookie = false
        )
        // 该接口成功时不会返回 cookie，只校验验证码是否正确。
        return response["code"]?.jsonPrimitive?.intOrNull == NeteaseConstants.CODE_SUCCESS
    }

    /**
     * 手机号 + 验证码登录。成功时响应体里的 `cookie` 字段即为登录凭据。
     */
    suspend fun loginByCellphone(
        phone: String,
        captcha: String,
        countryCode: String = "86"
    ): String = withContext(Dispatchers.IO) {
        val response = client.postForm(
            path = "/login/cellphone",
            query = mapOf(
                "phone" to phone,
                "captcha" to captcha,
                "ctcode" to countryCode,
                "timestamp" to now()
            ),
            withCookie = false
        )
        response["cookie"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("登录成功但服务端未返回 cookie")
    }

    // ── 游客登录 ────────────────────────────────────────────────

    /**
     * 游客登录：获取匿名凭证。
     *
     * 实测该网关的 `/register/anonimous` 只返回 `{"code":400}` 且**不下发 Set-Cookie**，
     * 因此这里实现两级回退：
     *
     * 1. `/register/anonimous` 的 `Set-Cookie`（部分部署会返回）；
     * 2. 退回 `/login/qr/check`——该接口即使在「等待扫码」（801）状态下
     *    也会在 body 的 `cookie` 字段里给出一枚 NMTID，这正是游客所需的最小凭据。
     *
     * @return 可直接用于后续请求的 cookie 串；两次尝试都拿不到时返回 null。
     */
    suspend fun loginAnonymously(): String? = withContext(Dispatchers.IO) {
        val fromRegister = runCatching {
            val response = client.postForHeaders(
                path = "/register/anonimous",
                successCodes = setOf(NeteaseConstants.CODE_ANONYMOUS_OK)
            )
            response.extractNmTid()
                ?: response.setCookieHeaders.firstOrNull()
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        if (!fromRegister.isNullOrBlank()) return@withContext fromRegister

        // 回退：申请一个二维码 key，再读一次 check 拿 NMTID。
        runCatching {
            val keyResponse = client.post(
                path = "/login/qr/key",
                query = mapOf("timestamp" to now()),
                withCookie = false
            )
            val unikey = keyResponse["data"]?.jsonObject?.get("unikey")?.jsonPrimitive?.content
                ?: return@runCatching null

            val checkResponse = client.postForHeaders(
                path = "/login/qr/check",
                query = mapOf(
                    "key" to unikey,
                    "timestamp" to now(),
                    "noCookie" to "true"
                ),
                successCodes = setOf(
                    NeteaseConstants.QR_EXPIRED,
                    NeteaseConstants.QR_WAITING_SCAN,
                    NeteaseConstants.QR_WAITING_CONFIRM,
                    NeteaseConstants.QR_AUTHORIZED
                )
            )
            checkResponse.data["cookie"]?.jsonPrimitive?.content
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // ── 账号信息 ────────────────────────────────────────────────

    /**
     * 获取当前账号信息。未登录时会抛出业务码 301 的 [com.ohmusic.app.data.remote.NeteaseApiException]。
     */
    suspend fun getAccount(): NeteaseAccount = withContext(Dispatchers.IO) {
        val response = client.post(path = "/user/account")
        val account = response["account"]?.jsonObject
        val profile = response["profile"]?.jsonObject
            ?: throw IllegalStateException("服务端未返回账号信息，cookie 可能已失效")

        NeteaseAccount(
            userId = profile["userId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            nickname = profile["nickname"]?.jsonPrimitive?.content.orEmpty(),
            avatarUrl = profile["avatarUrl"]?.jsonPrimitive?.content.orEmpty(),
            backgroundUrl = profile["backgroundUrl"]?.jsonPrimitive?.content.orEmpty(),
            signature = profile["signature"]?.jsonPrimitive?.content.orEmpty(),
            userName = profile["userName"]?.jsonPrimitive?.content.orEmpty(),
            vipType = profile["vipType"]?.jsonPrimitive?.intOrNull ?: 0,
            anonymous = account?.get("anonimousUser")?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    /** 退出登录（使服务端会话失效，失败不影响本地清理）。 */
    suspend fun logout() {
        runCatching { client.post(path = "/logout") }
    }

    private fun now(): String = System.currentTimeMillis().toString()

    /** 供调用方构造自定义请求体。 */
    private fun emptyBody(): JsonObject = buildJsonObject { }
}
