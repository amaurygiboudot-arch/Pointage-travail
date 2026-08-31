package com.amaury.pointage.v2

import android.net.Uri

/** Liens officiels PISTE nécessaires à la configuration manuelle de l'accès API Légifrance. */
object PisteAccessSetupV2 {
    val registrationUri: Uri = Uri.parse("https://piste.gouv.fr/registration")
    val applicationsUri: Uri = Uri.parse("https://piste.gouv.fr")

    const val SETUP_REQUIRED_MESSAGE =
        "Créer un compte PISTE, créer une application de production, accepter les CGU Légifrance et activer l’API Légifrance."
}
