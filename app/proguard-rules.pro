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
