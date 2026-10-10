package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.remote.NeteaseApiException
import com.ohmusic.app.data.remote.NeteaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 歌曲百科解析结果（只保留手表上有价值的信息）。 */
data class SongWikiInfo(
    /** 云端记录的第一次听时间戳（毫秒），null 表示还没有播放记录。 */
    val firstListenAt: Long?,
    /** 第一次听时段描述，如「金秋 · 中午 12:02 听」。 */
    val firstListenDesc: String?,
    /** 云端累计播放次数。 */
    val totalPlayCount: Int?,
    /** 创作信息（title -> 内容），如「作曲 Composer」->「xxx」。 */
    val credits: List<Pair<String, String>>
)

/**
 * 网易云「歌曲百科 / songWiki」接口（eapi 直连）。
 *
 * 对应官方 App 播放页的百科卡片，其中 `songWikiFirstListen`
 * 提供云端记录的**第一次听时间**与**累计播放次数**（听歌排行
 * `/user/record` 不含这两个字段），另有创作信息等区块。
 *
 * 协议：请求体整体 AES-128-ECB 加密为 `params=<HEX>` 表单；
 * 响应可能是明文 JSON 或 eapi 加密（AES 解密后还可能是一层 gzip）。
 *
 * 传输层用 HttpURLConnection 而非 OkHttp：该域名对部分客户端
 * TLS/HTTP 指纹返回 200 空体，JDK 栈实测可用（Android 同栈）。
 */
