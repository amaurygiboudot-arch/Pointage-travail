package com.amaury.pointage

/** The switch stores the user's choice; it does not prove geofences are running. */
internal fun gpsAutomaticStatus(
    enabled: Boolean,
    precisePermission: Boolean,
    backgroundPermission: Boolean,
    systemLocationEnabled: Boolean,
    registrationCurrent: Boolean,
    registrationError: String? = null
): String = when {
    !enabled -> "GPS automatique désactivé"
    !precisePermission -> "Localisation précise à autoriser"
    !backgroundPermission -> "Autorise la localisation tout le temps"
    !systemLocationEnabled -> "Localisation de l’appareil désactivée — active-la dans les réglages"
    !registrationError.isNullOrBlank() -> registrationError
    !registrationCurrent -> "Vérification des zones GPS en cours…"
    else -> "Pointage GPS automatique actif"
}

internal fun shouldOpenSystemLocationSettings(
    enabled: Boolean,
    precisePermission: Boolean,
    backgroundPermission: Boolean,
    systemLocationEnabled: Boolean
): Boolean = enabled && precisePermission && backgroundPermission && !systemLocationEnabled
