plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.kids"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.glas.kids"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("glas")
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

// APK named with its version, e.g. GlasKids-1.0.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasKids-$versionName.apk"
    }
}
