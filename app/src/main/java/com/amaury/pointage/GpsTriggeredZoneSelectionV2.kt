package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsPointTypeV2

/**
 * Sélection pure d'une zone GPS lorsqu'Android remonte plusieurs geofences simultanément.
 *
 * HoraTrack ne doit jamais choisir arbitrairement la première zone si les zones déclenchées
 * décrivent des contextes métier différents. Une sélection automatique n'est autorisée que si
 * toutes les zones sont sémantiquement équivalentes pour l'employeur, le type de point et le lieu.
 */
internal object GpsTriggeredZoneSelectionV2 {
    data class Candidate(
        val zoneId: String,
        val employerKey: String,
        val pointType: GpsPointTypeV2,
        val placeKey: String
    )

    sealed class Result {
        data class Selected(val zoneId: String) : Result()
        data class Blocked(val reason: String) : Result()
    }

    fun select(candidates: List<Candidate>): Result {
        val distinct = candidates
            .filter { it.zoneId.isNotBlank() }
            .distinctBy { it.zoneId }
        if (distinct.isEmpty()) {
            return Result.Blocked("Aucune zone GPS valide à sélectionner")
        }

        val signatures = distinct
            .map { Signature(it.employerKey, it.pointType, it.placeKey) }
            .distinct()

        if (signatures.size != 1) {
            return Result.Blocked(
                "Plusieurs zones GPS incompatibles ont été déclenchées simultanément"
            )
        }

        return Result.Selected(distinct.minBy { it.zoneId }.zoneId)
    }

    internal fun placeKey(zone: StoredGpsZone): String {
        val label = zone.label?.trim()?.lowercase()
        val address = zone.address?.trim()?.lowercase()
        return when {
            !label.isNullOrBlank() && !address.isNullOrBlank() -> "label:$label|address:$address"
            !label.isNullOrBlank() -> "label:$label"
            !address.isNullOrBlank() -> "address:$address"
            else -> "zone:" + zone.id
        }
    }

    private data class Signature(
        val employerKey: String,
        val pointType: GpsPointTypeV2,
        val placeKey: String
    )
}
