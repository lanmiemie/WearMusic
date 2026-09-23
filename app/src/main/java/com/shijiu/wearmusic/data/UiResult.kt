package com.shijiu.wearmusic.data

import com.ohmusic.app.data.remote.NeteaseApiException

/** 统一的 UI 层请求结果。 */
sealed interface UiResult<out T> {
    data class Success<T>(val data: T) : UiResult<T>
    data class Failure(
        val message: String,
        val needLogin: Boolean = false
    ) : UiResult<Nothing>

    val isSuccess: Boolean get() = this is Success
}

/** 成功时执行动作，失败时原样返回。 */
inline fun <T> UiResult<T>.onSuccess(block: (T) -> Unit): UiResult<T> {
    if (this is UiResult.Success) block(data)
    return this
}

/** 失败时执行动作。 */
inline fun <T> UiResult<T>.onFailure(block: (UiResult.Failure) -> Unit): UiResult<T> {
    if (this is UiResult.Failure) block(this)
    return this
}

/** 把 API 异常翻译成可展示的文案。 */
suspend fun <T> safeApi(block: suspend () -> T): UiResult<T> = try {
    UiResult.Success(block())
} catch (error: NeteaseApiException) {
    UiResult.Failure(
        message = if (error.isAuthRequired) "需要登录网易云账号" else (error.message ?: "请求失败"),
        needLogin = error.isAuthRequired
    )
} catch (error: IllegalStateException) {
    UiResult.Failure(message = error.message ?: "数据异常")
} catch (error: Exception) {
    UiResult.Failure(message = "网络异常：${error.message ?: "未知错误"}")
}
