package com.shijiu.wearmusic.data

import com.ohmusic.app.data.remote.NeteaseClient
import com.ohmusic.app.data.remote.api.CloudPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 宿主层补充接口：网关上存在但 core 模块未覆盖的能力。
 *
 * - `/personalized`        推荐歌单（匿名可用）
 * - `/playlist/mylike`     雷达歌单（需登录）
 * - `/playlist/privacy`    歌单隐私设置（需登录）
 */
class ExtraNeteaseApi(private val client: NeteaseClient) {

    /** 推荐歌单（官方「为你推荐」卡片位，匿名可用）。 */
    suspend fun personalizedPlaylists(limit: Int = 50): List<CloudPlaylist> =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/personalized",
                query = mapOf(
                    "limit" to limit.toString(),
                    "timestamp" to now()
                ),
                withCookie = false
            )
            parsePlaylistArray(response["result"])
        }

    /** 雷达歌单（私人雷达 / 心情雷达等），需要登录。 */
    suspend fun radarPlaylists(): List<CloudPlaylist> = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/playlist/mylike",
            query = mapOf("timestamp" to now())
        )
        val data = response["data"]
        val array: JsonArray? = when (data) {
            is JsonArray -> data
            is JsonObject -> data["playlists"] as? JsonArray
                ?: data["list"] as? JsonArray
            else -> response["result"] as? JsonArray
        }
        array?.let(::parsePlaylistArray).orEmpty()
    }

    /** 设置歌单隐私：`privacy=10` 私密，`0` 公开。需要登录且必须是自己的歌单。 */
    suspend fun setPlaylistPrivacy(playlistId: Long, isPrivate: Boolean) {
        client.post(
            path = "/playlist/privacy",
            query = mapOf(
                "id" to playlistId.toString(),
                "privacy" to if (isPrivate) "10" else "0",
                "timestamp" to now()
            )
        )
    }

    private fun parsePlaylistArray(raw: kotlinx.serialization.json.JsonElement?): List<CloudPlaylist> {
        val array = raw as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { parsePlaylist(element.jsonObject) }.getOrNull()
        }
    }

    private fun parsePlaylist(obj: JsonObject): CloudPlaylist {
        val creator = runCatching { obj["creator"]?.jsonObject }.getOrNull()
        val id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return CloudPlaylist(
            id = id,
            name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
            coverUrl = obj["coverImgUrl"]?.jsonPrimitive?.content
                ?: obj["picUrl"]?.jsonPrimitive?.content.orEmpty(),
            trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
            playCount = obj["playCount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            creatorNickname = creator?.get("nickname")?.jsonPrimitive?.content.orEmpty(),
            creatorUserId = creator?.get("userId")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            description = obj["description"]?.jsonPrimitive?.content
                ?: obj["copywriter"]?.jsonPrimitive?.content.orEmpty(),
            specialType = obj["specialType"]?.jsonPrimitive?.intOrNull ?: 0,
            subscribed = obj["subscribed"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    private fun now(): String = System.currentTimeMillis().toString()
}
