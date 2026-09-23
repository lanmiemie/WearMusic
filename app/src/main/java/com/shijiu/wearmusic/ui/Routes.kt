package com.shijiu.wearmusic.ui

/** 导航路由。 */
object Routes {
    const val HOME = "home"
    const val LOGIN = "login"
    const val ACCOUNT = "account"
    const val PLAYER = "player"
    const val LYRICS = "lyrics"
    const val DAILY = "daily"
    const val FM = "fm"
    const val HEART = "heart"
    const val RADAR = "radar"
    const val PERSONALIZED = "personalized"
    const val TOPLIST = "toplist"
    const val SEARCH = "search"
    const val CLOUD = "cloud"
    const val MINE = "mine"
    const val PLAYLIST = "playlist/{id}"
    const val PLAYLIST_EDIT = "playlistEdit/{id}"
    const val CREATE_PLAYLIST = "createPlaylist"
    const val ADD_TO_PLAYLIST = "addToPlaylist/{songId}"
    const val ALBUM = "album/{id}"
    const val ARTIST = "artist/{id}"
    const val DJ = "dj/{id}"
    const val COMMENTS = "comments/{type}/{id}?title={title}"
    const val ABOUT = "about"

    fun playlist(id: Long) = "playlist/$id"
    fun playlistEdit(id: Long) = "playlistEdit/$id"
    fun addToPlaylist(songId: Long) = "addToPlaylist/$songId"
    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun dj(id: Long) = "dj/$id"
    fun comments(type: Int, id: Long, title: String) =
        "comments/$type/$id?title=${android.net.Uri.encode(title.ifBlank { "评论" })}"
}
