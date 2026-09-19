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

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("com.google.gms.google-services") version "4.4.3" apply false
}
