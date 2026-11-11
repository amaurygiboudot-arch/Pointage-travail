pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }

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
            "org.bouncycastle:bcutil-jdk18on:1.86",
            // CVE-2026-102554: Guava deserialization allocation DoS fixed in 33.7.2.
            "com.google.guava:guava:33.7.2-jre"
        )
    }
}

dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "PointageTravail"
include(":app")
include(":app-v3")
