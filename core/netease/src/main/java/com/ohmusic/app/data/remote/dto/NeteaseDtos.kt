package com.ohmusic.app.data.remote.dto

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.model.SongSource
import com.ohmusic.app.util.ArtworkSize
import com.ohmusic.app.util.withNetEaseCoverSize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 网易云歌曲的通用形态。
 *
 * 网关在不同接口里对同一首歌的字段名并不统一，主要差别是：
 * - `/search`、`/toplist`、`/playlist/detail` 用 `artists` / `album` / `duration`
 * - `/recommend/songs`、`/song/detail` 用 `ar` / `al` / `dt`
 *
 * 这里把两套字段都声明为可空，由 [SongMapper] 统一归一化。
 */
@Serializable
data class NeteaseSongDto(
    val id: Long = 0,
    val name: String = "",
    val fee: Int = 0,
    val duration: Long? = null,
    val dt: Long? = null,
    val alia: List<String>? = null,
    val ar: List<NeteaseArtistDto>? = null,
    val artists: List<NeteaseArtistDto>? = null,
    val al: NeteaseAlbumDto? = null,
    val album: NeteaseAlbumDto? = null,
    /** `/recommend/songs` 会带上推荐语。 */
    val reason: String? = null,
    @SerialName("recommendReason")
    val recommendReason: String? = null
) {
    /** 曲目时长（毫秒），优先 `dt`（song/detail 系）再退回 `duration`（搜索系）。 */
    val resolvedDuration: Long
        get() = dt ?: duration ?: 0L

    val resolvedArtists: List<NeteaseArtistDto>
        get() = ar ?: artists ?: emptyList()

    val resolvedAlbum: NeteaseAlbumDto?
        get() = al ?: album

    /** 歌手名拼接，多个歌手用 `/` 分隔（与网易云客户端展示一致）。 */
    val artistText: String
        get() = resolvedArtists.joinToString("/") { it.name }.ifBlank { "未知歌手" }

    val albumName: String
        get() = resolvedAlbum?.name.orEmpty().ifBlank { "未知专辑" }
}

@Serializable
data class NeteaseArtistDto(
    val id: Long = 0,
    val name: String = ""
)

@Serializable
data class NeteaseAlbumDto(
    val id: Long = 0,
    val name: String = "",
    @SerialName("picUrl")
    val picUrl: String? = null,
    /** 部分接口（如 `/search`）只给 `picId`，需要自己拼 URL。 */
    @SerialName("picId")
    val picId: Long? = null
) {
    /**
     * 封面地址。网易云封面固定走 `p{1,2,3,4}.music.126.net`，
     * `picId` 可以通过 `https://p3.music.126.net/...` 形式拼出来，
     * 但拼法需要对 picId 做加密处理，因此这里只信任服务端给的 `picUrl`。
     */
    val coverUrl: String?
        get() = picUrl?.takeIf { it.isNotBlank() }
}

/** `/song/detail` 响应。 */
@Serializable
data class NeteaseSongDetailResponse(
    val code: Int = 0,
    val songs: List<NeteaseSongDto> = emptyList()
)

/** `/song/url` 响应。 */
@Serializable
data class NeteaseSongUrlResponse(
    val code: Int = 0,
    val data: List<NeteaseSongUrlItem> = emptyList()
)

@Serializable
data class NeteaseSongUrlItem(
    val id: Long = 0,
    val url: String? = null,
    val br: Int = 0,
    val size: Long = 0,
    val code: Int = 0,
    /** 无版权/需付费时服务端会给出 `freeTrialInfo`，此时 `url` 通常为空。 */
    @SerialName("freeTrialInfo")
    val freeTrialInfo: kotlinx.serialization.json.JsonElement? = null
)

/** `/lyric/new` 响应。 */
@Serializable
data class NeteaseLyricResponse(
    val code: Int = 0,
    val lrc: NeteaseLyricSection? = null,
    val tlyric: NeteaseLyricSection? = null,
    val romalrc: NeteaseLyricSection? = null,
    val yrc: NeteaseLyricSection? = null,
    val klyric: NeteaseLyricSection? = null
) {
    /** 原文歌词，优先 `lrc`，没有时退回逐字歌词 `yrc`。 */
    val primaryLyric: String
        get() = lrc?.lyric?.takeIf { it.isNotBlank() } ?: yrc?.lyric.orEmpty()

    /** 翻译歌词。 */
    val translatedLyric: String
        get() = tlyric?.lyric.orEmpty()

    val hasContent: Boolean
        get() = primaryLyric.isNotBlank()
}

@Serializable
data class NeteaseLyricSection(
    val version: Int = 0,
    val lyric: String = ""
)

/**
 * 把网易云 DTO 归一化成 App 内部统一的 [Song]。
 *
 * 关键约定：在线曲目的 [Song.id] 使用 `-songId`，与 MediaStore 的正数 id 隔离，
 * 这样播放队列按 Long id 持久化的既有逻辑无需修改。
 */
object SongMapper {

    fun toSong(dto: NeteaseSongDto): Song {
        val detail = dto.resolvedAlbum
        val cover = detail?.coverUrl?.takeIf { it.isNotBlank() }
        return Song(
            id = -dto.id,
            title = dto.name.ifBlank { "未知歌曲" },
            artist = dto.artistText,
            album = dto.albumName,
            duration = dto.resolvedDuration,
            albumId = detail?.id ?: 0L,
            uri = "",
            dateAdded = System.currentTimeMillis(),
            isFavorite = false,
            source = SongSource.NETEASE,
            songId = dto.id,
            coverUrl = cover,
            smallCoverUrl = cover?.withNetEaseCoverSize(ArtworkSize.Thumb),
            artistId = dto.resolvedArtists.firstOrNull()?.id?.takeIf { it > 0 }
        )
    }

    fun toSongs(dtos: List<NeteaseSongDto>): List<Song> =
        dtos.filter { it.id > 0 }.map(::toSong)
}
