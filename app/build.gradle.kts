plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gandharv.d3dcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gandharv.hicamviewer"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "2.2"
    }
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "d3dcam"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the permanent key when it is provided by CI, otherwise with the debug key.
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
