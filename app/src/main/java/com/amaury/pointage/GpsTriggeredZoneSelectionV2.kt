package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsPointTypeV2
import com.amaury.pointage.v2.engine.GpsEventV2
import com.amaury.pointage.v2.engine.GpsTransitionV2
import java.util.Locale

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

    data class ExitCandidate(
        val zoneId: String,
        val pointType: GpsPointTypeV2,
        val placeKey: String
    )

    sealed class Result {
        data class Selected(val zoneId: String) : Result()
        data class Blocked(val reason: String) : Result()
    }

    fun select(candidates: List<Candidate>, preferredZoneId: String? = null): Result {
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

        val preferred = preferredZoneId?.trim()?.takeIf(String::isNotBlank)
        return Result.Selected(
            distinct.firstOrNull { it.zoneId == preferred }?.zoneId
                ?: distinct.minBy { it.zoneId }.zoneId
        )
    }

    /**
     * Une sortie groupée ne dépend ni du registre employeur ni de son état de migration.
     * Lorsque plusieurs geofences sortent ensemble, le lieu porté par la session V2
     * effectivement ouverte est prioritaire. Sans correspondance, seules des zones de lieu et
     * de type équivalents peuvent encore être départagées de façon déterministe.
     */
    fun selectForExit(
        candidates: List<ExitCandidate>,
        openSessionPlaceId: String?,
        runtimeReliable: Boolean = true,
        openSessionAvailable: Boolean = true
    ): Result {
        if (!runtimeReliable) {
            return Result.Blocked("Session V2 courante non fiable")
        }
        if (!openSessionAvailable) {
            return Result.Blocked("Aucune session V2 ouverte à quitter")
        }
        val distinct = candidates
            .filter { it.zoneId.isNotBlank() }
            .distinctBy { it.zoneId }
            .sortedBy { it.zoneId }
        if (distinct.isEmpty()) {
            return Result.Blocked("Aucune zone GPS valide à quitter")
        }

        val sessionPlaceId = openSessionPlaceId?.trim()?.takeIf(String::isNotBlank)
        distinct.firstOrNull { it.zoneId == sessionPlaceId }?.let {
            return Result.Selected(it.zoneId)
        }

        if (distinct.size == 1) return Result.Selected(distinct.single().zoneId)

        val signatures = distinct
            .map { ExitSignature(it.pointType, it.placeKey) }
            .distinct()
        return if (signatures.size == 1) {
            Result.Selected(distinct.first().zoneId)
        } else {
            Result.Blocked("Plusieurs zones GPS incompatibles ont été quittées simultanément")
        }
    }

    /**
     * KeepCurrent est comparable à une association explicite uniquement lorsque l'employeur
     * actif a été relu et confirmé dans un registre fiable.
     */
    fun employerKey(
        resolution: GpsZoneEmployerResolutionV2,
        confirmedActiveCompanyId: String?,
        companiesReliable: Boolean = true
    ): String? = when (resolution) {
        GpsZoneEmployerResolutionV2.KeepCurrent -> if (!companiesReliable) {
            null
        } else {
            confirmedActiveCompanyId
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { "company:$it" }
                ?: "keep-current"
        }
        is GpsZoneEmployerResolutionV2.UseCompany -> "company:${resolution.companyId}"
        is GpsZoneEmployerResolutionV2.Block -> null
    }

    fun event(
        zoneId: String,
        pointType: GpsPointTypeV2,
        transition: GpsTransitionV2,
        atMs: Long
    ): GpsEventV2 {
        require(zoneId.isNotBlank())
        require(atMs > 0L)
        val verb = if (transition == GpsTransitionV2.ENTER) "enter" else "exit"
        return GpsEventV2(
            id = "gps-$verb-$zoneId-$atMs",
            atMs = atMs,
            placeId = zoneId,
            pointType = pointType,
            transition = transition
        )
    }

    internal fun placeKey(zone: StoredGpsZone): String {
        val address = zone.address?.trim()?.lowercase(Locale.ROOT)
        val label = zone.label?.trim()?.lowercase(Locale.ROOT)
        return when {
            !address.isNullOrBlank() -> "address:$address"
            !label.isNullOrBlank() -> "label:$label"
            else -> "zone:" + zone.id
        }
    }

    internal fun pointType(zone: StoredGpsZone): GpsPointTypeV2 {
        val explicit = zone.pointTypeToken?.trim()?.takeIf { it.isNotBlank() }
            ?: return GpsPointTypeV2.POSTE
        val raw = explicit.uppercase(Locale.ROOT)
        return when {
            raw.contains("PAUSE") || raw.contains("BREAK") -> GpsPointTypeV2.PAUSE
            raw.contains("PARK") -> GpsPointTypeV2.PARKING
            raw.contains("OTHER") || raw.contains("AUTRE") -> GpsPointTypeV2.OTHER
            raw.contains("POSTE") || raw.contains("WORKPLACE") || raw.contains("WORK") -> GpsPointTypeV2.POSTE
            else -> GpsPointTypeV2.OTHER
        }
    }

    private data class Signature(
        val employerKey: String,
        val pointType: GpsPointTypeV2,
        val placeKey: String
    )

    private data class ExitSignature(
        val pointType: GpsPointTypeV2,
        val placeKey: String
    )
}
