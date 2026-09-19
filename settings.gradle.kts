pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }

buildscript {
    dependencies {
        constraints {
            add("classpath", "org.bouncycastle:bcprov-jdk18on") {
                version { strictly("1.86") }
                because("Keep the Android build classpath on the current patched Bouncy Castle family")
            }
            add("classpath", "org.bouncycastle:bcpkix-jdk18on") {
                version { strictly("1.86") }
                because("Keep the Android build classpath on one coherent published Bouncy Castle family")
            }
            add("classpath", "org.bouncycastle:bcutil-jdk18on") {
                version { strictly("1.86") }
                because("Keep the Android build classpath on one coherent published Bouncy Castle family")
            }
        }
    }
}

dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "PointageTravail"
include(":app")
include(":app-v3")
