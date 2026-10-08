package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.NcblScrobbler
import com.ohmusic.app.data.remote.NeteaseApiException
import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.data.remote.dto.NeteaseSongDto
import com.ohmusic.app.data.remote.dto.SongMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 私人 FM / 心动模式 / 听歌打卡接口。
 *
 * ### 响应形态说明（实测）
 *
 * 这个网关把 `/personal_fm` 的歌曲数组**直接放在 `data` 下**（标准部署是
 * `data.songs`），且歌曲字段是旧格式（`artists` / `album`），这里做了两栖兼容。
 *
 * 心动模式 `/playmode/intelligence/list` 必须同时带 `id`（种子歌曲）与
 * `pid`（歌单），缺一个都会 400/500；返回的推荐项挂在 `data[].songInfo`。
 */
@Singleton
class CloudFmApi @Inject constructor(
    private val client: NeteaseClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 拉取一批私人 FM 歌曲（默认 3 首左右，由服务端决定）。 */
    suspend fun getPersonalFm(): List<Song> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/personal_fm",
            query = mapOf("timestamp" to now())
        )
        val raw = response["data"] ?: return@withContext emptyList()
        val array: JsonArray = when (raw) {
            // 网关实测：data 直接是歌曲数组
            is JsonArray -> raw
            // 标准部署：data.songs
            is kotlinx.serialization.json.JsonObject ->
                raw.jsonObject["songs"] as? JsonArray ?: return@withContext emptyList()
            else -> return@withContext emptyList()
        }
        SongMapper.toSongs(decodeSongs(array))
    }

    /** 把 FM 当前歌曲移进垃圾桶（不再出现）。 */
    suspend fun trashSong(songId: Long) {
        client.post(
            path = "/fm_trash",
            query = mapOf(
                "id" to songId.toString(),
                "timestamp" to now()
            )
        )
    }

    /**
     * 心动模式/智能播放列表。
     *
     * @param seedSongId 种子歌曲（通常取歌单里正在播的一首）
     * @param playlistId 歌单 id，作为推荐的参照集合
     * @param startSongId 要优先开播的歌曲，一般与种子相同；不传则服务端自行决定
     */
    suspend fun getIntelligenceList(
        seedSongId: Long,
        playlistId: Long,
        startSongId: Long? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        val query = buildMap {
            put("id", seedSongId.toString())
            put("pid", playlistId.toString())
            if (startSongId != null && startSongId > 0) put("sid", startSongId.toString())
            put("timestamp", now())
        }
        val response = client.post(path = "/playmode/intelligence/list", query = query)
        val raw = response["data"] as? JsonArray ?: return@withContext emptyList()
        val dtos = raw.jsonArray.mapNotNull { element ->
            runCatching {
                // 每个推荐项包在 data[i].songInfo 里，个别项可能缺该字段。
                element.jsonObject["songInfo"]?.jsonObject ?: return@mapNotNull null
                json.decodeFromJsonElement(NeteaseSongDto.serializer(), element.jsonObject["songInfo"]!!)
            }.getOrNull()
        }
        SongMapper.toSongs(dtos)
    }

    /**
     * 听歌打卡：按桌面客户端 NCBL v3 协议上报一次播放。
     *
     * ### 为什么不走网关的 `/scrobble`
     * 2024 年起网易云对第三方 weblog 上报做了静默风控：`/scrobble`（weapi/eapi）
     * 无论换哪个域名都返回假成功，但服务端不记账（实测 16 小时听歌排行无变化）。
     * 唯一实测能落库的是桌面客户端的 NCBL 日志协议（对齐 SPlayer-Next 的
     * scrobble_v1）：PLV+PLD 双日志经 clientlog3 上传，凭 `successfiles` 回执
     * 确认，听歌排行数分钟内落库。加密与封装细节见 [NcblScrobbler]。
     *
     * @param song 在线曲目（本地曲目无 songId，直接抛错）
     * @param playedSec 实际播放秒数
     * @param sourceId 播放来源 id（歌单等）；缺省用歌曲自身 id
     * @throws NeteaseApiException 未登录 / 服务端拒收 / 网络失败时抛出，
     *   由调用方如实提示——打卡是否成功必须让用户知道
     */
    suspend fun scrobble(song: Song, playedSec: Int, sourceId: Long? = null): Unit {
        val songId = song.songId
            ?: throw NeteaseApiException.parse("本地歌曲不参与听歌打卡")
        val result = NcblScrobbler.scrobbleSuspend(
            cookie = client.cookie(),
            songId = songId,
            playedSec = playedSec.coerceAtLeast(1),
            totalSec = ((song.duration.coerceAtLeast(0)) / 1000).toInt()
                .coerceAtLeast(playedSec.coerceAtLeast(1)),
            title = song.title,
            artist = song.artist,
            sourceId = sourceId
        )
        if (!result.accepted) {
            throw NeteaseApiException.parse(result.error ?: "打卡上报被服务端拒收")
        }
    }

    private fun decodeSongs(array: JsonArray): List<NeteaseSongDto> =
        array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(NeteaseSongDto.serializer(), element) }.getOrNull()
        }

    private fun now(): String = System.currentTimeMillis().toString()
}
