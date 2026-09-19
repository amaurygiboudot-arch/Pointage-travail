buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    configurations.configureEach {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86"
        )
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("com.google.gms:google-services:4.4.3")
    }
}
