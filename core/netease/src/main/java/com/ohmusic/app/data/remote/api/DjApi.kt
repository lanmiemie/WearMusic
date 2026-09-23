package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.data.remote.dto.NeteaseSongDto
import com.ohmusic.app.data.remote.dto.SongMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import javax.inject.Inject
import javax.inject.Singleton

/** 播客（电台）摘要。 */
data class CloudDj(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val description: String,
    val djNickname: String,
    val djAvatarUrl: String,
    val programCount: Int,
    val subCount: Long,
    val playCount: Long,
    val category: String
)

/** 播客单期节目。 */
data class DjProgram(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val durationMs: Long,
    val createTimeMs: Long,
    val listenerCount: Long,
    /** 该期节目对应的可播放歌曲（`mainSong`），部分节目为空。 */
    val song: Song?
)

/**
 * 从任意电台 JSON 对象解析摘要，搜索（type=1009）与收藏列表共用。
 */
internal fun parseDjSummary(obj: JsonObject): CloudDj {
    val dj = runCatching { obj["dj"]?.jsonObject }.getOrNull()
    return CloudDj(
        id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
        coverUrl = obj["picUrl"]?.jsonPrimitive?.content
            ?: obj["coverUrl"]?.jsonPrimitive?.content.orEmpty(),
        description = obj["desc"]?.jsonPrimitive?.content
            ?: obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        djNickname = dj?.get("nickname")?.jsonPrimitive?.content.orEmpty().ifBlank { "未知主播" },
        djAvatarUrl = dj?.get("avatarUrl")?.jsonPrimitive?.content.orEmpty(),
        programCount = obj["programCount"]?.jsonPrimitive?.intOrNull ?: 0,
        subCount = obj["subCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        playCount = obj["playCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        category = obj["category"]?.jsonPrimitive?.content
            ?: obj["name"]?.let { "" }.orEmpty()
    )
}

/**
 * 播客（电台）接口：详情、节目列表、收藏、收藏列表、推荐。
 *
 * `dj/detail` / `dj/program` / `dj/recommend` 匿名可用；
 * 收藏与收藏列表需要登录。
 */
@Singleton
class DjApi @Inject constructor(
    private val client: NeteaseClient,
    private val cloudSongApi: CloudSongApi
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 播客详情。 */
    suspend fun getDjDetail(rid: Long): CloudDj = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/dj/detail",
            query = mapOf(
                "rid" to rid.toString(),
                "timestamp" to now()
            ),
            withCookie = false
        )
        val radio = response["djRadio"]?.jsonObject
            ?: throw IllegalStateException("播客不存在或已下架")
        parseDjSummary(radio)
    }

    /** 节目列表（按发布时间倒序），带分页。 */
    suspend fun getDjPrograms(
        rid: Long,
        limit: Int = 30,
        offset: Int = 0
    ): List<DjProgram> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/dj/program",
            query = mapOf(
                "rid" to rid.toString(),
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "asc" to "false",
                "timestamp" to now()
            ),
            withCookie = false
        )
        val raw = response["programs"] ?: return@withContext emptyList()
        if (raw !is JsonArray) return@withContext emptyList()
        raw.jsonArray.mapNotNull { element ->
            runCatching { parseProgram(element.jsonObject) }.getOrNull()
        }
    }

    /** 收藏 / 取消收藏播客。`t=1` 收藏，`t=2` 取消。需要登录。 */
    suspend fun subscribeDj(rid: Long, subscribe: Boolean) {
        client.post(
            path = "/dj/sub",
            query = mapOf(
                "rid" to rid.toString(),
                "t" to if (subscribe) "1" else "2",
                "timestamp" to now()
            )
        )
    }

    /** 当前账号收藏的播客列表。需要登录。 */
    suspend fun getSubscribedDjs(limit: Int = 100): List<CloudDj> =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/dj/sublist",
                query = mapOf(
                    "limit" to limit.toString(),
                    "timestamp" to now()
                )
            )
            parseDjArray(response["djRadios"])
        }

    /** 推荐播客（匿名可用）。 */
    suspend fun getRecommendDjs(limit: Int = 12): List<CloudDj> =
        withContext(Dispatchers.IO) {
            val response = runCatching {
                client.post(
                    path = "/dj/recommend",
                    query = mapOf(
                        "limit" to limit.toString(),
                        "timestamp" to now()
                    ),
                    withCookie = false
                )
            }.getOrNull() ?: return@withContext emptyList()
            parseDjArray(response["djRadios"])
        }

    private fun parseDjArray(raw: kotlinx.serialization.json.JsonElement?): List<CloudDj> {
        if (raw !is JsonArray) return emptyList()
        return raw.mapNotNull { element ->
            runCatching { parseDjSummary(element.jsonObject) }.getOrNull()
        }
    }

    private fun parseProgram(obj: JsonObject): DjProgram {
        // mainSong 是旧版歌曲 DTO（artists/album/duration），与 NeteaseSongDto 兼容。
        val mainSong = obj["mainSong"]?.jsonObject
        val song = mainSong?.let {
            runCatching { SongMapper.toSong(json.decodeFromJsonElement(serializer<NeteaseSongDto>(), it)) }
                .getOrNull()
        }
        return DjProgram(
            id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
            coverUrl = obj["coverUrl"]?.jsonPrimitive?.content
                ?: obj["mainSong"]?.jsonObject?.get("album")?.jsonObject
                    ?.get("picUrl")?.jsonPrimitive?.content.orEmpty(),
            durationMs = obj["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            createTimeMs = obj["createTime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            listenerCount = obj["listenerCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            song = song
        )
    }

    private fun now(): String = System.currentTimeMillis().toString()
}
