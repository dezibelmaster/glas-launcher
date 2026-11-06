plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.smart"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.glas.smart"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "3.5"

        // Phones only: keeps the WebRTC library from bloating the APK with emulator builds
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Same fixed key as the launcher, so updates install over earlier versions
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

dependencies {
    // Draws the QR code for setting up another phone (pure Java, no Google services)
    implementation("com.google.zxing:core:3.5.3")
    // Plays the live picture of cameras and video doorbells (HLS from the Tuya cloud)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // WebRTC live picture with HD/SD choice, handshake over Tuya's MQTT broker (like go2rtc)
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}

// APK named with its version, e.g. GlasSmart-1.0.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasSmart-$versionName.apk"
    }
}
