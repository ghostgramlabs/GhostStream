# NOTE: Minification is currently disabled in app/build.gradle.kts because the
# webrtc-sdk artifact's native library crashes in JNI_OnLoad on Android 16 when
# R8 runs (any combination of shrinking/optimization/obfuscation reproduces it,
# even with broad -keep class org.webrtc.** { *; } rules). Rules below are kept
# as a record of what was tried so future re-enablement attempts have a starting
# point. They are inert while isMinifyEnabled = false.

# GhostStream relies on kotlinx serialization and Ktor route data classes.
-keepclassmembers class kotlinx.serialization.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.slf4j.**

# Ktor Server Rules required for minification (R8)
-keep class io.ktor.** { *; }
-keep class org.slf4j.** { *; }
-keepclassmembers class io.ktor.** { *; }
-keepclassmembers class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }

# WebRTC keep rules (insufficient on their own — see note above).
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class * extends org.webrtc.** { *; }
-keep class * implements org.webrtc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Media3 muxer
-keep class androidx.media3.muxer.** { *; }
-dontwarn androidx.media3.muxer.**
