package com.amaury.pointage

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

internal object AppCheckBuildProvider {
    const val name = "debug"

    fun install(appCheck: FirebaseAppCheck) {
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
