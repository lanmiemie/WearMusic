# :core:netease 消费方混淆规则（随 AAR 自动附带）

# kotlinx-serialization：保留 DTO 的 serializer 伴侣对象
-keepclassmembers class com.ohmusic.app.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.ohmusic.app.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Song 既被 kotlinx-serialization 反射读取，也可能映射进宿主 Room 数据库
-keepclassmembers class com.ohmusic.app.data.model.** { *; }
