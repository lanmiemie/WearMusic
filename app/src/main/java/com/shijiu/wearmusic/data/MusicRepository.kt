package com.shijiu.wearmusic.data

import com.ohmusic.app.data.model.Song
import com.ohmusic.app.data.remote.api.AlbumApi
import com.ohmusic.app.data.remote.api.ArtistApi
import com.ohmusic.app.data.remote.api.CloudAlbumSummary
import com.ohmusic.app.data.remote.api.CloudArtist
import com.ohmusic.app.data.remote.api.CloudCommentApi
import com.ohmusic.app.data.remote.api.CloudCommentPage
import com.ohmusic.app.data.remote.api.CloudDj
import com.ohmusic.app.data.remote.api.CloudDriveApi
import com.ohmusic.app.data.remote.api.CloudDriveSong
import com.ohmusic.app.data.remote.api.CloudFmApi
import com.ohmusic.app.data.remote.api.CloudLikeApi
import com.ohmusic.app.data.remote.api.CloudPlaylist
import com.ohmusic.app.data.remote.api.CloudPlaylistApi
import com.ohmusic.app.data.remote.api.CloudPlaylistDetail
import com.ohmusic.app.data.remote.api.CloudSearchApi
import com.ohmusic.app.data.remote.api.CloudSongApi
import com.ohmusic.app.data.remote.api.CloudToplist
import com.ohmusic.app.data.remote.api.DjApi
import com.ohmusic.app.data.remote.api.DjProgram
import com.ohmusic.app.data.remote.api.RecommendApi
import com.ohmusic.app.data.remote.dto.NeteaseLyricResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 搜索分类。 */
enum class SearchType(val label: String) {
    SONGS("单曲"),
    PLAYLISTS("歌单"),
    ARTISTS("歌手"),
    ALBUMS("专辑"),
    DJS("播客")
}

/** 一次分类搜索的结果。 */
data class SearchResults(
    val songs: List<Song> = emptyList(),
    val playlists: List<CloudPlaylist> = emptyList(),
    val artists: List<CloudArtist> = emptyList(),
    val albums: List<CloudAlbumSummary> = emptyList(),
    val djs: List<CloudDj> = emptyList()
)

/**
 * 音乐数据仓库：把 core:netease 的接口按 UI 需要整形暴露，
 * 全部返回 [UiResult]，UI 层不再感知异常细节。
 */
