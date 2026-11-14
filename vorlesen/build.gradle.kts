plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.glas.vorlesen"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.glas.vorlesen"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        // Phones have ARM processors; leaving out the x86 text recognition saves about 20 MB
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Same fixed key as the launcher, so updates install over the old version from any computer
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

    // Store the native libraries compressed: a much smaller download (Android unpacks them once when installing)
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
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Camera preview and frames
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // Text recognition on the phone itself (model inside the APK, works offline, no Google account needed)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Piper voices: the sherpa-onnx speech engine (native, version 1.12.38) and the ONNX runtime it needs.
    // The Kotlin interface for speech is in com/k2fsa/sherpa/onnx/Tts.kt; the other parts of the package are not used.
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.6.10") { isTransitive = false }
    implementation("com.bihe0832.android:lib-onnx:8.5.4") { isTransitive = false }
    // Unpacking the downloaded voices (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
}

// APK named with its version, e.g. GlasVorleser-1.0.apk
@Suppress("DEPRECATION")
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "GlasVorleser-$versionName.apk"
    }
}

// Lets the unit test unpack a real voice package: ./gradlew :vorlesen:testDebugUnitTest -Ppiper.archive=…
tasks.withType<Test>().configureEach {
    project.findProperty("piper.archive")?.let { systemProperty("piper.archive", it) }
}
