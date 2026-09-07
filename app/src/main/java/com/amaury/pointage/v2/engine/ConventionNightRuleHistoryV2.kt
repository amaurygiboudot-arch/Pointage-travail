package com.amaury.pointage.v2.engine

/**
 * Instantané indépendant pour une règle conventionnelle de nuit.
 *
 * Il est séparé du snapshot des heures supplémentaires afin que plusieurs familles KALI puissent
 * avoir leurs propres articles et dates d'effet sans se chevaucher artificiellement.
 */
data class ConventionNightRuleSnapshotV2(
    val idcc: String,
    val versionId: String,
    val sourceId: String,
    val effectiveFromEpochDay: Long,
    val effectiveToEpochDay: Long? = null,
    val rule: NightPremiumRuleV2,
    val checkedAtMs: Long,
    val note: String? = null
) {
    init {
        require(idcc.isNotBlank()) { "IDCC obligatoire" }
        require(versionId.isNotBlank()) { "Version nuit obligatoire" }
        require(sourceId.isNotBlank()) { "Source nuit obligatoire" }
        require(effectiveToEpochDay == null || effectiveToEpochDay >= effectiveFromEpochDay) {
            "Période d'application nuit invalide"
        }
    }

    fun appliesTo(epochDay: Long): Boolean =
        epochDay >= effectiveFromEpochDay &&
            (effectiveToEpochDay == null || epochDay <= effectiveToEpochDay)
}

/** Historique déterministe des règles de nuit confirmées, par IDCC. */
class ConventionNightRuleHistoryV2(
    snapshots: List<ConventionNightRuleSnapshotV2>
) {
    private val versions = snapshots
        .groupBy { normalizeIdcc(it.idcc) }
        .mapValues { (_, items) -> items.sortedByDescending { it.effectiveFromEpochDay } }

    init {
        versions.values.flatten()
            .groupBy { normalizeIdcc(it.idcc) to it.versionId }
            .forEach { (key, items) ->
                require(items.size == 1) { "Version nuit dupliquée : ${key.first}/${key.second}" }
            }

        versions.forEach { (idcc, items) ->
            val ascending = items.sortedBy { it.effectiveFromEpochDay }
            for (index in 1 until ascending.size) {
                val previous = ascending[index - 1]
                val current = ascending[index]
                val previousEnd = previous.effectiveToEpochDay
                require(previousEnd != null && previousEnd < current.effectiveFromEpochDay) {
                    "Versions nuit qui se chevauchent pour IDCC $idcc"
                }
            }
        }
    }

    fun applicable(idcc: String?, epochDay: Long): ConventionNightRuleSnapshotV2? {
        val normalized = normalizeIdcc(idcc ?: return null)
        if (normalized.isBlank()) return null
        return versions[normalized]?.firstOrNull { it.appliesTo(epochDay) }
    }

    fun allVersions(idcc: String?): List<ConventionNightRuleSnapshotV2> {
        val normalized = normalizeIdcc(idcc ?: return emptyList())
        if (normalized.isBlank()) return emptyList()
        return versions[normalized].orEmpty()
    }

    companion object {
        fun empty(): ConventionNightRuleHistoryV2 = ConventionNightRuleHistoryV2(emptyList())

        private fun normalizeIdcc(value: String): String = value.trim().padStart(4, '0')
    }
}