class SongWikiApi(private val client: NeteaseClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 拉取歌曲百科；接口没有可用区块时抛出解析异常。 */
    suspend fun wiki(songId: Long): SongWikiInfo = withContext(Dispatchers.IO) {
        val cookie = client.cookie()
        val csrf = parseCookieValue(cookie, "__csrf").orEmpty()
        val musicU = parseCookieValue(cookie, "MUSIC_U").orEmpty()

        val header = linkedMapOf(
            "osver" to "13",
            "deviceId" to WIKI_DEVICE_ID,
            "os" to "android",
            "appver" to "9.6.05",
            "versioncode" to "9006005",
            "buildver" to "260923162310",
            "resolution" to "2260x1080",
            "__csrf" to csrf,
            "channel" to "xiaomi",
            "requestId" to "${System.currentTimeMillis()}_0001"
        ).also { if (musicU.isNotEmpty()) it["MUSIC_U"] = musicU }

        val extJson = buildJsonObject {
            putJsonObject("states") {
                putJsonObject("playingResource") {
                    // 注意：官方端点要求字符串形式，传数字会返回空体
                    put("current", songId.toString())
                    put("scene", "songWiki")
                }
            }
        }.toString()

        val data = buildJsonObject {
            put("extJson", extJson)
            put("positionCode", "songWikiMainPosition")
            put("e_r", false)
            put("header", JsonObject(header.mapValues { JsonPrimitive(it.value) }))
        }

        val text = data.toString()
        val digest = md5Hex("nobody$API_PATH" + "use$text" + "md5forencrypt")
        val plain = "$API_PATH-36cd479b6b5-$text-36cd479b6b5-$digest"
        val hex = aesEcbEncrypt(plain.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

        val cookieHeader = header.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val conn = (URL(EAPI_BASE + "/eapi" + API_PATH.removePrefix("/api"))
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", WIKI_USER_AGENT)
            setRequestProperty("Cookie", cookieHeader)
        }

        val body: JsonObject = try {
            try {
                conn.outputStream.use { it.write("params=$hex".toByteArray(Charsets.UTF_8)) }
                var bytes = conn.inputStream.use { it.readBytes() }
                if (conn.contentEncoding == "gzip") {
                    bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                }
                decodeResponse(bytes)
            } finally {
                conn.disconnect()
            }
        } catch (error: NeteaseApiException) {
            throw error
        } catch (error: Exception) {
            throw NeteaseApiException.network(error)
        }

        val code = body["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 200) {
            throw NeteaseApiException.parse(
                "songWiki 返回 code=$code：${body["message"]?.toString().orEmpty()}"
            )
        }
        parseBlocks(body["data"] as? JsonObject)
            ?: throw NeteaseApiException.parse("暂无该歌曲的百科数据")
    }

    /** 宽松解析 blocks：逐个 bizCode 取需要的字段，缺什么跳什么。 */
    private fun parseBlocks(data: JsonObject?): SongWikiInfo? {
        val blocks = data?.get("blocks") as? JsonArray ?: return null
        var firstListenAt: Long? = null
        var firstListenDesc: String? = null
        var totalPlayCount: Int? = null
        val credits = mutableListOf<Pair<String, String>>()

        for (block in blocks) {
            val obj = block as? JsonObject ?: continue
            val rn = obj["rnData"] as? JsonObject ?: continue
            when (obj["bizCode"]?.jsonPrimitive?.content) {
                "songWikiFirstListen" -> {
                    val fl = rn["firstListen"] as? JsonObject
                    firstListenAt = fl?.get("timestamp")?.jsonPrimitive?.content?.toLongOrNull()
                    val period = fl?.get("period")?.jsonPrimitive?.content
                    val season = fl?.get("season")?.jsonPrimitive?.content
                    val time = fl?.get("time")?.jsonPrimitive?.content
                    firstListenDesc = listOfNotNull(season, period, time?.let { "$it 听" })
                        .joinToString(" · ").ifEmpty { null }
                    totalPlayCount = (rn["totalPlay"] as? JsonObject)
                        ?.get("playCount")?.jsonPrimitive?.content?.toIntOrNull()
                }
                "songDetailNewSongWiki" -> {
                    val blocksArr = rn["blocks"] as? JsonArray ?: continue
                    for (b in blocksArr) {
                        val info = (b as? JsonObject)?.get("blockInfo") as? JsonObject ?: continue
                        val elements = info["wikiSubElementVos"] as? JsonArray ?: continue
                        for (el in elements) {
                            val eo = el as? JsonObject ?: continue
                            val title = eo["title"]?.jsonPrimitive?.content ?: continue
                            val metas = eo["wikiSubMetaVos"] as? JsonArray ?: continue
                            val names = metas.mapNotNull { m ->
                                (m as? JsonObject)?.get("text")?.jsonPrimitive?.content
                            }.filter { it.isNotBlank() }
                            if (names.isNotEmpty()) credits.add(title to names.joinToString("、"))
                        }
                    }
                }
            }
        }
        if (firstListenAt == null && totalPlayCount == null && credits.isEmpty()) return null
        return SongWikiInfo(firstListenAt, firstListenDesc, totalPlayCount, credits)
    }

    /** 响应可能是明文 JSON，也可能是 eapi 加密（解密后可能还有一层 gzip）。 */
    private fun decodeResponse(raw: ByteArray): JsonObject {
        if (raw.isEmpty()) throw NeteaseApiException.parse("songWiki 响应为空")
        if (raw[0] == '{'.code.toByte()) {
            return json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        }
        var plain = aesEcbDecrypt(raw)
        if (plain.size >= 2 && plain[0] == 0x1F.toByte() && plain[1] == 0x8B.toByte()) {
            plain = GZIPInputStream(plain.inputStream()).use { it.readBytes() }
        }
        return json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
    }

    private fun aesEcbEncrypt(input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY, "AES"))
        return cipher.doFinal(input)
    }

    /** eapi 响应的填充不保证标准 PKCS5，失败则按 NoPadding 返回原始明文。 */
    private fun aesEcbDecrypt(input: ByteArray): ByteArray {
        val key = SecretKeySpec(EAPI_KEY, "AES")
        return try {
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, key)
                doFinal(input)
            }
        } catch (error: Exception) {
            Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key)
                doFinal(input)
            }
        }
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun parseCookieValue(cookie: String, name: String): String? =
        cookie.split(';')
            .mapNotNull {
                val parts = it.trim().split('=', limit = 2)
                if (parts.size == 2) parts else null
            }
            .firstOrNull { it[0] == name }?.get(1)

    private companion object {
        const val EAPI_BASE = "https://interface.music.163.com"
        const val API_PATH = "/api/link/page/parent/relation/construct/info"

        /** 客户端通用 eapi 密钥（16 字节，AES-128）。 */
        val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)

        /** 官方 App 使用的移动端 UA（已验证可用）。 */
        const val WIKI_USER_AGENT = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"

        /** 请求里携带的设备信息（只读接口，非真实设备亦可）。 */
        const val WIKI_DEVICE_ID = "d36d718628353fb4"
    }
}
