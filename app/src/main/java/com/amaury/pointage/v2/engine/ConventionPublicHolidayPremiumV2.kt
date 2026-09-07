package com.amaury.pointage.v2.engine

/**
 * Majoration simple et uniforme pour les jours fériés.
 *
 * Ce modèle est volontairement strict : il ne représente pas encore les exceptions par jour nommé
 * (par exemple une règle spécifique au 1er mai), les catégories de salariés ou les clauses de cumul.
 * Dès qu'une de ces conditions existe, aucun snapshot de ce type ne doit être enregistré.
 */
data class PublicHolidayPremiumRuleV2(
    val multiplier: Double
) {
    init {
        require(multiplier >= 1.0) { "Multiplicateur de jour férié invalide" }
    }

    val percentage: Double get() = (multiplier - 1.0) * 100.0
}

data class ConventionPublicHolidayPremiumSnapshotV2(
    val idcc: String,
    val versionId: String,
    val sourceId: String,
    val effectiveFromEpochDay: Long,
    val effectiveToEpochDay: Long? = null,
    val rule: PublicHolidayPremiumRuleV2,
    val checkedAtMs: Long,
    val note: String? = null
) {
    init {
        require(idcc.isNotBlank()) { "IDCC obligatoire" }
        require(versionId.isNotBlank()) { "Version obligatoire" }
        require(sourceId.isNotBlank()) { "Source officielle obligatoire" }
        require(effectiveToEpochDay == null || effectiveToEpochDay >= effectiveFromEpochDay) {
            "Période d'application invalide"
        }
    }

    fun appliesTo(epochDay: Long): Boolean =
        epochDay >= effectiveFromEpochDay &&
            (effectiveToEpochDay == null || epochDay <= effectiveToEpochDay)
}

/** Historique séparé des jours fériés : aucune réutilisation des règles samedi/dimanche. */
class ConventionPublicHolidayPremiumHistoryV2(
    snapshots: List<ConventionPublicHolidayPremiumSnapshotV2>
) {
    private val versions = snapshots
        .groupBy { normalizeIdcc(it.idcc) }
        .mapValues { (_, items) -> items.sortedByDescending { it.effectiveFromEpochDay } }

    init {
        versions.forEach { (idcc, items) ->
            items.groupBy { it.versionId }.forEach { (versionId, duplicates) ->
                require(duplicates.size == 1) {
                    "Version conventionnelle jour férié dupliquée : $idcc/$versionId"
                }
            }
            val ascending = items.sortedBy { it.effectiveFromEpochDay }
            for (index in 1 until ascending.size) {
                val previous = ascending[index - 1]
                val current = ascending[index]
                val previousEnd = previous.effectiveToEpochDay
                require(previousEnd != null && previousEnd < current.effectiveFromEpochDay) {
                    "Versions de majoration jour férié qui se chevauchent pour $idcc"
                }
            }
        }
    }

    fun applicable(idcc: String?, epochDay: Long): ConventionPublicHolidayPremiumSnapshotV2? {
        val normalized = normalizeIdcc(idcc ?: return null)
        if (normalized.isBlank()) return null
        return versions[normalized]?.firstOrNull { it.appliesTo(epochDay) }
    }

    fun allVersions(idcc: String?): List<ConventionPublicHolidayPremiumSnapshotV2> {
        val normalized = normalizeIdcc(idcc ?: return emptyList())
        if (normalized.isBlank()) return emptyList()
        return versions[normalized].orEmpty()
    }

    companion object {
        private fun normalizeIdcc(value: String): String = value.trim().padStart(4, '0')
    }
}
