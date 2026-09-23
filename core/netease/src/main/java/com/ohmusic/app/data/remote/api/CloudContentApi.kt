package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.util.ArtworkSize
import com.ohmusic.app.util.withNetEaseCoverSize
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

/** 榜单摘要。 */
data class CloudToplist(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val updateFrequency: String,
    val trackCount: Int
)

/**
 * 推荐与榜单接口：每日推荐、推荐歌单、所有榜单。
 *
 * 这些接口大多需要登录 cookie；未登录时服务端会返回 301，
 * 由 UI 层引导用户去登录。
 */
@Singleton
class RecommendApi @Inject constructor(
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
     * 每日推荐歌曲。
     *
     * 不带 [date] 时是「今天」（需要登录）；带 `date=YYYY-MM-DD` 时为
     * 历史日推回看（匿名可用），用于实现「支持历史日推」。
     */
    suspend fun getDailyRecommendSongs(date: String? = null): List<Song> =
        withContext(Dispatchers.IO) {
            val query = buildMap {
                put("timestamp", now())
                if (!date.isNullOrBlank()) put("date", date)
            }
            val response = client.post(
                path = "/recommend/songs",
                query = query
            )
            cloudSongApi.extractSongs(response)
        }

    /** 每日推荐歌单，需要登录。 */
    suspend fun getDailyRecommendPlaylists(): List<CloudPlaylist> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/recommend/resource",
            query = mapOf("timestamp" to now())
        )
        val raw = response["recommend"] ?: response["data"]?.jsonObject?.get("recommend")
            ?: return@withContext emptyList()
        if (raw !is JsonArray) return@withContext emptyList()
        raw.jsonArray.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                CloudPlaylist(
                    id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    coverUrl = obj["picUrl"]?.jsonPrimitive?.content.orEmpty(),
                    trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    playCount = obj["playCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    creatorNickname = obj["copywriter"]?.jsonPrimitive?.content.orEmpty(),
                    description = obj["copywriter"]?.jsonPrimitive?.content.orEmpty(),
                    subscribed = obj["subscribed"]?.jsonPrimitive?.content?.toBoolean() ?: false
                )
            }.getOrNull()
        }
    }

    /** 所有榜单，不需要登录。 */
    suspend fun getToplists(): List<CloudToplist> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/toplist",
            query = mapOf("timestamp" to now())
        )
        val raw = response["list"] ?: return@withContext emptyList()
        if (raw !is JsonArray) return@withContext emptyList()
        raw.jsonArray.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                CloudToplist(
                    id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    coverUrl = obj["coverImgUrl"]?.jsonPrimitive?.content.orEmpty(),
                    updateFrequency = obj["updateFrequency"]?.jsonPrimitive?.content.orEmpty(),
                    trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0
                )
            }.getOrNull()
        }.filter { it.id > 0 }
    }

    private fun now(): String = System.currentTimeMillis().toString()
}

/** 搜索建议项。 */
data class SearchSuggestItem(
    val keyword: String,
    /** 建议附带的信息，通常是歌手名或专辑名。 */
    val hint: String
)

/** 热搜词。 */
data class HotSearchItem(
    val keyword: String,
    val score: Long
)

/**
 * 搜索接口：综合搜索、搜索建议、热搜。
 */
