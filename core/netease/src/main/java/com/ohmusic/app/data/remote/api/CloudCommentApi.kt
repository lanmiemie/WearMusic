package com.ohmusic.app.data.remote.api

import com.ohmusic.app.data.remote.NeteaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 一条评论。 */
data class CloudComment(
    val commentId: Long,
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    val content: String,
    /** 评论时间戳（毫秒），0 表示服务端没给。 */
    val time: Long,
    val likedCount: Int,
    /** 当前账号是否已点赞。 */
    val liked: Boolean,
    val replyCount: Int
)

/** 评论分页结果。 */
data class CloudCommentPage(
    val comments: List<CloudComment>,
    val hotComments: List<CloudComment> = emptyList(),
    val total: Int,
    /** 翻页游标：sortType=3（按时间）时为最后一条的 time，其余场景用页码。 */
    val cursor: Long = 0L,
    val hasMore: Boolean
)

/**
 * 评论接口（新版 `/comment/new` + 发送/删除 `/comment` + 点赞 `/comment/like`）。
 *
 * 网关上两个读取接口都实测可用：
 * - `/comment/new`（分页/排序齐全）返回 `data.comments`；
 * - `/comment/music`（旧版）返回顶层 `comments` / `hotComments`。
 * 这里统一走新版，失败时回退旧版。
 */
