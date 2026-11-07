# APK SLIM: release R8 rules (2026-10-15). R8 is release-only; debug
# keeps everything for the emulator.

# Tink (androidx.security:security-crypto): default R8 run fails on
# missing errorprone/javax.annotation classes — silence, keep the impl.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.meta.**
# Optional Tink/MediaPipe deps R8 flags as missing (http downloader,
# AutoValue, image pipeline, protobuf internals, joda-time): none are
# on the runtime path we use (EncryptedSharedPreferences file crypto,
# text-only LlmInference via reflection).
-dontwarn com.google.api.client.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.mediapipe.framework.image.**
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

# Reflection bridges: class/method names resolve via Class.forName at
# runtime, so R8 must keep the targets in release builds that ship
# them (full flavor; harmless when absent in lite).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.mediapipe.tasks.genai.llminference.** { *; }
# Own bridge (loaded by NAME from common code, never referenced
# directly — R8 cannot see the edge). The litertlm library classes
# it touches are traced normally through its direct references.
-keep class com.mywhisper.mobile.LiteRtLmBridge { *; }
