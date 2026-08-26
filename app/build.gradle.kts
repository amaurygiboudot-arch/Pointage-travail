plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// The launcher artwork is stored as Base64 text because the connected GitHub API only writes UTF-8 files.
// Recreate the real WebP resource during Gradle configuration so local and CI builds use the same icon.
val horaTrackIconSource = file("icon-source/horatrack_launcher.b64")
val horaTrackGeneratedRes = layout.buildDirectory.dir("generated/horatrackIcon/res").get().asFile
val horaTrackLauncherFile = file("${horaTrackGeneratedRes.path}/drawable-nodpi/horatrack_launcher.webp")
if (horaTrackIconSource.exists()) {
    val encoded = horaTrackIconSource.readText().filterNot { it.isWhitespace() }
    val bytes = java.util.Base64.getDecoder().decode(encoded)
    horaTrackLauncherFile.parentFile.mkdirs()
    horaTrackLauncherFile.writeBytes(bytes)
}

android {
    namespace = "com.amaury.pointage"
    compileSdk = 36

    sourceSets.getByName("main").res.srcDir(horaTrackGeneratedRes)

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
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 9
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.9"

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
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("io.sentry:sentry-android:8.43.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

kotlin { jvmToolchain(17) }
