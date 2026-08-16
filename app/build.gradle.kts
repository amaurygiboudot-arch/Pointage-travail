plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amaury.pointage"
    compileSdk = 35

    signingConfigs {
        create("stableDebug") {
            storeFile = rootProject.file("pointage-test.keystore")
            storePassword = "PointageTest2026"
            keyAlias = "pointage"
            keyPassword = "PointageTest2026"
        }
    }

    defaultConfig {
        applicationId = "com.amaury.pointage"
        minSdk = 23
        targetSdk = 35
        versionCode = 9
        versionName = "1.9"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
}

kotlin {
    jvmToolchain(17)
}
