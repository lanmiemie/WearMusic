package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.model.SongSource
import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.data.remote.dto.NeteaseSongDto
import com.ohmusic.app.data.remote.dto.SongMapper
import com.ohmusic.app.util.ArtworkSize
import com.ohmusic.app.util.withNetEaseCoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 云盘里的一首歌：文件元数据 + 服务端解析出的曲目信息。 */
data class CloudDriveSong(
    val song: Song,
    /** 云盘文件对应的 songId（匹配纠正/删除时使用）。 */
    val cloudSongId: Long,
    /** 是否已匹配到网易云曲库。 */
    val matched: Boolean,
    /** 文件大小（字节），用于展示。 */
    val fileSize: Long,
    /** 上传时间（毫秒时间戳）。 */
    val addTime: Long,
    /** 文件 md5（自动匹配 `/search/match` 时透传，可提升命中率）。 */
    val md5: String? = null
)

/**
 * 音乐云盘接口：列表 / 匹配纠正 / 删除。
 *
 * ### 响应形态说明（实测）
 *
 * `/user/cloud` 的 `data` 直接是数组（标准部署是 `data.songs`），每项结构：
 *
 * ```
 * { "privateCloud": { songId, song, artist, album, fileName, fileSize, md5,
 *                     originalAudioSongId, ... },
 *   "simpleSong":   { id, name, ar[], al{}, dt, ... } }
 * ```
 *
 * **未匹配**的文件 `simpleSong.ar[0].name` 为空串、`originalAudioSongId` 为 0，
 * 展示信息需要回退到 `privateCloud` 的文件元数据（歌名常带 `.mp3` 后缀，要剥掉）。
 */