@Singleton
class CloudCommentApi @Inject constructor(
    private val client: NeteaseClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * 读取评论。
     *
     * @param type 资源类型：0 歌曲 / 2 歌单 / 3 专辑 / 4 电台节目（其余见文档）
     * @param sortType 1 推荐 / 2 热度 / 3 时间
     * @param cursor sortType=3 且翻页时传上一页最后一条的 time
     */
    suspend fun getComments(
        type: Int,
        resourceId: Long,
        pageNo: Int = 1,
        pageSize: Int = 20,
        sortType: Int = SORT_HOT,
        cursor: Long = 0L
    ): CloudCommentPage = withContext(Dispatchers.IO) {
        val query = buildMap {
            put("type", type.toString())
            put("id", resourceId.toString())
            put("pageNo", pageNo.toString())
            put("pageSize", pageSize.toString())
            put("sortType", sortType.toString())
            if (cursor > 0) put("cursor", cursor.toString())
            put("timestamp", now())
        }
        val response = runCatching { client.post(path = "/comment/new", query = query) }
            .getOrElse { return@withContext legacyComments(type, resourceId, pageNo, pageSize) }

        val data = response["data"]?.jsonObject
        if (data != null) {
            val comments = parseComments(data["comments"])
            CloudCommentPage(
                comments = comments,
                total = data["totalCount"]?.jsonPrimitive?.intOrNull
                    ?: comments.size,
                cursor = comments.lastOrNull()?.time ?: 0L,
                hasMore = data["hasMore"]?.jsonPrimitive?.content?.toBoolean()
                    ?: (comments.size >= pageSize)
            )
        } else {
            legacyComments(type, resourceId, pageNo, pageSize)
        }
    }

    /** 发送评论（t=1）。返回新评论 id；发送失败抛 [com.ohmusic.app.data.remote.NeteaseApiException]。 */
    suspend fun sendComment(type: Int, resourceId: Long, content: String): Long =
        withContext(Dispatchers.IO) {
            val response = client.post(
                path = "/comment",
                query = mapOf(
                    "t" to "1",
                    "type" to type.toString(),
                    "id" to resourceId.toString(),
                    "content" to content,
                    "timestamp" to now()
                )
            )
            parseNewCommentId(response)
        }

    /** 回复评论（t=2）。 */
    suspend fun replyComment(
        type: Int,
        resourceId: Long,
        content: String,
        replyToCommentId: Long
    ): Long = withContext(Dispatchers.IO) {
        val response = client.post(
            path = "/comment",
            query = mapOf(
                "t" to "2",
                "type" to type.toString(),
                "id" to resourceId.toString(),
                "content" to content,
                "commentId" to replyToCommentId.toString(),
                "timestamp" to now()
            )
        )
        parseNewCommentId(response)
    }

    /** 删除自己的评论（t=0）。 */
    suspend fun deleteComment(type: Int, resourceId: Long, commentId: Long) {
        client.post(
            path = "/comment",
            query = mapOf(
                "t" to "0",
                "type" to type.toString(),
                "id" to resourceId.toString(),
                "commentId" to commentId.toString(),
                "timestamp" to now()
            )
        )
    }

    /** 评论点赞 / 取消点赞。 */
    suspend fun likeComment(type: Int, resourceId: Long, commentId: Long, like: Boolean) {
        client.post(
            path = "/comment/like",
            query = mapOf(
                "t" to if (like) "1" else "0",
                "type" to type.toString(),
                "id" to resourceId.toString(),
                "cid" to commentId.toString(),
                "timestamp" to now()
            )
        )
    }

    /** 新版接口不可用时的回退：旧版歌曲评论（仅歌曲场景，其它类型直接空页）。 */
    private suspend fun legacyComments(
        type: Int,
        resourceId: Long,
        pageNo: Int,
        pageSize: Int
    ): CloudCommentPage {
        if (type != TYPE_SONG) {
            return CloudCommentPage(emptyList(), total = 0, hasMore = false)
        }
        val response = runCatching {
            client.post(
                path = "/comment/music",
                query = mapOf(
                    "id" to resourceId.toString(),
                    "limit" to pageSize.toString(),
                    "offset" to ((pageNo - 1) * pageSize).toString(),
                    "timestamp" to now()
                )
            )
        }.getOrNull() ?: return CloudCommentPage(emptyList(), total = 0, hasMore = false)

        val comments = parseComments(response["comments"])
        val hot = parseComments(response["hotComments"])
        return CloudCommentPage(
            comments = comments,
            hotComments = hot,
            total = response["total"]?.jsonPrimitive?.intOrNull ?: comments.size,
            hasMore = response["more"]?.jsonPrimitive?.content?.toBoolean() ?: false
        )
    }

    private fun parseComments(raw: kotlinx.serialization.json.JsonElement?): List<CloudComment> {
        val array = raw as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.jsonArray.mapNotNull { element ->
            runCatching { parseComment(element.jsonObject) }.getOrNull()
        }
    }

    private fun parseComment(obj: JsonObject): CloudComment = CloudComment(
        commentId = obj["commentId"]?.jsonPrimitive?.longOrNull
            ?: obj["cid"]?.jsonPrimitive?.longOrNull
            ?: 0L,
        userId = obj["user"]?.jsonObject?.get("userId")?.jsonPrimitive?.longOrNull ?: 0L,
        nickname = obj["user"]?.jsonObject?.get("nickname")?.jsonPrimitive?.content.orEmpty(),
        avatarUrl = obj["user"]?.jsonObject?.get("avatarUrl")?.jsonPrimitive?.content.orEmpty(),
        content = obj["content"]?.jsonPrimitive?.content.orEmpty(),
        time = obj["time"]?.jsonPrimitive?.longOrNull ?: 0L,
        likedCount = obj["likedCount"]?.jsonPrimitive?.intOrNull ?: 0,
        liked = obj["liked"]?.jsonPrimitive?.content?.toBoolean() ?: false,
        replyCount = obj["replyCount"]?.jsonPrimitive?.intOrNull ?: 0
    )

    /** 从发送/回复响应里取新评论 id（不同部署可能放在 comment.commentId 或 data.commentId）。 */
    private fun parseNewCommentId(response: JsonObject): Long =
        response["comment"]?.jsonObject?.get("commentId")?.jsonPrimitive?.longOrNull
            ?: (response["data"] as? JsonObject)?.get("commentId")?.jsonPrimitive?.longOrNull
            ?: 0L

    private fun now(): String = System.currentTimeMillis().toString()

    companion object {
        /** 资源类型：歌曲。 */
        const val TYPE_SONG = 0

        /** 资源类型：歌单。 */
        const val TYPE_PLAYLIST = 2

        /** 资源类型：专辑。 */
        const val TYPE_ALBUM = 3

        /** 排序：推荐。 */
        const val SORT_RECOMMEND = 1

        /** 排序：热度。 */
        const val SORT_HOT = 2

        /** 排序：时间。 */
        const val SORT_TIME = 3
    }
}
