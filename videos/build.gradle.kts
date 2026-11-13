plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.launcher.videos"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.glas.launcher.videos"
        minSdk = 25
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // Same fixed key as the launcher
    signingConfigs {
        create("glas") {
            storeFile = rootProject.file("keystore/glas-launcher.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("glas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// APK named with its version, e.g. GlasNaturVideos-2.25.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasNaturVideos-$versionName.apk"
    }
}
