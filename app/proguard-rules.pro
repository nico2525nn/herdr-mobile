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

# BouncyCastle EdDSA signer, loaded reflectively via JSch.setConfig("ssh-ed25519", ...).
# Without this R8 drops Ed25519Signer and key auth fails on release builds only.
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.signers.Ed448Signer { *; }
-keep class org.bouncycastle.crypto.params.Ed25519* { *; }
-keep class org.bouncycastle.crypto.params.Ed448* { *; }
-keep class org.bouncycastle.math.ec.rfc8032.Ed25519 { *; }
-keep class org.bouncycastle.math.ec.rfc7748.X25519Field { *; }

# Compose / AndroidX: R8 handles these via bundled rules; keep this file for app code only.
# Test APK only (releaseAndroidTest shares this file): error-prone annotations
# reference javax.lang.model, absent on Android.
-dontwarn javax.lang.model.element.Modifier
