plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cwbridge.helper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cwbridge.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.1.2"
    }

    val debugStoreFile = rootProject.file("keystore/cwbridge-debug.p12")
    signingConfigs {
        create("debugFixed") {
            if (debugStoreFile.exists()) {
                storeFile = debugStoreFile
                storePassword = "cwbridge-debug"
                keyAlias = "cwbridge-debug"
                keyPassword = "cwbridge-debug"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (debugStoreFile.exists()) {
                signingConfig = signingConfigs.getByName("debugFixed")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
