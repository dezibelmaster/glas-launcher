plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.browser"
    // GeckoView (the Firefox engine) needs the newest SDK to build against
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "de.glas.browser"
        // GeckoView runs from Android 8
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    // One APK per processor type; the launcher installs the one that fits the TV
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    // Same fixed key as the launcher, so they trust each other (phone remote) and updates install
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

    // Store the engine compressed: about 75 instead of 200 MB to download (Android unpacks it once when installing)
    packaging {
        jniLibs {
            useLegacyPackaging = true
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

dependencies {
    // Firefox engine with WebExtensions (uBlock Origin and others), Mozilla Public License 2.0
    implementation("org.mozilla.geckoview:geckoview:159.0.20261102144711")
    // QR code for the phone remote
    implementation("com.google.zxing:core:3.5.3")
}

// APK named with version and processor type, e.g. GlasBrowser-1.3-arm64-v8a.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        val abi = (this as com.android.build.gradle.internal.api.ApkVariantOutputImpl).getFilter("ABI") ?: "universal"
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasBrowser-$versionName-$abi.apk"
    }
}
