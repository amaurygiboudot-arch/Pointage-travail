package com.amaury.pointage.v2.engine

/**
 * Instantané immuable d'un jeu de règles conventionnelles.
 *
 * Chaque version est reliée à une convention (IDCC), une source officielle et une période
 * d'application. Une ancienne période de paie ne doit jamais utiliser automatiquement une règle
 * plus récente.
 */
data class ConventionRuleSnapshotV2(
    val idcc: String,
    val versionId: String,
    val sourceId: String,
    val effectiveFromEpochDay: Long,
    val effectiveToEpochDay: Long? = null,
    val rules: PayrollRulesV2,
    val checkedAtMs: Long,
    val note: String? = null
) {
    init {
        require(idcc.isNotBlank()) { "IDCC obligatoire" }
        require(versionId.isNotBlank()) { "Version de règle obligatoire" }
        require(sourceId.isNotBlank()) { "Source officielle obligatoire" }
        require(effectiveToEpochDay == null || effectiveToEpochDay >= effectiveFromEpochDay) {
            "Période d'application invalide"
        }
    }

    fun appliesTo(epochDay: Long): Boolean =
        epochDay >= effectiveFromEpochDay &&
            (effectiveToEpochDay == null || epochDay <= effectiveToEpochDay)
}

/**
 * Registre déterministe des versions conventionnelles connues.
 *
 * Le registre ne fait aucun fallback vers la règle actuelle : si aucune version confirmée ne couvre
 * la période demandée, le résultat est null et l'appelant doit afficher "À confirmer" au lieu
 * d'inventer une règle.
 */
class ConventionRuleHistoryV2(
    snapshots: List<ConventionRuleSnapshotV2>
) {
    private val versions = snapshots
        .groupBy { normalizeIdcc(it.idcc) }
        .mapValues { (_, items) -> items.sortedByDescending { it.effectiveFromEpochDay } }

    init {
        versions.values.flatten().groupBy { normalizeIdcc(it.idcc) to it.versionId }.forEach { (key, items) ->
            require(items.size == 1) { "Version conventionnelle dupliquée : ${key.first}/${key.second}" }
        }

        versions.forEach { (idcc, items) ->
            val ascending = items.sortedBy { it.effectiveFromEpochDay }
            for (index in 1 until ascending.size) {
                val previous = ascending[index - 1]
                val current = ascending[index]
                val previousEnd = previous.effectiveToEpochDay
                require(previousEnd != null && previousEnd < current.effectiveFromEpochDay) {
                    "Versions conventionnelles qui se chevauchent pour IDCC $idcc"
                }
            }
        }
    }

    fun applicable(idcc: String?, epochDay: Long): ConventionRuleSnapshotV2? {
        val normalized = normalizeIdcc(idcc ?: return null)
        if (normalized.isBlank()) return null
        return versions[normalized]?.firstOrNull { it.appliesTo(epochDay) }
    }

    fun allVersions(idcc: String?): List<ConventionRuleSnapshotV2> {
        val normalized = normalizeIdcc(idcc ?: return emptyList())
        if (normalized.isBlank()) return emptyList()
        return versions[normalized].orEmpty()
    }

    companion object {
        fun empty(): ConventionRuleHistoryV2 = ConventionRuleHistoryV2(emptyList())

        private fun normalizeIdcc(value: String): String = value.trim().padStart(4, '0')
    }
}
