package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.NeteaseApiException
import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.data.remote.dto.NeteaseSongDto
import com.ohmusic.app.data.remote.dto.SongMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 听歌排行单条记录：曲目 + 播放次数 + 热度分。 */
data class PlayRecordSong(
    val song: Song,
    /** 网易云统计的播放次数（周榜=本周次数；总榜=累计次数）。 */
    val playCount: Int,
    /** 热度分（100 满分）。 */
    val score: Int
)

/**
 * 听歌排行接口（网关 `/user/record`，官方底层 `/api/play-record/songlist/list`）。
 *
 * 响应形态（实测）：
 * - `type=1`（最近一周）→ `weekData: [{ playCount, score, song: {...} }]`
 * - `type=0`（所有时间）→ `allData: [...]`，结构相同，服务端最多下发 100 条
 * - `song` 为新格式（`ar` / `al` / `dt`），与 [NeteaseSongDto] 两栖兼容
 *
 * 注意：接口只有播放次数与热度，**没有首次播放时间**——该项由 App 本地记录补足。
 */
@Singleton
class PlayRecordApi @Inject constructor(
    private val client: NeteaseClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 最近一周排行。 */
    suspend fun weekRecord(uid: Long): List<PlayRecordSong> = fetch(uid, "weekData", "1")

    /** 所有时间排行（累计播放次数最多的 100 首）。 */
    suspend fun allRecord(uid: Long): List<PlayRecordSong> = fetch(uid, "allData", "0")

    private suspend fun fetch(
        uid: Long,
        dataKey: String,
        type: String
    ): List<PlayRecordSong> = withContext(Dispatchers.IO) {
        if (uid <= 0) {
            throw NeteaseApiException.parse("需要登录后才能查看听歌排行")
        }
        val response = client.post(
            path = "/user/record",
            query = mapOf(
                "uid" to uid.toString(),
                "type" to type,
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        val array = response[dataKey] as? JsonArray ?: return@withContext emptyList()
        array.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val playCount = obj["playCount"]?.jsonPrimitive?.intOrNull ?: 0
                val score = obj["score"]?.jsonPrimitive?.intOrNull ?: 0
                val songDto = json.decodeFromJsonElement(
                    NeteaseSongDto.serializer(),
                    obj.getValue("song").jsonObject
                )
                PlayRecordSong(SongMapper.toSong(songDto), playCount, score)
            }.getOrNull()
        }
    }
}
