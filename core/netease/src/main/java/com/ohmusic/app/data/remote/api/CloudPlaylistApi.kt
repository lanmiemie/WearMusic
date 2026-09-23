package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.NeteaseClient
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
import javax.inject.Inject
import javax.inject.Singleton

/** 歌单摘要（列表页用）。 */
data class CloudPlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val trackCount: Int,
    val playCount: Long,
    val creatorNickname: String,
    /** 创建者网易云 uid，用于判断当前账号能否编辑该歌单。 */
    val creatorUserId: Long = 0L,
    val description: String,
    /** 是否为「我喜欢的音乐」特殊歌单。 */
    val specialType: Int = 0,
    /** 当前账号是否已收藏该歌单。 */
    val subscribed: Boolean = false
) {
    val isLikedPlaylist: Boolean get() = specialType == 5
}

/** 歌单详情（详情页用）。 */
data class CloudPlaylistDetail(
    val playlist: CloudPlaylist,
    val songs: List<Song>
)

/**
 * 歌单相关接口：我的歌单、歌单详情、收藏、增删歌曲。
 */
@Singleton
class CloudPlaylistApi @Inject constructor(
    private val client: NeteaseClient,
    private val cloudSongApi: CloudSongApi
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * 获取用户的全部歌单（含创建与收藏），对应 `/user/playlist`。
     *
     * 参考项目用的是该部署自定义的 `/user/playlist/create` 与 `/user/playlist/collect`
     * 两个拆分接口；这里优先用官方 `/user/playlist`，失败时再退回拆分接口，
     * 以兼容不同网关版本。
     */
    suspend fun getUserPlaylists(uid: Long, limit: Int = 100, offset: Int = 0): List<CloudPlaylist> =
        withContext(Dispatchers.IO) {
            val query = mapOf(
                "uid" to uid.toString(),
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "timestamp" to now()
            )
            val direct = runCatching { client.post(path = "/user/playlist", query = query) }.getOrNull()
            val fromDirect = direct?.let(::parsePlaylistArray)
            if (!fromDirect.isNullOrEmpty()) return@withContext fromDirect

            val created = runCatching {
                client.post(
                    path = "/user/playlist/create",
                    query = mapOf(
                        "uid" to uid.toString(),
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                        "timestamp" to now()
                    )
                )
            }.getOrNull()?.let(::parsePlaylistArray).orEmpty()

            val collected = runCatching {
                client.post(
                    path = "/user/playlist/collect",
                    query = mapOf(
                        "uid" to uid.toString(),
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                        "timestamp" to now()
                    )
                )
            }.getOrNull()?.let(::parsePlaylistArray).orEmpty()

            (created + collected).distinctBy { it.id }
        }

    /** 获取歌单详情，含完整曲目列表。 */
    suspend fun getPlaylistDetail(playlistId: Long): CloudPlaylistDetail =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/playlist/detail",
                query = mapOf(
                    "id" to playlistId.toString(),
                    "timestamp" to now()
                )
            )
            val playlistObject = response["playlist"]?.jsonObject
                ?: throw IllegalStateException("歌单不存在或无权访问")

            // trackIds 给出全量 id，但 songs 只带前若干首。
            // 若曲目数量明显少于 trackCount，用 /song/detail 补齐。
            val inlineSongs = cloudSongApi.extractSongs(response)
            val trackCount = playlistObject["trackCount"]?.jsonPrimitive?.intOrNull ?: inlineSongs.size
            val songs = if (inlineSongs.size < trackCount) {
                val ids = extractTrackIds(playlistObject)
                if (ids.size > inlineSongs.size) {
                    fetchSongsInChunks(ids)
                } else {
                    inlineSongs
                }
            } else {
                inlineSongs
            }

            CloudPlaylistDetail(
                playlist = parsePlaylist(playlistObject),
                songs = songs
            )
        }

    /** 收藏 / 取消收藏歌单。`t=1` 收藏，`t=2` 取消。 */
    suspend fun subscribePlaylist(playlistId: Long, subscribe: Boolean) {
        client.post(
            path = "/playlist/subscribe",
            query = mapOf(
                "t" to if (subscribe) "1" else "2",
                "id" to playlistId.toString(),
                "timestamp" to now()
            )
        )
    }

    /**
     * 更新歌单名称与描述。
     *
     * `/playlist/update` 返回的是**聚合响应**：顶层没有 `code`，
     * 每个子操作的结果挂在以接口路径为键的字段下（键形如 `/api/playlist/tags/update`）。
     * 这里走 [NeteaseClient.postAggregate]，全部子操作成功才算成功。
     */
    suspend fun updatePlaylist(playlistId: Long, name: String, description: String) {
        client.postAggregate(
            path = "/playlist/update",
            query = mapOf(
                "id" to playlistId.toString(),
                "name" to name,
                "desc" to description,
                "timestamp" to now()
            )
        )
    }

    /** 新建歌单，返回新歌单 id。 */
    suspend fun createPlaylist(name: String, isPrivate: Boolean): Long =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/playlist/create",
                query = mapOf(
                    "name" to name,
                    "privacy" to if (isPrivate) "10" else "0",
                    "timestamp" to now()
                )
            )
            response["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        }

    /** 删除歌单。 */
    suspend fun deletePlaylist(playlistId: Long) {
        client.post(
            path = "/playlist/delete",
            query = mapOf(
                "id" to playlistId.toString(),
                "timestamp" to now()
            )
        )
    }

    /**
     * 对歌单添加或移除歌曲。
     *
     * 该接口的响应被服务端包了一层（`{ status, body: { code } }`），
     * 与其它接口形态不同，因此用 `postRaw` 语义单独处理：直接判断 HTTP 与 body.code。
     */
    suspend fun modifyPlaylistTracks(playlistId: Long, songIds: List<Long>, add: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (songIds.isEmpty()) return@withContext true
            val response = client.post(
                path = "/playlist/tracks",
                query = mapOf(
                    "op" to if (add) "add" else "del",
                    "pid" to playlistId.toString(),
                    "tracks" to songIds.joinToString(","),
                    "timestamp" to now()
                ),
                // 该接口的 code 可能为 200 或 502，均代表操作已被接受。
                successCodes = setOf(200, 502)
            )
            // 有些网关会把结果再包一层 body
            val nestedCode = response["body"]?.let { body ->
                runCatching { body.jsonObject["code"]?.jsonPrimitive?.intOrNull }.getOrNull()
            }
            nestedCode == null || nestedCode == 200
        }

    private fun parsePlaylistArray(response: JsonObject): List<CloudPlaylist> {
        val raw = response["playlist"] ?: response["data"]?.jsonObject?.get("playlist") ?: return emptyList()
        if (raw !is JsonArray) return emptyList()
        return raw.jsonArray.mapNotNull { element ->
            runCatching { parsePlaylist(element.jsonObject) }.getOrNull()
        }
    }

    private fun parsePlaylist(obj: JsonObject): CloudPlaylist {
        val creator = runCatching { obj["creator"]?.jsonObject }.getOrNull()
        return CloudPlaylist(
            id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
            coverUrl = obj["coverImgUrl"]?.jsonPrimitive?.content.orEmpty(),
            trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
            playCount = obj["playCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            creatorNickname = creator?.get("nickname")?.jsonPrimitive?.content.orEmpty(),
            creatorUserId = creator?.get("userId")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            specialType = obj["specialType"]?.jsonPrimitive?.intOrNull ?: 0,
            subscribed = obj["subscribed"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    private fun extractTrackIds(playlistObject: JsonObject): List<Long> {
        val raw = playlistObject["trackIds"] ?: return emptyList()
        if (raw !is JsonArray) return emptyList()
        return raw.jsonArray.mapNotNull { element ->
            runCatching {
                element.jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
            }.getOrNull()
        }
    }

    /** `/song/detail` 单次能接受的 id 数量有限，分批拉取后合并。 */
    private suspend fun fetchSongsInChunks(ids: List<Long>): List<Song> {
        val result = mutableListOf<Song>()
        ids.chunked(SONG_DETAIL_CHUNK).forEach { chunk ->
            runCatching { result += cloudSongApi.getSongDetails(chunk) }
        }
        return result
    }

    private fun now(): String = System.currentTimeMillis().toString()

    private companion object {
        const val SONG_DETAIL_CHUNK = 200
    }
}
