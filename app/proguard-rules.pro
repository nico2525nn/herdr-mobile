# kotlinx.serialization: keep generated serializers looked up by name at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembernames class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Serializers referenced via @Serializable on model classes.
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialInfo *;
}

# OkHttp / Okio: nothing special beyond the default file, but keep the platform check quiet.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JSch (com.github.mwiede fork, com.jcraft.jsch package): reflective channel loading.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Compose / AndroidX: R8 handles these via bundled rules; keep this file for app code only.
