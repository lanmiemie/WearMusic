package com.ohmusic.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网易云 API 网关客户端。
 *
 * 对应参考项目 oh-my-neteasemusic4harmonyos 中的 `HttpClient`，行为保持一致：
 *
 * 1. 鉴权 cookie **不放 HTTP 头**，而是放进请求 JSON body 的 `cookie` 字段；
 * 2. 服务端 HTTP 状态码恒为 200，真实结果码在 body 的 `code` 里，
 *    因此需要按调用方给出的 [successCodes] 判断成功与否；
 * 3. 所有请求都带上时间戳查询参数，规避服务端缓存。
 *
 * 网关接受 GET 与 POST 两种方法，这里统一用 POST（与参考项目一致），
 * 个别接口需要表单体时才切换。
 *
 * @param cookieStore 登录凭据存储（SharedPreferences 实现，也可自行实现）
 * @param baseUrl 网关地址，默认 [NeteaseConstants.DEFAULT_BASE_URL]；
 *   指向自建 NeteaseCloudMusicApi 实例时在此覆盖
 */
class NeteaseClient(
    private val cookieStore: CookieStore,
    private val baseUrl: String = NeteaseConstants.DEFAULT_BASE_URL
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = NeteaseConstants.CONTENT_TYPE_JSON.toMediaType()

    /**
     * 发送带 cookie 的 POST 请求。
     *
     * @param path 以 `/` 开头的接口路径，可自带查询参数。
     * @param query 追加到 URL 的查询参数，值会被 URL 编码。
     * @param body 额外的 JSON body 字段（cookie 由本方法自动注入）。
     * @param successCodes 视为成功的业务码，默认只有 200。
     */
    suspend fun post(
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonObject = JsonObject(emptyMap()),
        successCodes: Set<Int> = setOf(NeteaseConstants.CODE_SUCCESS),
        withCookie: Boolean = true
    ): JsonObject = request(
        method = "POST",
        path = path,
        query = query,
        body = body,
        successCodes = successCodes,
        withCookie = withCookie
    )

    /**
     * 同 [post]，但**不做顶层 code 校验**，把原始响应原样交给调用方。
     *
     * 个别接口（如 `/playlist/update`）返回「聚合响应」：
     * 顶层没有 `code`，而是把每个子操作的结果挂在以接口路径为键的字段下
     * （形如 `"/api/playlist/tags/update": { "code": 200, ... }`）。
     * 顶层校验对这种形态必然失败，需要调用方自行解析子响应。
     */
    suspend fun postUnchecked(
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonObject = JsonObject(emptyMap())
    ): JsonObject = request(
        method = "POST",
        path = path,
        query = query,
        body = body,
        successCodes = null,
        withCookie = true
    )

    /**
     * 发送 POST 并按「聚合响应」语义校验。
     *
     * 要求响应中所有子对象的 `code` 都落在 200..299 区间，否则抛出
     * [NeteaseApiException]（取第一个失败码），全成功或没有子 code 时原样返回。
     */
    suspend fun postAggregate(
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonObject = JsonObject(emptyMap())
    ): JsonObject {
        val response = postUnchecked(path, query, body)
        val subCodes = response.values.mapNotNull { element ->
            runCatching {
                (element as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull
            }.getOrNull()
        }
        val failed = subCodes.firstOrNull { it !in 200..299 }
        if (failed != null) {
            throw NeteaseApiException(failed, "操作未完成（错误码 $failed）")
        }
        return response
    }

    /** 同 [post]，但请求体是 `application/x-www-form-urlencoded` 表单。 */
    suspend fun postForm(
        path: String,
        query: Map<String, String?> = emptyMap(),
        form: Map<String, String> = emptyMap(),
        successCodes: Set<Int> = setOf(NeteaseConstants.CODE_SUCCESS),
        withCookie: Boolean = true
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = buildUrl(path, query)
        val builder = FormBody.Builder()
        form.forEach { (key, value) -> builder.add(key, value) }
        if (withCookie) {
            val cookie = cookieStore.getCookie()
            if (cookie.isNotEmpty()) builder.add("cookie", cookie)
        }
        execute(url, builder.build(), successCodes)
    }

    /**
     * 发送不注入 cookie 的请求，同时把响应头原样返回，
     * 供游客登录读取 `Set-Cookie: NMTID=...` 使用。
     */
    suspend fun postForHeaders(
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonObject = JsonObject(emptyMap()),
        successCodes: Set<Int> = setOf(NeteaseConstants.CODE_SUCCESS)
    ): NeteaseRawResponse = withContext(Dispatchers.IO) {
        val url = buildUrl(path, query)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NeteaseConstants.USER_AGENT)
            .header("Content-Type", NeteaseConstants.CONTENT_TYPE_JSON)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = parseBody(text)
                validate(parsed, successCodes)
                NeteaseRawResponse(
                    data = parsed,
                    setCookieHeaders = response.headers.values("Set-Cookie")
                )
            }
        } catch (error: NeteaseApiException) {
            throw error
        } catch (error: Exception) {
            throw NeteaseApiException.network(error)
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String?>,
        body: JsonObject,
        successCodes: Set<Int>?,
        withCookie: Boolean
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = buildUrl(path, query)
        val payload = if (withCookie) {
            val cookie = cookieStore.getCookie()
            if (cookie.isEmpty()) {
                body
            } else {
                buildJsonObject {
                    body.forEach { (key, value) -> put(key, value) }
                    put("cookie", JsonPrimitive(cookie))
                }
            }
        } else {
            body
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NeteaseConstants.USER_AGENT)
            .header("Content-Type", NeteaseConstants.CONTENT_TYPE_JSON)
            .method(method, payload.toString().toRequestBody(jsonMediaType))
            .build()

        execute(url, request.body, successCodes)
    }

    private fun execute(
        url: String,
        requestBody: okhttp3.RequestBody?,
        successCodes: Set<Int>?
    ): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NeteaseConstants.USER_AGENT)
            .header("Content-Type", NeteaseConstants.CONTENT_TYPE_JSON)
            .post(requestBody ?: "{}".toRequestBody(jsonMediaType))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = parseBody(text)
                validate(parsed, successCodes)
                parsed
            }
        } catch (error: NeteaseApiException) {
            throw error
        } catch (error: Exception) {
            throw NeteaseApiException.network(error)
        }
    }

    private fun parseBody(text: String): JsonObject {
        if (text.isBlank()) {
            throw NeteaseApiException.parse("服务端返回了空响应")
        }
        return try {
            json.parseToJsonElement(text).let { element ->
                when (element) {
                    is JsonObject -> element
                    else -> throw NeteaseApiException.parse("响应不是 JSON 对象：${text.take(120)}")
                }
            }
        } catch (error: NeteaseApiException) {
            throw error
        } catch (error: Exception) {
            throw NeteaseApiException.parse("响应解析失败：${text.take(120)}", error)
        }
    }

    private fun validate(parsed: JsonObject, successCodes: Set<Int>?) {
        if (successCodes == null) return
        val code = parsed["code"]?.jsonPrimitive?.intOrNull
            ?: throw NeteaseApiException.parse("响应中缺少 code 字段：${parsed.toString().take(120)}")
        if (code in successCodes) return

        val message = parsed["message"]?.jsonPrimitive?.contentOrNull
            ?: parsed["msg"]?.jsonPrimitive?.contentOrNull
            ?: parsed["data"]?.let { data ->
                runCatching { data.jsonPrimitive.contentOrNull }.getOrNull()
            }
            ?: defaultMessageFor(code)
        throw NeteaseApiException(code, message)
    }

    private fun defaultMessageFor(code: Int): String = when (code) {
        NeteaseConstants.CODE_NEED_LOGIN, 250 -> "需要登录网易云账号"
        NeteaseConstants.QR_EXPIRED -> "二维码已过期"
        NeteaseConstants.QR_WAITING_SCAN -> "等待扫码"
        NeteaseConstants.QR_WAITING_CONFIRM -> "已扫码，请在手机上确认"
        else -> "网易云接口返回错误码 $code"
    }

    /**
     * 拼接 URL。`path` 里已有的查询参数会保留，`query` 中值为 null 的键会被跳过。
     */
    private fun buildUrl(path: String, query: Map<String, String?>): String {
        val raw = if (path.startsWith("http")) {
            path
        } else {
            baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
        val url = raw.toHttpUrlOrNull()
            ?: throw NeteaseApiException.parse("非法请求地址：$raw")
        val builder = url.newBuilder()
        query.forEach { (key, value) ->
            if (value != null) builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    /**
     * 把对象序列化成 JSON 供接口使用。放在这里是为了让调用方不用感知 serialization 细节。
     */
    fun encodeToString(element: kotlinx.serialization.json.JsonElement): String =
        element.toString()

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 20L
    }
}

/** 需要读取原始响应头的场景（游客登录）使用。 */
data class NeteaseRawResponse(
    val data: JsonObject,
    val setCookieHeaders: List<String>
) {
    /** 从 `Set-Cookie` 里提取 `NMTID` 的裸 cookie 串。 */
    fun extractNmTid(): String? {
        val line = setCookieHeaders.firstOrNull {
            it.trim().startsWith("${NeteaseConstants.NMTID_NAME}=")
        } ?: return null
        val semicolon = line.indexOf(';')
        return (if (semicolon == -1) line else line.substring(0, semicolon)).trim()
    }
}
