package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractV2

/**
 * Instantané daté d'un contrat salarié confirmé.
 *
 * Le contrat courant ne doit jamais être utilisé comme fallback pour une ancienne période.
 * Chaque changement de type, durée, taux ou forfait doit donc produire une nouvelle version
 * explicitement datée avant de pouvoir alimenter un calcul historique.
 */
data class EmploymentContractSnapshotV2(
    val versionId: String,
    val sourceId: String,
    val effectiveFromEpochDay: Long,
    val effectiveToEpochDay: Long? = null,
    val contract: ContractV2,
    val checkedAtMs: Long,
    val note: String? = null
) {
    init {
        require(versionId.isNotBlank()) { "Version de contrat obligatoire" }
        require(sourceId.isNotBlank()) { "Source du contrat obligatoire" }
        require(contract.id.isNotBlank()) { "Identifiant de contrat obligatoire" }
        require(contract.employerId.isNotBlank()) { "Employeur obligatoire" }
        require(checkedAtMs >= 0L) { "Date de vérification invalide" }
        require(effectiveToEpochDay == null || effectiveToEpochDay >= effectiveFromEpochDay) {
            "Période de contrat invalide"
        }
    }

    fun appliesTo(epochDay: Long): Boolean =
        epochDay >= effectiveFromEpochDay &&
            (effectiveToEpochDay == null || epochDay <= effectiveToEpochDay)
}

data class EmploymentContractCoverageSegmentV2(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val snapshot: EmploymentContractSnapshotV2
)

data class EmploymentContractCoverageV2(
    val employerId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val segments: List<EmploymentContractCoverageSegmentV2>,
    val fullyCovered: Boolean
) {
    val singleSnapshotForWholePeriod: EmploymentContractSnapshotV2?
        get() = segments.singleOrNull()?.takeIf {
            fullyCovered &&
                it.startEpochDay == periodStartEpochDay &&
                it.endEpochDay == periodEndEpochDay
        }?.snapshot

    val requiresMultipleContractVersions: Boolean
        get() = fullyCovered && segments.size > 1
}

/**
 * Historique déterministe des contrats confirmés.
 *
 * Les versions d'un même employeur ne peuvent ni se chevaucher ni partager le même versionId.
 * Une période sans snapshot reste explicitement non couverte : aucune version plus récente ou
 * plus ancienne n'est étendue artificiellement.
 */
class EmploymentContractHistoryV2(
    snapshots: List<EmploymentContractSnapshotV2>
) {
    private val versions = snapshots
        .groupBy { normalizeEmployerId(it.contract.employerId) }
        .mapValues { (_, items) -> items.sortedByDescending { it.effectiveFromEpochDay } }

    init {
        versions.forEach { (employerId, items) ->
            require(employerId.isNotBlank()) { "Employeur vide dans l'historique de contrats" }
            items.groupBy { it.versionId.trim() }.forEach { (versionId, duplicates) ->
                require(versionId.isNotBlank() && duplicates.size == 1) {
                    "Version de contrat dupliquée : $employerId/$versionId"
                }
            }

            val ascending = items.sortedBy { it.effectiveFromEpochDay }
            for (index in 1 until ascending.size) {
                val previous = ascending[index - 1]
                val current = ascending[index]
                val previousEnd = previous.effectiveToEpochDay
                require(previousEnd != null && previousEnd < current.effectiveFromEpochDay) {
                    "Versions de contrat qui se chevauchent pour $employerId"
                }
            }
        }
    }

    fun applicable(employerId: String?, epochDay: Long): EmploymentContractSnapshotV2? {
        val normalized = normalizeEmployerId(employerId ?: return null)
        if (normalized.isBlank()) return null
        return versions[normalized]?.firstOrNull { it.appliesTo(epochDay) }
    }

    fun allVersions(employerId: String?): List<EmploymentContractSnapshotV2> {
        val normalized = normalizeEmployerId(employerId ?: return emptyList())
        if (normalized.isBlank()) return emptyList()
        return versions[normalized].orEmpty()
    }

    fun coverage(
        employerId: String,
        periodStartEpochDay: Long,
        periodEndEpochDay: Long
    ): EmploymentContractCoverageV2 {
        val normalized = normalizeEmployerId(employerId)
        require(normalized.isNotBlank()) { "Employeur obligatoire" }
        require(periodEndEpochDay >= periodStartEpochDay) { "Période demandée invalide" }

        val segments = allVersions(normalized)
            .mapNotNull { snapshot ->
                val start = maxOf(snapshot.effectiveFromEpochDay, periodStartEpochDay)
                val end = minOf(snapshot.effectiveToEpochDay ?: periodEndEpochDay, periodEndEpochDay)
                if (start > end) null else EmploymentContractCoverageSegmentV2(start, end, snapshot)
            }
            .sortedWith(compareBy<EmploymentContractCoverageSegmentV2> { it.startEpochDay }
                .thenBy { it.endEpochDay })

        var cursor = periodStartEpochDay
        var fullyCovered = segments.isNotEmpty()
        if (fullyCovered) {
            for (segment in segments) {
                if (segment.startEpochDay != cursor) {
                    fullyCovered = false
                    break
                }
                if (segment.endEpochDay == periodEndEpochDay) {
                    cursor = periodEndEpochDay
                    break
                }
                if (segment.endEpochDay >= periodEndEpochDay || segment.endEpochDay == Long.MAX_VALUE) {
                    fullyCovered = false
                    break
                }
                cursor = segment.endEpochDay + 1L
            }
            if (segments.lastOrNull()?.endEpochDay != periodEndEpochDay) fullyCovered = false
        }

        return EmploymentContractCoverageV2(
            employerId = normalized,
            periodStartEpochDay = periodStartEpochDay,
            periodEndEpochDay = periodEndEpochDay,
            segments = segments,
            fullyCovered = fullyCovered
        )
    }

    companion object {
        fun empty(): EmploymentContractHistoryV2 = EmploymentContractHistoryV2(emptyList())

        private fun normalizeEmployerId(value: String): String = value.trim()
    }
}
