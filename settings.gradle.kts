buildscript {
    dependencies {
        constraints {
            add("classpath", "org.bouncycastle:bcprov-jdk18on") {
                version {
                    strictly("[1.85,2.0[")
                    prefer("1.85.2")
                }
                because("CVE-2026-8763 and CVE-2026-13506 affect Bouncy Castle versions before 1.85")
            }
            add("classpath", "org.bouncycastle:bcpkix-jdk18on") {
                version {
                    strictly("[1.85,2.0[")
                    prefer("1.85")
                }
                because("Keep the Android build classpath on the patched Bouncy Castle 1.85 family")
            }
            add("classpath", "org.bouncycastle:bcutil-jdk18on") {
                version {
                    strictly("[1.85,2.0[")
                    prefer("1.85.1")
                }
                because("Keep the Android build classpath on the patched Bouncy Castle 1.85 family")
            }
        }
    }
}

pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "PointageTravail"
include(":app")
include(":app-v3")
