/*
 * :core:netease — 网易云 API 层独立模块
 *
 * 从 OHMusic 拆出的网易云 API 网关客户端，可被任意 Android 工程复用：
 * - 传输层：OkHttp + kotlinx.serialization，cookie 走 JSON body（网关约定）
 * - 接口层：10 个 Api 类覆盖登录/搜索/歌单/专辑/歌手/电台/云盘/评论/FM/推荐
 * - 模型层：统一曲目模型 Song（Room @Entity，可映射进宿主的本地数据库）
 * - 依赖注入：仅使用 javax.inject 注解，不绑定任何具体 DI 框架
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ohmusic.app.core.netease"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Song 是 Room @Entity：annotations 需要 compile 可见，宿主工程自持 Room 编译器
    api("androidx.room:room-runtime:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // 只用 @Inject/@Singleton 注解，不引入 Dagger 运行时
    implementation("javax.inject:javax.inject:1")

    testImplementation("junit:junit:4.13.2")
}
