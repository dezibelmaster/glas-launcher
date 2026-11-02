plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.glas.launcher"
        minSdk = 25
        targetSdk = 35
        versionCode = 36
        versionName = "2.34"
    }

    // One fixed key for every build, so updates install over earlier versions
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

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// APK named with its version, e.g. GlasLauncher-2.25.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasLauncher-$versionName.apk"
    }
}
