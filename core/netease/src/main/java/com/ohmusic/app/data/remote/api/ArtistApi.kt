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

/** 歌手摘要（搜索结果、专辑归属等场景共用）。 */
data class CloudArtist(
    val id: Long,
    val name: String,
    val avatarUrl: String,
    /** 歌手别名，如英文名/拼音。 */
    val alias: String = "",
    val musicSize: Int = 0,
    val albumSize: Int = 0
)

/** 歌手详情（`/artists` 的 artist 字段）。 */
data class CloudArtistDetail(
    val artist: CloudArtist,
    val description: String
)

/**
 * 从任意歌手 JSON 对象解析摘要，搜索（type=100）与歌手专辑列表共用。
 */
internal fun parseArtistSummary(obj: JsonObject): CloudArtist {
    val alias = runCatching {
        obj["alias"]?.jsonArray
            ?.joinToString(" / ") { it.jsonPrimitive.content }
            .orEmpty()
    }.getOrNull().orEmpty()
    return CloudArtist(
        id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
        avatarUrl = obj["picUrl"]?.jsonPrimitive?.content
            ?: obj["img1v1Url"]?.jsonPrimitive?.content.orEmpty(),
        alias = alias,
        musicSize = obj["musicSize"]?.jsonPrimitive?.intOrNull ?: 0,
        albumSize = obj["albumSize"]?.jsonPrimitive?.intOrNull ?: 0
    )
}

/**
 * 歌手接口：详情、热门单曲、全部单曲（分页）、专辑列表。
 *
 * 实测均为匿名可用。
 */
@Singleton
class ArtistApi @Inject constructor(
    private val client: NeteaseClient,
    private val cloudSongApi: CloudSongApi
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 歌手详情（含热门单曲一并返回，减少一次请求）。 */
    suspend fun getArtistDetail(artistId: Long): CloudArtistDetail =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/artists",
                query = mapOf(
                    "id" to artistId.toString(),
                    "timestamp" to now()
                ),
                withCookie = false
            )
            val artistObj = response["artist"]?.jsonObject
                ?: throw IllegalStateException("歌手不存在或已下架")
            CloudArtistDetail(
                artist = parseArtistSummary(artistObj),
                description = artistObj["briefDesc"]?.jsonPrimitive?.content
                    ?: artistObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }

    /** 热门单曲（`/artists` 的 hotSongs，一般 50 首）。 */
    suspend fun getArtistHotSongs(artistId: Long): List<Song> =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/artists",
                query = mapOf(
                    "id" to artistId.toString(),
                    "timestamp" to now()
                ),
                withCookie = false
            )
            val raw = response["hotSongs"] ?: return@withContext emptyList()
            if (raw !is JsonArray) return@withContext emptyList()
            SongMapper.toSongs(decodeList<NeteaseSongDto>(raw))
        }

    /**
     * 全部单曲，按 [order] 排序（`hot`=最热，`time`=最新），带分页。
     */
    suspend fun getArtistSongs(
        artistId: Long,
        order: String = "hot",
        limit: Int = 50,
        offset: Int = 0
    ): List<Song> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/artist/songs",
            query = mapOf(
                "id" to artistId.toString(),
                "order" to order,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "timestamp" to now()
            ),
            withCookie = false
        )
        cloudSongApi.extractSongs(response)
    }

    /** 歌手专辑列表，带分页。 */
    suspend fun getArtistAlbums(
        artistId: Long,
        limit: Int = 30,
        offset: Int = 0
    ): List<CloudAlbumSummary> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/artist/album",
            query = mapOf(
                "id" to artistId.toString(),
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "timestamp" to now()
            ),
            withCookie = false
        )
        val raw = response["hotAlbums"] ?: return@withContext emptyList()
        if (raw !is JsonArray) return@withContext emptyList()
        raw.jsonArray.mapNotNull { element ->
            runCatching { parseAlbumSummary(element.jsonObject) }.getOrNull()
        }
    }

    private inline fun <reified T> decodeList(array: JsonArray): List<T> =
        array.map { json.decodeFromJsonElement(serializer<T>(), it) }

    private fun now(): String = System.currentTimeMillis().toString()
}
