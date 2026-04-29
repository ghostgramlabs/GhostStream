import java.util.Properties
import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

android {
    namespace = "com.ghostgramlabs.directserve"
    compileSdk = 35
    val releaseKeystorePath = keystoreProperties.getProperty("storeFile") ?: "ghoststream-release.jks"
    val releaseKeystoreFile = rootProject.file(releaseKeystorePath)
    val hasReleaseSigning = releaseKeystoreFile.exists() &&
        !keystoreProperties.getProperty("storePassword").isNullOrBlank() &&
        !keystoreProperties.getProperty("keyAlias").isNullOrBlank() &&
        !keystoreProperties.getProperty("keyPassword").isNullOrBlank()

    defaultConfig {
        applicationId = "com.ghostgramlabs.directserve"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.0.23"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 minification (any combination of shrinking/optimization/obfuscation)
            // breaks WebRTC's JNI_OnLoad with a SIGTRAP/RTC_CHECK assertion on
            // Android 16, even with broad -keep rules for org.webrtc.**. The native
            // library expects a class layout R8 cannot guarantee. We trade the ~13MB
            // size benefit for a working Live Screen feature.
            isMinifyEnabled = false
            isShrinkResources = false
            ndk {
                debugSymbolLevel = "FULL"
            }
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val packageReleaseNativeSymbols by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages release native symbols for Play Console upload."
    archiveFileName.set("native-debug-symbols.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/native-debug-symbols/release"))
    from(layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib"))
    dependsOn("mergeReleaseNativeLibs")
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy(packageReleaseNativeSymbols)
}

dependencies {
    implementation(project(":core:resources"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:media"))
    implementation(project(":core:storage"))
    implementation(project(":core:session"))
    implementation(project(":core:settings"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:session"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:networksetup"))
    implementation(project(":feature:history"))
    implementation(project(":core:history"))
    implementation(project(":webassets"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.google.webrtc)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.muxer)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    debugImplementation(libs.compose.ui.tooling)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
