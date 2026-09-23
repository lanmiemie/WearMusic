# 保持 kotlinx.serialization 生成的序列化器
-keepclassmembers class com.ohmusic.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.ohmusic.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ohmusic.app.**$$serializer { *; }
