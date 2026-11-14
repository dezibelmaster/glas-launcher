plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.entdecker"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.glas.entdecker"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "2.1"
        // Handys und Tablets mit 64-Bit-ARM (alle der letzten Jahre); 32 Bit wäre für das Modell zu langsam
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
        noCompress += listOf("onnx", "ogg")
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
    // Bilderkennung auf dem Gerät, ohne Internet (ONNX Runtime, MIT-Lizenz)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

// APK named with version, e.g. GlasEntdecker-1.0.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasEntdecker-$versionName.apk"
    }
}
