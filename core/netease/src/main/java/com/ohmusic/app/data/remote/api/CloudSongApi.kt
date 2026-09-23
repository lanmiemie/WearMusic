package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.EapiClient
import com.ohmusic.app.data.remote.NeteaseApiException
import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.data.remote.dto.NeteaseLyricResponse
import com.ohmusic.app.data.remote.dto.NeteaseSongDetailResponse
import com.ohmusic.app.data.remote.dto.NeteaseSongDto
import com.ohmusic.app.data.remote.dto.NeteaseSongUrlResponse
import com.ohmusic.app.data.remote.dto.SongMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在线曲目接口：播放地址、歌曲详情、歌词。
 */
@Singleton
class CloudSongApi @Inject constructor(
    private val client: NeteaseClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** eapi 直连客户端（歌词逐字数据源）。 */
    private val eapi = EapiClient()

    /**
     * 获取可播放的音频直链。
     *
     * 网易云 CDN 直链带时间戳路径，**有效期很短且每次都不一样**，
     * 因此每次播放前实时换取，不做缓存。
     *
     * @throws NeteaseApiException 无版权 / 需付费试听时 url 为空，会以 -3 抛出。
     */
    suspend fun getSongUrl(songId: Long, bitrate: Int = DEFAULT_BITRATE): String =
        withContext(Dispatchers.IO) {
            val urls = getSongUrls(listOf(songId), bitrate)
            val url = urls[songId]
            if (url.isNullOrBlank()) {
                // 该曲目在服务端没有可用音源：无版权、需要付费或已下架。
                throw NeteaseApiException(-3, "该歌曲暂无可用音源（可能是版权或会员限制）")
            }
            url
        }

    /**
     * 批量获取音源直链。
     *
     * `/song/url` 的 `id` 参数接受逗号分隔的多个 id，一次请求即可拿到整批结果。
     * 实测 100 首一次请求约 1～2 秒，而逐个请求（即便并发 6）需要 17 轮以上，
     * 这是整张歌单点播慢的主因，因此批量播放一律走这里。
     *
     * 单次请求的 id 数量做上限切分，避免超长 URL 被服务端截断。
     *
     * @return `songId -> 直链`，无音源的曲目不会出现在结果里。
     */
    suspend fun getSongUrls(
        songIds: List<Long>,
        bitrate: Int = DEFAULT_BITRATE
    ): Map<Long, String> = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<Long, String>()

        songIds.distinct().chunked(MAX_BATCH_SIZE).forEach { batch ->
            val response = runCatching {
                client.post(
                    path = "/song/url",
                    query = mapOf(
                        "id" to batch.joinToString(","),
                        "br" to bitrate.toString(),
                        "timestamp" to now()
                    )
                )
            }.getOrNull() ?: return@forEach

            val typed = runCatching { decode<NeteaseSongUrlResponse>(response) }.getOrNull()
                ?: return@forEach
            typed.data.forEach { item ->
                val url = item.url
                if (!url.isNullOrBlank()) result[item.id] = url
            }
        }
        result
    }

    /** 批量获取歌曲详情。 */
    suspend fun getSongDetails(songIds: List<Long>): List<Song> = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext emptyList()
        val response = client.post(
            path = "/song/detail",
            query = mapOf(
                "ids" to songIds.joinToString(","),
                "timestamp" to now()
            )
        )
        val typed = decode<NeteaseSongDetailResponse>(response)
        SongMapper.toSongs(typed.songs)
    }

    /** 获取歌词（含翻译）。 */
    suspend fun getLyric(songId: Long): NeteaseLyricResponse = withContext(Dispatchers.IO) {
        // 优先 eapi 直连官方：能拿到逐字歌词（yrc）；失败时降级自建网关。
        val direct = runCatching { getLyricViaEapi(songId) }.getOrNull()
        if (direct != null && direct.hasContent) {
            return@withContext direct
        }
        val response = client.post(
            path = "/lyric/new",
            query = mapOf(
                "id" to songId.toString(),
                "timestamp" to now()
            )
        )
        decode<NeteaseLyricResponse>(response)
    }

    /**
     * eapi 直连官方歌词接口（`/api/song/lyric/v1`）。
     *
     * `cv` 为客户端版本号，逐字歌词（yrc）只在较新的版本号下返回。
     * 网关部署普遍不转发该参数，因此这里绕开网关直连。
     */
    private suspend fun getLyricViaEapi(songId: Long): NeteaseLyricResponse {
        val payload = buildJsonObject {
            put("id", songId.toString())
            put("cv", EAPI_CLIENT_VERSION)
            put("lv", 0)
            put("kv", 0)
            put("tv", 0)
            put("rv", 0)
            put("yv", 0)
            put("ytv", 0)
            put("yrv", 0)
        }
        val response = eapi.post("/api/song/lyric/v1", payload)
        val code = response["code"]?.let { element ->
            runCatching { element.jsonPrimitive.intOrNull }.getOrNull()
        } ?: -1
        if (code != 200) {
            throw NeteaseApiException(code, "eapi 歌词接口返回错误码 $code")
        }
        return decode<NeteaseLyricResponse>(response)
    }

    /**
     * 从任意响应里取出 `songs` / `tracks` 数组并归一化。
     * 供歌单详情、榜单等接口复用。
     */
    fun extractSongs(response: kotlinx.serialization.json.JsonObject): List<Song> {
        val candidates = listOf("songs", "tracks", "dailySongs")
        candidates.forEach { key ->
            val raw = response[key]
            if (raw is JsonArray) {
                return SongMapper.toSongs(decodeList<NeteaseSongDto>(raw))
            }
        }
        // 嵌套在 data / playlist 下
        listOf("data", "playlist", "result").forEach { key ->
            val nested = response[key]?.let { runCatching { it.jsonObject }.getOrNull() }
            if (nested != null) {
                val found = extractSongs(nested)
                if (found.isNotEmpty()) return found
            }
        }
        return emptyList()
    }

    private inline fun <reified T> decode(element: kotlinx.serialization.json.JsonObject): T =
        json.decodeFromJsonElement(serializer(), element)

    private inline fun <reified T> decodeList(array: JsonArray): List<T> =
        array.jsonArray.map { json.decodeFromJsonElement(serializer(), it) }

    private inline fun <reified T> serializer() =
        kotlinx.serialization.serializer<T>()

    private fun now(): String = System.currentTimeMillis().toString()

    companion object {
        /** 默认取 320kbps；服务端会在无该码率时自动降级。 */
        const val DEFAULT_BITRATE = 320_000

        /** eapi 歌词接口要求的客户端版本号：低于该值官方不下发逐字歌词（yrc）。 */
        const val EAPI_CLIENT_VERSION = 35883748

        /** 单次 `/song/url` 请求最多携带的 id 数，避免 URL 过长被截断。 */
        private const val MAX_BATCH_SIZE = 100
    }
}
