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

# WebRTC: heavy JNI bridge — R8 must not strip or rename anything in org.webrtc.
# Native code calls back into these classes by exact name/signature; without this
# rule Live Screen capture and audio pump fail with NoSuchMethodError on release.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keepnames class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Subclasses we register with WebRTC (callbacks invoked from native code).
-keep class * implements org.webrtc.PeerConnection$Observer { *; }
-keep class * implements org.webrtc.SdpObserver { *; }
-keep class * implements org.webrtc.VideoSink { *; }

# Media3 muxer uses native code paths via reflection in some helper classes.
-keep class androidx.media3.muxer.** { *; }
-dontwarn androidx.media3.muxer.**
