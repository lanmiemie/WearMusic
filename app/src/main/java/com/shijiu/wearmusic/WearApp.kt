package com.shijiu.wearmusic

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ohmusic.app.data.remote.NeteaseConstants
import com.shijiu.wearmusic.data.AccountRepository
import com.shijiu.wearmusic.data.AppPrefs
import com.shijiu.wearmusic.data.ExtraNeteaseApi
import com.shijiu.wearmusic.data.MusicRepository
import com.shijiu.wearmusic.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 全局依赖容器：手动装配，无 DI 框架。 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 网易云 API 层（core:netease 模块）────────────────────────
    val cookieStore = com.ohmusic.app.data.remote.CookieStore(appContext)
    val client = com.ohmusic.app.data.remote.NeteaseClient(cookieStore)
    val authApi = com.ohmusic.app.data.remote.api.AuthApi(client)
    val songApi = com.ohmusic.app.data.remote.api.CloudSongApi(client)
    val searchApi = com.ohmusic.app.data.remote.api.CloudSearchApi(client, songApi)
    val recommendApi = com.ohmusic.app.data.remote.api.RecommendApi(client, songApi)
    val likeApi = com.ohmusic.app.data.remote.api.CloudLikeApi(client)
    val playlistApi = com.ohmusic.app.data.remote.api.CloudPlaylistApi(client, songApi)
    val driveApi = com.ohmusic.app.data.remote.api.CloudDriveApi(client)
    val commentApi = com.ohmusic.app.data.remote.api.CloudCommentApi(client)
    val fmApi = com.ohmusic.app.data.remote.api.CloudFmApi(client)
    val djApi = com.ohmusic.app.data.remote.api.DjApi(client, songApi)
    val artistApi = com.ohmusic.app.data.remote.api.ArtistApi(client, songApi)
    val albumApi = com.ohmusic.app.data.remote.api.AlbumApi(client, songApi)
    val extraApi = ExtraNeteaseApi(client)

    // ── 应用层 ──────────────────────────────────────────────────
    val prefs = AppPrefs(appContext)
    val accountRepo = AccountRepository(appScope, authApi, cookieStore, playlistApi, prefs)
    val musicRepo = MusicRepository(
        accountRepo, songApi, searchApi, recommendApi, likeApi, playlistApi,
        driveApi, commentApi, fmApi, djApi, artistApi, albumApi, extraApi
    )
    val playbackManager = PlaybackManager(appContext, appScope, songApi, fmApi, musicRepo, prefs)
}

/** 供非 Compose 场景（Service）获取容器。 */
object ServiceLocator {
    @Volatile
    lateinit var container: AppContainer
        private set

    fun init(container: AppContainer) {
        this.container = container
    }
}

class WearApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ServiceLocator.init(container)
    }

    /**
     * Coil 全局 ImageLoader：
     * 网易云 CDN 校验 Referer，缺失时封面直接 403，这里统一注入。
     */
    override fun newImageLoader(): ImageLoader {
        val refererInterceptor = Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.url.host.endsWith("music.126.net")) {
                builder.header("Referer", NeteaseConstants.REFERER)
            }
            builder.header("User-Agent", NeteaseConstants.USER_AGENT)
            chain.proceed(builder.build())
        }
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(refererInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .crossfade(180)
            .build()
    }
}
