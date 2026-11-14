plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.entdecker"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.glas.entdecker"
        // MediaPipe (Bilderkennung) braucht Android 7, CameraX ebenso
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // MediaPipe gibt es nur für 64-Bit-ARM (alle Handys und Tablets der letzten Jahre)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Same fixed key as the launcher, so updates install over each other
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

    // Das Erkennungsmodell und die Geräusche unkomprimiert lassen (werden direkt aus der APK gelesen)
    androidResources {
        noCompress += listOf("tflite", "ogg")
    }

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
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // Bilderkennung auf dem Gerät, ohne Internet (Apache License 2.0)
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
}

// APK named with version, e.g. GlasEntdecker-1.0.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasEntdecker-$versionName.apk"
    }
}
