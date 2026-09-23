package com.ohmusic.app.data.remote

/**
 * 网易云网关返回的业务错误。
 *
 * 服务端 HTTP 状态码恒为 200，真正的结果码在响应体的 `code` 字段里，
 * 因此需要把业务码一路带到 UI，才能区分「未登录」「无版权」等场景。
 */
class NeteaseApiException(
    val code: Int,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** 是否为需要登录的场景（301 / 250 等）。 */
    val isAuthRequired: Boolean
        get() = code == NeteaseConstants.CODE_NEED_LOGIN || code == 250

    /** 是否为网络层失败（未拿到业务码）。 */
    val isNetwork: Boolean
        get() = code == CODE_NETWORK

    companion object {
        const val CODE_NETWORK = -1
        const val CODE_PARSE = -2

        fun network(cause: Throwable): NeteaseApiException = NeteaseApiException(
            code = CODE_NETWORK,
            message = cause.message ?: "网络请求失败",
            cause = cause
        )

        fun parse(message: String, cause: Throwable? = null): NeteaseApiException =
            NeteaseApiException(code = CODE_PARSE, message = message, cause = cause)
    }
}
