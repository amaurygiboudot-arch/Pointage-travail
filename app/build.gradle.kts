plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amaury.pointage"
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
        applicationId = "com.amaury.pointage"
        minSdk = 23
        targetSdk = 36
        versionCode = 9
        versionName = "1.9"

        fun envString(name: String): String = (System.getenv(name) ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        buildConfigField("String", "SENTRY_DSN", "\"${envString("SENTRY_DSN")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${envString("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${envString("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${envString("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${envString("FIREBASE_SENDER_ID")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("io.sentry:sentry-android:8.43.0")
}

kotlin { jvmToolchain(17) }
