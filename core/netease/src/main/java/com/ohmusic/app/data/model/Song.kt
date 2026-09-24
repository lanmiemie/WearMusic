package com.ohmusic.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 曲目来源。 */
object SongSource {
    /** 设备本地音乐（MediaStore 扫描而来）。 */
    const val LOCAL = "local"

    /** 网易云在线曲目。 */
    const val NETEASE = "netease"
}

/**
 * 统一曲目模型。
 *
 * 本地曲目与网易云在线曲目共用这一张表：
 * - [source] 区分来源，扫描本地音乐时只会替换 `local` 的行，不会误删在线曲目；
 * - [id] 对在线曲目使用**网易云 songId 的负值**，与 MediaStore 的正数 id 在数值空间上
 *   完全隔离，因此播放队列按 `Long` id 持久化的既有逻辑无需改动。
 */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val id: Long,          // 本地：MediaStore ID；在线：-网易云 songId
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,                 // 毫秒
    val albumId: Long,                  // 本地用于获取封面；在线为网易云专辑 id
    val uri: String,                    // 本地：content:// URI；在线为空（播放时实时换取直链）
    val dateAdded: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,    // 本地：收藏标记；在线收藏以网易云「我喜欢的音乐」为准
    val source: String = SongSource.LOCAL,
    /** 网易云 songId，仅 [source] 为 `netease` 时有值。 */
    val songId: Long? = null,
    /** 在线曲目封面地址，仅 [source] 为 `netease` 时有值。 */
    val coverUrl: String? = null,
    /**
     * 列表缩略图用的封面地址。
     *
     * 网易云的 `/search` 接口只下发 `picId`，拿不到 `picUrl`；在线曲目入库时
     * 会顺带记下一个已经带好 `?param=100y100` 的小图地址。列表直接用它，
     * 省掉一次「原图 → 服务端裁剪」的协商开销，滑动时明显更跟手；
     * 播放页则改用 [coverUrl] 的高清版本，两边互不影响。
     *
     * 本地曲目不使用该字段（列表与播放页都走 MediaStore 解析）。
     */
    val smallCoverUrl: String? = null,
    /** 网易云主歌手 id，仅 [source] 为 `netease` 时有值，用于从歌曲跳转歌手页。 */
    val artistId: Long? = null,
    /**
     * 网易云**全部**歌手 id，按 "/" 拼接，与 [artist] 里的名字逐位对应，
     * 仅 [source] 为 `netease` 时有值。多歌手曲目据此为每位歌手生成独立入口。
     */
    val artistIds: String? = null
) {
    val isLocal: Boolean get() = source == SongSource.LOCAL
    val isOnline: Boolean get() = source == SongSource.NETEASE

    /** 全部有效歌手 id；[artistIds] 缺失时退回 [artistId]，保证旧数据仍可跳转主歌手。 */
    val artistIdList: List<Long>
        get() = artistIds?.split("/")?.mapNotNull { it.toLongOrNull() }?.filter { it > 0 }
            ?: listOfNotNull(artistId?.takeIf { it > 0 })
}
