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

/** 专辑摘要（歌手专辑列表、搜索、收藏列表共用）。 */
data class CloudAlbumSummary(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val artistName: String,
    val artistId: Long,
    /** 收录歌曲数（专辑对象的 `size` 字段）。 */
    val songCount: Int,
    val publishTimeMs: Long = 0L,
    val company: String = "",
    /** 当前账号是否已收藏。`/album` 匿名响应不含该字段，由 ViewModel 登录态下校准。 */
    val subscribed: Boolean = false
)

/** 专辑详情（`/album` 接口）。 */
data class CloudAlbumDetail(
    val album: CloudAlbumSummary,
    val description: String,
    val songs: List<Song>
)

/**
 * 从任意专辑 JSON 对象解析摘要，搜索（type=10）与歌手专辑列表共用。
 *
 * 歌手字段的形态随接口而异：搜索/歌手页给 `artist{...}` 对象；
 * 收藏专辑列表（/album/sublist）实测只有扁平的 `artistName`/`artistId`
 * 字符串字段；还有的旧接口用 `artists[]` 数组。三处都尝试，防止
 * 「未知歌手」刷屏。
 */
internal fun parseAlbumSummary(obj: JsonObject): CloudAlbumSummary {
    val artist = runCatching { obj["artist"]?.jsonObject }.getOrNull()
    val artistsFirst = runCatching {
        obj["artists"]?.jsonArray?.firstOrNull()?.jsonObject
    }.getOrNull()
    val artistName = artist?.get("name")?.jsonPrimitive?.content
        ?: artistsFirst?.get("name")?.jsonPrimitive?.content
        ?: obj["artistName"]?.jsonPrimitive?.content
        ?: ""
    val artistId = artist?.get("id")?.jsonPrimitive?.content?.toLongOrNull()
        ?: artistsFirst?.get("id")?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj["artistId"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: 0L
    return CloudAlbumSummary(
        id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
        coverUrl = obj["picUrl"]?.jsonPrimitive?.content
            ?: obj["blurPicUrl"]?.jsonPrimitive?.content.orEmpty(),
        artistName = artistName.ifBlank { "未知歌手" },
        artistId = artistId,
        songCount = obj["size"]?.jsonPrimitive?.intOrNull ?: 0,
        publishTimeMs = obj["publishTime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        company = obj["company"]?.jsonPrimitive?.content.orEmpty(),
        subscribed = obj["subscribed"]?.jsonPrimitive?.content?.toBoolean() ?: false
    )
}

/**
 * 专辑接口：详情、收藏、收藏列表。
 */
@Singleton
class AlbumApi @Inject constructor(
    private val client: NeteaseClient,
    private val cloudSongApi: CloudSongApi
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 专辑详情（含完整曲目）。匿名可用。 */
    suspend fun getAlbumDetail(albumId: Long): CloudAlbumDetail =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/album",
                query = mapOf(
                    "id" to albumId.toString(),
                    "timestamp" to now()
                ),
                withCookie = false
            )
            val albumObj = response["album"]?.jsonObject
                ?: throw IllegalStateException("专辑不存在或已下架")

            // 曲目列表挂在**响应顶层** `songs`（实测主流专辑如此）；
            // 部分旧数据只放在 album.songs 下，两处都取、顶层优先。
            // 版权受限专辑两处都可能为空（如周杰伦），此时按无曲目展示。
            val songsArray = response["songs"] ?: albumObj["songs"]
            val songs = if (songsArray is JsonArray) {
                SongMapper.toSongs(decodeList<NeteaseSongDto>(songsArray))
            } else {
                emptyList()
            }

            CloudAlbumDetail(
                album = parseAlbumSummary(albumObj),
                description = albumObj["description"]?.jsonPrimitive?.contentOrNull
                    ?: albumObj["briefDesc"]?.jsonPrimitive?.content.orEmpty(),
                songs = songs
            )
        }

    /** 收藏 / 取消收藏专辑。`t=1` 收藏，`t=2` 取消。需要登录。 */
    suspend fun subscribeAlbum(albumId: Long, subscribe: Boolean) {
        client.post(
            path = "/album/sub",
            query = mapOf(
                "id" to albumId.toString(),
                "t" to if (subscribe) "1" else "2",
                "timestamp" to now()
            )
        )
    }

    /** 当前账号收藏的专辑列表。需要登录。 */
    suspend fun getSubscribedAlbums(limit: Int = 100): List<CloudAlbumSummary> =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/album/sublist",
                query = mapOf(
                    "limit" to limit.toString(),
                    "timestamp" to now()
                )
            )
            val raw = response["data"] ?: return@withContext emptyList()
            if (raw !is JsonArray) return@withContext emptyList()
            raw.jsonArray.mapNotNull { element ->
                runCatching { parseAlbumSummary(element.jsonObject) }.getOrNull()
            }
        }

    private inline fun <reified T> decodeList(array: JsonArray): List<T> =
        array.map { json.decodeFromJsonElement(serializer<T>(), it) }

    private fun now(): String = System.currentTimeMillis().toString()
}
