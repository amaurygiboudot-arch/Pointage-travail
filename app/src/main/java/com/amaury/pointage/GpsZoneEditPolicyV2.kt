package com.amaury.pointage

import java.util.Locale

/** Identité d'affichage d'un lieu. Un ID d'entreprise reste opaque et sensible à la casse. */
internal data class GpsPlaceScopeV2 private constructor(
    val companyId: String?,
    val companySlot: Int?,
    val addressKey: String
) {
    companion object {
        fun of(companyId: String?, companySlot: Int?, address: String): GpsPlaceScopeV2 {
            val id = companyId?.trim()?.takeIf { it.isNotEmpty() }
            return GpsPlaceScopeV2(id, if (id == null) companySlot else null,
                address.trim().lowercase(Locale.ROOT))
        }
    }
}

/** Le rôle d'un lieu ne constitue jamais une qualification de rémunération. */
internal enum class GpsZoneRoleV2(val token: String, val title: String) {
    WORK("POSTE", "Travail / pointage"),
    PARKING("PARKING", "Parking"),
    BREAK("PAUSE", "Pause — à confirmer"),
    OTHER("OTHER", "Autre / à confirmer");

    companion object {
        fun fromToken(raw: String?): GpsZoneRoleV2 = when (raw?.trim()?.uppercase(Locale.ROOT)) {
            "POSTE", "WORK", "WORKSITE", "WORKPLACE" -> WORK
            "PARKING" -> PARKING
            "PAUSE", "BREAK" -> BREAK
            else -> OTHER
        }
    }
}

internal data class GpsZoneDraftV2(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    val role: GpsZoneRoleV2,
    val contactName: String = "",
    val phone: String = "",
    val notifyOnArrival: Boolean = false
) {
    fun error(): String? = when {
        label.trim().isEmpty() -> "Indique un nom pour cette zone"
        !latitude.isFinite() || latitude !in -90.0..90.0 -> "Latitude invalide"
        !longitude.isFinite() || longitude !in -180.0..180.0 -> "Longitude invalide"
        !radius.isFinite() || radius !in 50.0..1000.0 -> "Rayon attendu : de 50 à 1 000 mètres"
        notifyOnArrival && phone.trim().isEmpty() -> "Ajoute un numéro pour prévenir à l'arrivée"
        else -> null
    }
}
