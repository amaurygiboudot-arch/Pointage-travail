package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractV2

/**
 * Éditeur déterministe de la chronologie des contrats salariés confirmés.
 *
 * Une date d'effet confirmée est une information distincte de la date d'embauche. Aucune date
 * n'est déduite de l'heure courante, de la date d'entrée ou du mois consulté.
 *
 * Règles :
 * - une nouvelle version commence exactement au jour confirmé ;
 * - si elle coupe une version encore applicable, cette version est fermée la veille ;
 * - la nouvelle version se termine la veille de la prochaine version déjà connue ;
 * - un trou historique déjà prouvé avant la nouvelle date n'est jamais comblé artificiellement ;
 * - modifier une version au même jour conserve son identifiant et sa borne de fin existante ;
 * - les autres employeurs restent strictement inchangés.
 */
object EmploymentContractTimelineV2 {
    fun upsertEffectiveVersion(
        existing: List<EmploymentContractSnapshotV2>,
        contract: ContractV2,
        effectiveFromEpochDay: Long,
        sourceId: String,
        checkedAtMs: Long,
        note: String? = null
    ): List<EmploymentContractSnapshotV2>? {
        // Un historique déjà incohérent ne doit jamais être "réparé" implicitement par une saisie.
        if (runCatching { EmploymentContractHistoryV2(existing) }.isFailure) return null

        val employerId = contract.employerId.trim()
        val normalizedContract = contract.copy(
            id = contract.id.trim(),
            employerId = employerId
        )
        val normalizedSource = sourceId.trim()
        val normalizedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if (employerId.isBlank() || normalizedSource.isBlank() || checkedAtMs < 0L) return null

        val sameEmployer = existing
            .filter { it.contract.employerId.trim() == employerId }
            .sortedBy { it.effectiveFromEpochDay }
        val sameStart = sameEmployer.singleOrNull { it.effectiveFromEpochDay == effectiveFromEpochDay }
        val next = sameEmployer.firstOrNull { it.effectiveFromEpochDay > effectiveFromEpochDay }

        val candidateEnd = sameStart?.effectiveToEpochDay
            ?: next?.effectiveFromEpochDay?.let(::dayBefore)
            ?: return buildAndValidate(
                existing = existing,
                employerId = employerId,
                effectiveFromEpochDay = effectiveFromEpochDay,
                sameStart = sameStart,
                candidateEnd = null,
                contract = normalizedContract,
                sourceId = normalizedSource,
                checkedAtMs = checkedAtMs,
                note = normalizedNote
            )

        return buildAndValidate(
            existing = existing,
            employerId = employerId,
            effectiveFromEpochDay = effectiveFromEpochDay,
            sameStart = sameStart,
            candidateEnd = candidateEnd,
            contract = normalizedContract,
            sourceId = normalizedSource,
            checkedAtMs = checkedAtMs,
            note = normalizedNote
        )
    }

    private fun buildAndValidate(
        existing: List<EmploymentContractSnapshotV2>,
        employerId: String,
        effectiveFromEpochDay: Long,
        sameStart: EmploymentContractSnapshotV2?,
        candidateEnd: Long?,
        contract: ContractV2,
        sourceId: String,
        checkedAtMs: Long,
        note: String?
    ): List<EmploymentContractSnapshotV2>? {
        val previousEnd = dayBefore(effectiveFromEpochDay)
        val versionId = sameStart?.versionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: "effective-$effectiveFromEpochDay"

        val candidate = runCatching {
            EmploymentContractSnapshotV2(
                versionId = versionId,
                sourceId = sourceId,
                effectiveFromEpochDay = effectiveFromEpochDay,
                effectiveToEpochDay = candidateEnd,
                contract = contract,
                checkedAtMs = checkedAtMs,
                note = note
            )
        }.getOrNull() ?: return null

        val updated = existing.mapNotNull { snapshot ->
            if (snapshot.contract.employerId.trim() != employerId) return@mapNotNull snapshot
            if (snapshot.effectiveFromEpochDay == effectiveFromEpochDay) return@mapNotNull null

            if (snapshot.effectiveFromEpochDay < effectiveFromEpochDay &&
                (snapshot.effectiveToEpochDay == null || snapshot.effectiveToEpochDay >= effectiveFromEpochDay)
            ) {
                snapshot.copy(effectiveToEpochDay = previousEnd ?: return null)
            } else {
                snapshot
            }
        } + candidate

        if (runCatching { EmploymentContractHistoryV2(updated) }.isFailure) return null
        return updated.sortedWith(
            compareBy<EmploymentContractSnapshotV2> { it.contract.employerId.trim() }
                .thenBy { it.effectiveFromEpochDay }
                .thenBy { it.versionId.trim() }
        )
    }

    /** Retourne null uniquement lorsque la veille n'est pas représentable. */
    private fun dayBefore(epochDay: Long): Long? =
        if (epochDay == Long.MIN_VALUE) null else epochDay - 1L
}