@Singleton
class CloudDriveApi @Inject constructor(
    private val client: NeteaseClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 分页拉取云盘歌曲；返回 (本页歌曲, 云盘总文件数)。 */
    suspend fun getCloudSongs(limit: Int = 100, offset: Int = 0): Pair<List<CloudDriveSong>, Int> =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/user/cloud",
                query = mapOf(
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "timestamp" to now()
                )
            )
            val raw = response["data"] as? JsonArray
            val items = raw?.jsonArray?.mapNotNull { element ->
                runCatching { parseCloudItem(element.jsonObject) }.getOrNull()
            }.orEmpty()
            val total = response["count"]?.jsonPrimitive?.longOrNull ?: items.size.toLong()
            items to total.toInt()
        }

    /** 删除云盘歌曲（文件一并删除，UI 层需二次确认）。 */
    suspend fun deleteSong(cloudSongId: Long) {
        client.post(
            path = "/user/cloud/del",
            query = mapOf(
                "id" to cloudSongId.toString(),
                "timestamp" to now()
            )
        )
    }

    /**
     * 匹配纠正：把云盘文件关联到曲库歌曲；`targetSongId = 0` 表示取消匹配。
     *
     * **注意实测：匹配/取消都会改变该文件在云盘里的 songId**（匹配后变成
     * 目标歌曲的 id，取消后变成新的裸文件 id），旧 id 立即失效。
     * 服务端会在 `matchData.simpleSong` 里带回**匹配后的完整曲目信息**，
     * 这里解析成新条目返回，调用方可就地替换列表项，无需重新拉列表。
     *
     * @param uid 用户 id（接口必填）
     * @param cloudSongId 云盘文件的当前 songId
     * @param targetSongId 要匹配到的曲库歌曲 id；0 表示取消匹配
     * @return 匹配后的新条目；响应里没有 matchData 时返回 null（需重拉列表）
     */
    suspend fun matchSong(
        uid: Long,
        cloudSongId: Long,
        targetSongId: Long,
        fileSize: Long = 0L,
        addTime: Long = 0L,
        md5: String? = null
    ): CloudDriveSong? = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/cloud/match",
            query = mapOf(
                "uid" to uid.toString(),
                "sid" to cloudSongId.toString(),
                "asid" to targetSongId.toString(),
                "timestamp" to now()
            )
        )
        val simpleSong = response["matchData"]?.jsonObject?.get("simpleSong")?.jsonObject
            ?: return@withContext null
        val newId = simpleSong["id"]?.jsonPrimitive?.longOrNull ?: return@withContext null
        val dto = runCatching {
            json.decodeFromJsonElement(NeteaseSongDto.serializer(), simpleSong)
        }.getOrNull()

        // 取消匹配后服务端会把文件名（含扩展名）塞进 name，此时回退到文件元数据。
        val rawName = dto?.name.orEmpty()
        val hasRealName = rawName.isNotBlank() && !rawName.endsWith(".mp3") &&
            !rawName.endsWith(".flac") && !rawName.endsWith(".ape")
        val title = if (targetSongId > 0 && hasRealName) rawName
        else stripExtension(rawName).ifBlank { "未知歌曲" }
        val artist = dto?.resolvedArtists
            ?.mapNotNull { it.name.takeIf(String::isNotBlank) }
            ?.joinToString("/")
            ?.takeIf { it.isNotBlank() && targetSongId > 0 }
            ?: "未知歌手"
        val album = dto?.resolvedAlbum?.name
            ?.takeIf { it.isNotBlank() && targetSongId > 0 }
            .orEmpty()
        val cover = dto?.resolvedAlbum?.coverUrl?.takeIf { it.isNotBlank() && targetSongId > 0 }

        CloudDriveSong(
            song = Song(
                id = -newId,
                title = title,
                artist = artist,
                album = album,
                duration = dto?.resolvedDuration ?: 0L,
                albumId = dto?.resolvedAlbum?.id ?: 0L,
                uri = "",
                source = SongSource.NETEASE,
                songId = newId,
                coverUrl = cover,
                smallCoverUrl = cover?.withNetEaseCoverSize(ArtworkSize.Thumb)
            ),
            cloudSongId = newId,
            matched = targetSongId > 0,
            fileSize = fileSize,
            addTime = addTime,
            md5 = md5
        )
    }

    /**
     * 按文件元数据自动匹配（`/search/match`）。
     *
     * 该接口以标题/歌手/时长/md5 综合判断，命中的置信度比关键词搜索高；
     * 拿不到 md5（列表接口已提供 privateCloud.md5，可直接透传）时也能只靠文本字段。
     *
     * @return 最匹配的曲库歌曲；无命中时返回 null。
     */
    suspend fun searchMatch(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Long,
        md5: String?
    ): Song? = withContext(Dispatchers.IO) {
        val query = buildMap {
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("duration", durationSeconds.toString())
            if (!md5.isNullOrBlank()) put("md5", md5)
            put("timestamp", now())
        }
        val response = runCatching { client.post(path = "/search/match", query = query) }
            .getOrNull() ?: return@withContext null
        val songs = response["result"]?.jsonObject?.get("songs") as? JsonArray
            ?: return@withContext null
        val dto = songs.jsonArray.firstOrNull()?.let { element ->
            runCatching { json.decodeFromJsonElement(NeteaseSongDto.serializer(), element) }.getOrNull()
        } ?: return@withContext null
        SongMapper.toSong(dto)
    }

    private fun parseCloudItem(obj: JsonObject): CloudDriveSong? {
        val privateCloud = obj["privateCloud"]?.jsonObject ?: return null
        val simpleSong = obj["simpleSong"]?.jsonObject

        val cloudSongId = privateCloud["songId"]?.jsonPrimitive?.longOrNull ?: return null
        val fileName = privateCloud["fileName"]?.jsonPrimitive?.content.orEmpty()
        val fileSize = privateCloud["fileSize"]?.jsonPrimitive?.longOrNull ?: 0L
        val addTime = privateCloud["addTime"]?.jsonPrimitive?.longOrNull ?: 0L
        val originalAudioSongId = privateCloud["originalAudioSongId"]?.jsonPrimitive?.longOrNull ?: 0L

        val dto = simpleSong?.let { element ->
            runCatching { json.decodeFromJsonElement(NeteaseSongDto.serializer(), element) }.getOrNull()
        }
        val parsed = dto?.let { if (it.id > 0) SongMapper.toSong(it) else null }

        // 展示信息回退链：simpleSong → privateCloud 文件元数据 → 文件名。
        // simpleSong 的 name 对未匹配文件常是「xxx.mp3」这类文件名，
        // 歌手/专辑可能为空串，这些场景都要回退到文件元数据。
        val rawSongName = privateCloud["song"]?.jsonPrimitive?.content.orEmpty()
        val fallbackTitle = stripExtension(fileName).ifBlank {
            stripExtension(rawSongName).ifBlank { "未知歌曲" }
        }
        val fallbackArtist = privateCloud["artist"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() } ?: "未知歌手"
        val fallbackAlbum = privateCloud["album"]?.jsonPrimitive?.content.orEmpty()

        val title = parsed?.title
            ?.takeIf { it.isNotBlank() && dto?.isUnnamed == false }
            ?: fallbackTitle
        val artist = parsed?.artist
            ?.takeIf { it.isNotBlank() && dto?.hasArtist == true }
            ?: fallbackArtist
        val album = parsed?.album
            ?.takeIf { it.isNotBlank() && dto?.hasAlbum == true }
            ?: fallbackAlbum

        val song = (parsed ?: Song(
            id = -cloudSongId,
            title = "",
            artist = "",
            album = "",
            duration = 0L,
            albumId = 0L,
            uri = "",
            source = SongSource.NETEASE,
            songId = cloudSongId
        )).copy(
            // 云盘歌曲一律用 cloudSongId 充当 songId（播放直链也按它换取）。
            songId = cloudSongId,
            title = title,
            artist = artist,
            album = album
        )

        val matched = originalAudioSongId > 0 ||
            (dto != null && dto.resolvedArtists.any { it.name.isNotBlank() })
        return CloudDriveSong(
            song = song,
            cloudSongId = cloudSongId,
            matched = matched,
            fileSize = fileSize,
            addTime = addTime,
            md5 = privateCloud["md5"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        )
    }

    /** 去掉文件名扩展名，并容忍「歌名.mp3」这类把扩展名写进元数据的场景。 */
    private fun stripExtension(name: String): String =
        name.substringBeforeLast('.').trim()

    private fun now(): String = System.currentTimeMillis().toString()

    private val NeteaseSongDto.isUnnamed: Boolean
        get() = name.isBlank() || name.endsWith(".mp3") || name.endsWith(".flac")

    private val NeteaseSongDto.hasArtist: Boolean
        get() = resolvedArtists.any { it.name.isNotBlank() }

    private val NeteaseSongDto.hasAlbum: Boolean
        get() = resolvedAlbum?.name?.isNotBlank() == true
}
