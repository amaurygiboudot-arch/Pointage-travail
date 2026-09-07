package com.amaury.pointage.v2.engine

/** Jour conventionnel pouvant porter une majoration simple sur toutes les minutes payées du jour. */
enum class WeekdayPremiumKindV2 { SATURDAY, SUNDAY }

data class WeekdayPremiumRuleV2(
    val kind: WeekdayPremiumKindV2,
    val multiplier: Double
) {
    init {
        require(multiplier >= 1.0) { "Multiplicateur de jour invalide" }
    }

    val percentage: Double get() = (multiplier - 1.0) * 100.0
}

data class ConventionWeekdayPremiumSnapshotV2(
    val idcc: String,
    val versionId: String,
    val sourceId: String,
    val effectiveFromEpochDay: Long,
    val effectiveToEpochDay: Long? = null,
    val rule: WeekdayPremiumRuleV2,
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

/** Historique séparé par IDCC et par jour pour éviter de mélanger samedi et dimanche. */
class ConventionWeekdayPremiumHistoryV2(
    snapshots: List<ConventionWeekdayPremiumSnapshotV2>
) {
    private data class Key(val idcc: String, val kind: WeekdayPremiumKindV2)

    private val versions = snapshots
        .groupBy { Key(normalizeIdcc(it.idcc), it.rule.kind) }
        .mapValues { (_, items) -> items.sortedByDescending { it.effectiveFromEpochDay } }

    init {
        versions.forEach { (key, items) ->
            items.groupBy { it.versionId }.forEach { (versionId, duplicates) ->
                require(duplicates.size == 1) {
                    "Version conventionnelle dupliquée : ${key.idcc}/${key.kind}/$versionId"
                }
            }
            val ascending = items.sortedBy { it.effectiveFromEpochDay }
            for (index in 1 until ascending.size) {
                val previous = ascending[index - 1]
                val current = ascending[index]
                val previousEnd = previous.effectiveToEpochDay
                require(previousEnd != null && previousEnd < current.effectiveFromEpochDay) {
                    "Versions de majoration qui se chevauchent pour ${key.idcc}/${key.kind}"
                }
            }
        }
    }

    fun applicable(
        idcc: String?,
        kind: WeekdayPremiumKindV2,
        epochDay: Long
    ): ConventionWeekdayPremiumSnapshotV2? {
        val normalized = normalizeIdcc(idcc ?: return null)
        if (normalized.isBlank()) return null
        return versions[Key(normalized, kind)]?.firstOrNull { it.appliesTo(epochDay) }
    }

    fun allVersions(idcc: String?, kind: WeekdayPremiumKindV2): List<ConventionWeekdayPremiumSnapshotV2> {
        val normalized = normalizeIdcc(idcc ?: return emptyList())
        if (normalized.isBlank()) return emptyList()
        return versions[Key(normalized, kind)].orEmpty()
    }

    companion object {
        private fun normalizeIdcc(value: String): String = value.trim().padStart(4, '0')
    }
}
