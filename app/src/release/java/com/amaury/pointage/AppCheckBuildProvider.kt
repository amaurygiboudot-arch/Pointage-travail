package com.amaury.pointage

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

internal object AppCheckBuildProvider {
    const val name = "play_integrity"

    fun install(appCheck: FirebaseAppCheck) {
        appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