@Singleton
class CloudSearchApi @Inject constructor(
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
     * 搜索歌曲。
     *
     * **封面必须回填**：`/search` 返回的是精简结构，专辑对象里只有 `picId`（一个数字），
     * **不含 `picUrl`**，且没有 `/song/detail` 的 `al`/`ar`/`dt` 字段。
     * 只靠搜索响应的话每首歌的封面都会是空的，界面上一律显示默认图。
     *
     * 因此这里在搜索完成后，用命中的 songId 批量调一次 `/song/detail`，
     * 把封面、时长等字段补齐后再返回。多一次请求，但只发一次（不分页循环）。
     */
    suspend fun searchSongs(
        keywords: String,
        limit: Int = 30,
        offset: Int = 0
    ): List<Song> = withContext(Dispatchers.IO) {
        if (keywords.isBlank()) return@withContext emptyList()
        val response = client.post(
            path = "/search",
            query = mapOf(
                "keywords" to keywords,
                "type" to "1",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "timestamp" to now()
            ),
            withCookie = false
        )
        val result = response["result"]?.jsonObject ?: return@withContext emptyList()
        val songs = cloudSongApi.extractSongs(result)
        if (songs.isEmpty()) return@withContext emptyList()

        // 回填封面：失败时退回搜索结果本身，不影响搜索可用性。
        val details = runCatching {
            cloudSongApi.getSongDetails(songs.map { -it.id })
        }.getOrNull()
        mergeDetails(songs, details.orEmpty())
    }

    /**
     * 通用搜索（非歌曲类结果）。
     *
     * `type`：10=专辑 100=歌手 1000=歌单 1009=播客（电台）。
     * 结果数组的键分别是 result.albums / result.artists / result.playlists / result.djRadios。
     */
    private suspend fun searchByType(
        keywords: String,
        type: Int,
        resultKey: String,
        limit: Int,
        offset: Int
    ): JsonArray? = withContext(Dispatchers.IO) {
        if (keywords.isBlank()) return@withContext null
        val response = runCatching {
            client.post(
                path = "/search",
                query = mapOf(
                    "keywords" to keywords,
                    "type" to type.toString(),
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "timestamp" to now()
                ),
                withCookie = false
            )
        }.getOrNull() ?: return@withContext null
        response["result"]?.jsonObject?.get(resultKey) as? JsonArray
    }

    /** 搜索歌手（type=100）。 */
    suspend fun searchArtists(
        keywords: String,
        limit: Int = 30,
        offset: Int = 0
    ): List<CloudArtist> = withContext(Dispatchers.IO) {
        searchByType(keywords, TYPE_ARTIST, "artists", limit, offset)
            ?.mapNotNull { element -> runCatching { parseArtistSummary(element.jsonObject) }.getOrNull() }
            .orEmpty()
    }

    /** 搜索专辑（type=10）。 */
    suspend fun searchAlbums(
        keywords: String,
        limit: Int = 30,
        offset: Int = 0
    ): List<CloudAlbumSummary> = withContext(Dispatchers.IO) {
        searchByType(keywords, TYPE_ALBUM, "albums", limit, offset)
            ?.mapNotNull { element -> runCatching { parseAlbumSummary(element.jsonObject) }.getOrNull() }
            .orEmpty()
    }

    /** 搜索歌单（type=1000）。 */
    suspend fun searchPlaylists(
        keywords: String,
        limit: Int = 30,
        offset: Int = 0
    ): List<CloudPlaylist> = withContext(Dispatchers.IO) {
        val raw = searchByType(keywords, TYPE_PLAYLIST, "playlists", limit, offset)
            ?: return@withContext emptyList()
        raw.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                CloudPlaylist(
                    id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    coverUrl = obj["coverImgUrl"]?.jsonPrimitive?.content.orEmpty(),
                    trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    playCount = obj["playCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    creatorNickname = runCatching {
                        obj["creator"]?.jsonObject?.get("nickname")?.jsonPrimitive?.content
                    }.getOrNull().orEmpty(),
                    description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    subscribed = obj["subscribed"]?.jsonPrimitive?.content?.toBoolean() ?: false
                )
            }.getOrNull()
        }
    }

    /** 搜索播客（type=1009）。 */
    suspend fun searchDjs(
        keywords: String,
        limit: Int = 30,
        offset: Int = 0
    ): List<CloudDj> = withContext(Dispatchers.IO) {
        val raw = searchByType(keywords, TYPE_PODCAST, "djRadios", limit, offset)
            ?: return@withContext emptyList()
        raw.mapNotNull { element ->
            runCatching { parseDjSummary(element.jsonObject) }.getOrNull()
        }
    }

    /**
     * 用 `/song/detail` 的结果补齐搜索结果的封面等字段。
     *
     * 按 songId 对齐；详情里封面为空的条目保留原值，避免把已有的信息覆盖没。
     */
    private fun mergeDetails(searched: List<Song>, details: List<Song>): List<Song> {
        if (details.isEmpty()) return searched
        val byId = details.associateBy { it.songId }
        return searched.map { song ->
            val detail = byId[song.songId] ?: return@map song
            val cover = detail.coverUrl?.takeIf { it.isNotBlank() } ?: song.coverUrl
            song.copy(
                coverUrl = cover,
                // 列表缩略图跟着一起补上，否则搜索结果里的封面仍会走大图分支。
                smallCoverUrl = cover?.withNetEaseCoverSize(ArtworkSize.Thumb),
                duration = detail.duration.takeIf { it > 0 } ?: song.duration
            )
        }
    }

    /** 搜索建议，用于搜索框实时联想。 */
    suspend fun searchSuggest(keywords: String): List<SearchSuggestItem> =
        withContext(Dispatchers.IO) {
            if (keywords.isBlank()) return@withContext emptyList()
            val response = runCatching {
                client.post(
                    path = "/search/suggest",
                    query = mapOf(
                        "keywords" to keywords,
                        "timestamp" to now()
                    ),
                    withCookie = false
                )
            }.getOrNull() ?: return@withContext emptyList()

            val items = mutableListOf<SearchSuggestItem>()
            val result = response["result"] ?: return@withContext emptyList()
            if (result !is JsonObject) return@withContext emptyList()

            // 歌单/歌手/专辑建议附带名称，歌曲建议只有名字。
            listOf("songs", "artists", "albums", "playlists").forEach { key ->
                val array = result[key]
                if (array !is JsonArray) return@forEach
                array.jsonArray.forEach { element ->
                    val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                    val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                    if (name.isBlank()) return@forEach
                    val hint = runCatching {
                        obj["artists"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("name")?.jsonPrimitive?.content
                            ?: obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    }.getOrNull().orEmpty()
                    items += SearchSuggestItem(name, hint)
                }
            }
            items.distinctBy { it.keyword }.take(SUGGEST_LIMIT)
        }

    /** 热搜榜。 */
    suspend fun getHotSearch(): List<HotSearchItem> = withContext(Dispatchers.IO) {
        val response = runCatching {
            client.post(
                path = "/search/hot/detail",
                query = mapOf("timestamp" to now()),
                withCookie = false
            )
        }.getOrNull() ?: return@withContext emptyList()

        val raw = response["data"] ?: return@withContext emptyList()
        if (raw !is JsonArray) return@withContext emptyList()
        raw.jsonArray.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                HotSearchItem(
                    keyword = obj["searchWord"]?.jsonPrimitive?.content.orEmpty(),
                    score = obj["score"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                )
            }.getOrNull()
        }.filter { it.keyword.isNotBlank() }
    }

    private fun now(): String = System.currentTimeMillis().toString()

    private companion object {
        const val SUGGEST_LIMIT = 10

        /** `/search` 的 type 参数：专辑 / 歌手 / 歌单 / 播客。 */
        const val TYPE_ALBUM = 10
        const val TYPE_ARTIST = 100
        const val TYPE_PLAYLIST = 1000
        const val TYPE_PODCAST = 1009
    }
}

/**
 * 喜欢（收藏单曲）接口，对应网易云「我喜欢的音乐」。
 */
@Singleton
class CloudLikeApi @Inject constructor(
    private val client: NeteaseClient
) {
    /** 喜欢 / 取消喜欢一首歌。 */
    suspend fun setLiked(songId: Long, liked: Boolean) {
        client.post(
            path = "/like",
            query = mapOf(
                "id" to songId.toString(),
                "like" to liked.toString(),
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
    }

    /**
     * 获取用户喜欢的全部歌曲 id。
     *
     * 该接口需要 cookie，返回的 ids 是完整列表（可能上万条），
     * 调用方应只用于本地判断「某首歌是否已喜欢」。
     */
    suspend fun getLikedSongIds(uid: Long): List<Long> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/likelist",
            query = mapOf(
                "uid" to uid.toString(),
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        val raw = response["ids"] ?: return@withContext emptyList()
        if (raw !is JsonArray) return@withContext emptyList()
        raw.jsonArray.mapNotNull { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() }
    }
}