class MusicRepository(
    private val account: AccountRepository,
    private val songApi: CloudSongApi,
    private val searchApi: CloudSearchApi,
    private val recommendApi: RecommendApi,
    private val likeApi: CloudLikeApi,
    private val playlistApi: CloudPlaylistApi,
    private val driveApi: CloudDriveApi,
    private val commentApi: CloudCommentApi,
    private val fmApi: CloudFmApi,
    private val djApi: DjApi,
    private val artistApi: ArtistApi,
    private val albumApi: AlbumApi,
    private val extraApi: ExtraNeteaseApi
) {

    // ── 喜欢列表缓存 ────────────────────────────────────────────

    private val _likedIds = MutableStateFlow<Set<Long>>(emptySet())
    val likedIds: StateFlow<Set<Long>> = _likedIds.asStateFlow()

    private var likedLoaded = false

    /** 登录状态下拉取「我喜欢的音乐」id 集合，用于红心按钮。 */
    suspend fun ensureLikedLoaded() {
        if (likedLoaded || !account.isLoggedIn) return
        runCatching { likeApi.getLikedSongIds(account.uid) }.onSuccess {
            _likedIds.value = it.toSet()
            likedLoaded = true
        }
    }

    fun isLiked(songId: Long?): Boolean = songId != null && _likedIds.value.contains(songId)

    /** 红心 / 取消红心，返回操作后的状态。 */
    suspend fun toggleLike(songId: Long): Boolean {
        val target = !isLiked(songId)
        likeApi.setLiked(songId, target)
        _likedIds.value =
            if (target) _likedIds.value + songId else _likedIds.value - songId
        return target
    }

    // ── 推荐 / 榜单 ─────────────────────────────────────────────

    /** 每日推荐；`date` 非空时为历史日推（YYYY-MM-DD）。 */
    suspend fun dailySongs(date: String? = null): UiResult<List<Song>> =
        safeApi { recommendApi.getDailyRecommendSongs(date) }

    /** 推荐歌单（匿名可用）。 */
    suspend fun personalizedPlaylists(): UiResult<List<CloudPlaylist>> =
        safeApi { extraApi.personalizedPlaylists() }

    /** 雷达歌单（需登录）。 */
    suspend fun radarPlaylists(): UiResult<List<CloudPlaylist>> =
        safeApi { extraApi.radarPlaylists() }

    /** 所有排行榜。 */
    suspend fun toplists(): UiResult<List<CloudToplist>> =
        safeApi { recommendApi.getToplists() }

    // ── 私人漫游 / 心动 ─────────────────────────────────────────

    suspend fun fmSongs(): UiResult<List<Song>> = safeApi { fmApi.getPersonalFm() }

    /** 私人漫游追加一批（供播放队列尾部加载，失败返回空）。 */
    suspend fun fmSongsRaw(): List<Song> = runCatching { fmApi.getPersonalFm() }.getOrDefault(emptyList())

    /** 心动模式 / 智能推荐。 */
    suspend fun heartSongs(seedSongId: Long, playlistId: Long): UiResult<List<Song>> =
        safeApi { fmApi.getIntelligenceList(seedSongId, playlistId) }

    // ── 云盘 ────────────────────────────────────────────────────

    suspend fun cloudDrive(offset: Int = 0): UiResult<Pair<List<CloudDriveSong>, Int>> =
        safeApi { driveApi.getCloudSongs(limit = 100, offset = offset) }

    suspend fun deleteCloudSong(cloudSongId: Long): UiResult<Unit> =
        safeApi { driveApi.deleteSong(cloudSongId) }

    // ── 搜索 ────────────────────────────────────────────────────

    suspend fun search(keywords: String, type: SearchType): UiResult<SearchResults> = safeApi {
        when (type) {
            SearchType.SONGS -> SearchResults(songs = searchApi.searchSongs(keywords))
            SearchType.PLAYLISTS -> SearchResults(playlists = searchApi.searchPlaylists(keywords))
            SearchType.ARTISTS -> SearchResults(artists = searchApi.searchArtists(keywords))
            SearchType.ALBUMS -> SearchResults(albums = searchApi.searchAlbums(keywords))
            SearchType.DJS -> SearchResults(djs = searchApi.searchDjs(keywords))
        }
    }

    suspend fun hotSearch(): UiResult<List<String>> = safeApi {
        searchApi.getHotSearch().map { it.keyword }
    }

    suspend fun searchSuggest(keywords: String): UiResult<List<String>> = safeApi {
        searchApi.searchSuggest(keywords).map { it.keyword }
    }

    // ── 我的音乐 ────────────────────────────────────────────────

    suspend fun userPlaylists(): UiResult<List<CloudPlaylist>> = safeApi {
        check(account.uid > 0) { "需要登录网易云账号" }
        playlistApi.getUserPlaylists(account.uid)
    }

    suspend fun mineAlbums(): UiResult<List<CloudAlbumSummary>> = safeApi {
        check(account.uid > 0) { "需要登录网易云账号" }
        albumApi.getSubscribedAlbums()
    }

    suspend fun mineDjs(): UiResult<List<CloudDj>> = safeApi {
        check(account.uid > 0) { "需要登录网易云账号" }
        djApi.getSubscribedDjs()
    }

    // ── 歌单 ────────────────────────────────────────────────────

    suspend fun playlistDetail(playlistId: Long): UiResult<CloudPlaylistDetail> =
        safeApi { playlistApi.getPlaylistDetail(playlistId) }

    suspend fun subscribePlaylist(playlistId: Long, subscribe: Boolean): UiResult<Unit> =
        safeApi { playlistApi.subscribePlaylist(playlistId, subscribe) }

    suspend fun updatePlaylistMeta(playlistId: Long, name: String, desc: String): UiResult<Unit> =
        safeApi { playlistApi.updatePlaylist(playlistId, name, desc) }

    suspend fun setPlaylistPrivacy(playlistId: Long, isPrivate: Boolean): UiResult<Unit> =
        safeApi { extraApi.setPlaylistPrivacy(playlistId, isPrivate) }

    suspend fun createPlaylist(name: String, isPrivate: Boolean): UiResult<Long> =
        safeApi { playlistApi.createPlaylist(name, isPrivate) }

    suspend fun deletePlaylist(playlistId: Long): UiResult<Unit> =
        safeApi { playlistApi.deletePlaylist(playlistId) }

    suspend fun addPlaylistTracks(playlistId: Long, songIds: List<Long>): UiResult<Boolean> =
        safeApi { playlistApi.modifyPlaylistTracks(playlistId, songIds, true) }

    suspend fun removePlaylistTracks(playlistId: Long, songIds: List<Long>): UiResult<Boolean> =
        safeApi { playlistApi.modifyPlaylistTracks(playlistId, songIds, false) }

    // ── 专辑 / 歌手 / 播客 ──────────────────────────────────────

    suspend fun albumDetail(albumId: Long) = safeApi { albumApi.getAlbumDetail(albumId) }

    suspend fun subscribeAlbum(albumId: Long, subscribe: Boolean): UiResult<Unit> =
        safeApi { albumApi.subscribeAlbum(albumId, subscribe) }

    suspend fun artistDetail(artistId: Long) = safeApi { artistApi.getArtistDetail(artistId) }

    suspend fun artistHotSongs(artistId: Long): UiResult<List<Song>> =
        safeApi { artistApi.getArtistHotSongs(artistId) }

    suspend fun artistSongs(
        artistId: Long,
        order: String,
        offset: Int
    ): UiResult<List<Song>> = safeApi { artistApi.getArtistSongs(artistId, order, 50, offset) }

    suspend fun artistAlbums(artistId: Long, offset: Int): UiResult<List<CloudAlbumSummary>> =
        safeApi { artistApi.getArtistAlbums(artistId, 30, offset) }

    suspend fun djDetail(rid: Long) = safeApi { djApi.getDjDetail(rid) }

    suspend fun djPrograms(rid: Long, offset: Int): UiResult<List<DjProgram>> =
        safeApi { djApi.getDjPrograms(rid, 30, offset) }

    suspend fun subscribeDj(rid: Long, subscribe: Boolean): UiResult<Unit> =
        safeApi { djApi.subscribeDj(rid, subscribe) }

    // ── 评论 ────────────────────────────────────────────────────

    suspend fun comments(
        type: Int,
        resourceId: Long,
        pageNo: Int,
        sortType: Int
    ): UiResult<CloudCommentPage> = safeApi {
        commentApi.getComments(type, resourceId, pageNo, 20, sortType)
    }

    suspend fun sendComment(type: Int, resourceId: Long, content: String): UiResult<Long> =
        safeApi { commentApi.sendComment(type, resourceId, content) }

    suspend fun likeComment(
        type: Int,
        resourceId: Long,
        commentId: Long,
        like: Boolean
    ): UiResult<Unit> = safeApi { commentApi.likeComment(type, resourceId, commentId, like) }

    /** 评论资源类型：电台（播客本身）。 */
    companion object {
        const val COMMENT_TYPE_RADIO = 7

        /** 歌词缓存上限（LRU）。 */
        const val LYRIC_CACHE_SIZE = 32
    }

    // ── 歌词 / 详情 ─────────────────────────────────────────────

    /** 歌词缓存：播放页与歌词页共享同一份响应，避免重复请求。 */
    private val lyricCache = object : LinkedHashMap<Long, NeteaseLyricResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, NeteaseLyricResponse>): Boolean =
            size > LYRIC_CACHE_SIZE
    }

    suspend fun lyric(songId: Long): UiResult<NeteaseLyricResponse> {
        lyricCache[songId]?.let { return UiResult.Success(it) }
        return when (val result = safeApi { songApi.getLyric(songId) }) {
            is UiResult.Success -> {
                lyricCache[songId] = result.data
                result
            }
            else -> result
        }
    }

    /** 清空歌词缓存（清理缓存入口）。 */
    fun clearLyricCache() {
        lyricCache.clear()
    }

    suspend fun songDetail(songId: Long): Song? =
        runCatching { songApi.getSongDetails(listOf(songId)).firstOrNull() }.getOrNull()
}
