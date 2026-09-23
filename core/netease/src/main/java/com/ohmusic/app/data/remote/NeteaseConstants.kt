package com.ohmusic.app.data.remote

/**
 * 网易云 API 网关的全局约定。
 *
 * cookie 不通过 HTTP 头传递，而是放在请求 JSON body 的 `cookie` 字段里，
 * 这一点由服务端（NeteaseCloudMusicApi 系）约定，见 [NeteaseClient]。
 */
object NeteaseConstants {

    /** 默认网关地址；构造 [NeteaseClient] 时可通过 `baseUrl` 参数指向自建实例。 */
    const val DEFAULT_BASE_URL: String = "https://mymusic.rbook.site"

    /**
     * 网易云 CDN 会校验这两个头，缺失时音频与封面图都可能返回 403。
     * 播放链路和封面加载链路都必须带上。
     */
    const val REFERER: String = "https://music.163.com/"

    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    const val CONTENT_TYPE_JSON: String = "application/json; charset=utf-8"

    /** 业务成功码。 */
    const val CODE_SUCCESS = 200

    /** 需要登录。服务端在未携带有效 cookie 时会返回该码。 */
    const val CODE_NEED_LOGIN = 301

    /** 二维码登录状态码。 */
    const val QR_EXPIRED = 800
    const val QR_WAITING_SCAN = 801
    const val QR_WAITING_CONFIRM = 802
    const val QR_AUTHORIZED = 803

    /** 游客登录（`/register/anonimous`）把 400 当作成功，NMTID 通过 Set-Cookie 下发。 */
    const val CODE_ANONYMOUS_OK = 400

    const val NMTID_NAME = "NMTID"

    /** 网易云曲目在本地数据库中的 id 使用 `-songId`，与 MediaStore 的正数 id 完全隔离开。 */
    fun negativeIdFor(songId: Long): Long = -songId

    fun songIdFromNegativeId(id: Long): Long = -id
}
