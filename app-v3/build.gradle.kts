plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amaury.pointage.v3"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val storePath = System.getenv("POINTAGE_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("POINTAGE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POINTAGE_KEY_ALIAS")
                keyPassword = System.getenv("POINTAGE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.amaury.pointage.v3"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "3.0.0-alpha01"
    }

    buildTypes {
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
}

kotlin { jvmToolchain(17) }
