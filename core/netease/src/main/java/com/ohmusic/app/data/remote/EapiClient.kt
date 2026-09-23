package com.ohmusic.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 eapi 加密直连客户端。
 *
 * 网易云官方 App 走 eapi 通道：请求参数序列化成 JSON 后，
 * 以 `nobody{path}use{text}md5forencrypt` 的 MD5 作摘要拼接，
 * 再用通用密钥做 AES-128-ECB 加密，以 `params=<HEX>` 表单提交。
 *
 * 部分自建网关（NeteaseCloudMusicApi 旧版）不转发逐字歌词（yrc）
 * 的版本参数，导致歌词接口拿不到逐字数据；歌词等只读接口对
 * 未登录客户端同样开放，因此这里直连官方域名补齐。
 */
class EapiClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 发送 eapi 加密 POST 请求。
     *
     * @param apiPath 网关侧路径（形如 `/api/song/lyric/v1`），
     *   实际请求地址为 `https://interface3.music.163.com/eapi{apiPath}`。
     * @param payload 业务参数对象（序列化后参与摘要与加密）。
     * @throws NeteaseApiException 网络 / HTTP / 解析失败。
     */
    suspend fun post(apiPath: String, payload: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val text = payload.toString()
            val digest = md5Hex("nobody$apiPath" + "use$text" + "md5forencrypt")
            val plain = "$apiPath-36cd479b6b5-$text-36cd479b6b5-$digest"
            val encrypted = aesEcbHexUppercase(plain.toByteArray(Charsets.UTF_8))

            val request = Request.Builder()
                .url(EAPI_BASE + "/eapi" + apiPath)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", EAPI_USER_AGENT)
                .header("Cookie", EAPI_COOKIE)
                .post("params=$encrypted".toRequestBody(FORM_MEDIA_TYPE))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw NeteaseApiException(response.code, "eapi 请求失败 HTTP ${response.code}")
                    }
                    val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
                    (parsed as? JsonObject)
                        ?: throw NeteaseApiException.parse("eapi 响应解析失败：${body.take(120)}")
                }
            } catch (error: NeteaseApiException) {
                throw error
            } catch (error: Exception) {
                throw NeteaseApiException.network(error)
            }
        }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun aesEcbHexUppercase(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY, "AES"))
        return cipher.doFinal(plain).joinToString("") { "%02X".format(it) }
    }

    private companion object {
        const val EAPI_BASE = "https://interface3.music.163.com"

        /** 客户端通用 eapi 密钥（16 字节，AES-128）。 */
        val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)

        const val EAPI_USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 12; WearMusic)"

        /** 只读接口通行的客户端标识 cookie，无需登录凭据。 */
        const val EAPI_COOKIE = "os=android; appver=9.1.30; osver=Android%2012"

        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    }
}
