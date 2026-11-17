# Release R8 rules. R8 is release-only; debug keeps everything.

# Tink (androidx.security:security-crypto): default R8 run fails on
# missing errorprone/javax.annotation classes — silence, keep the impl.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.meta.**
# Optional Tink deps R8 flags as missing (http downloader, AutoValue,
# protobuf internals, joda-time): not on the EncryptedSharedPreferences
# file-crypto path we use.
-dontwarn com.google.api.client.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.protobuf.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# Rust core JNI: CoreBridge declares the native entry points — R8 must
# not strip or rename them (or the class).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.mywhisper.mobile.CoreBridge { *; }
# Called back from Rust by method name (`cleanup`) over JNI.
-keep class com.mywhisper.mobile.LocalLlmCleanupRunner { *; }

# On-device AI pack: libmwlocalai.so binds JNI symbols to this exact
# class name (Java_com_mywhisper_mobile_LocalAiNative_*).
-keep class com.mywhisper.mobile.LocalAiNative { *; }
