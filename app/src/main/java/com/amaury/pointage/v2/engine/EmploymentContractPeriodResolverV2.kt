package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractV2

/**
 * Résolution fail-closed du contrat applicable à une période de paie complète.
 *
 * Le résolveur ne choisit jamais arbitrairement une version :
 * - stockage amont non fiable => aucun contrat ni segment calculable ;
 * - trou dans la couverture => aucun contrat ni segment calculable ;
 * - plusieurs versions pendant la période => aucun contrat unique, mais les segments datés
 *   confirmés restent exposés pour un calcul segmenté ;
 * - une seule version couvrant chaque jour => contrat unique utilisable.
 */
data class EmploymentContractPeriodResolutionV2(
    val employerId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val sourceReliable: Boolean,
    val coverage: EmploymentContractCoverageV2?,
    val contract: ContractV2?,
    val warnings: List<String>
) {
    val readyForSingleContractCalculation: Boolean
        get() = sourceReliable && contract != null

    val calculationSegments: List<EmploymentContractCoverageSegmentV2>
        get() = coverage?.segments.orEmpty().takeIf {
            sourceReliable && coverage?.fullyCovered == true && it.isNotEmpty()
        }.orEmpty()

    val readyForSegmentedCalculation: Boolean
        get() = calculationSegments.isNotEmpty()

    /** Vrai uniquement si les versions multiples empêchent encore un contrat calculable. */
    val requiresMultipleContractVersions: Boolean
        get() = contract == null && coverage?.requiresMultipleContractVersions == true
}

object EmploymentContractPeriodResolverV2 {
    const val UNRELIABLE_WARNING =
        "Contrat de paie : historique contractuel non fiable ; aucun contrat n'est utilisé pour cette période."
    const val INCOMPLETE_WARNING =
        "Contrat de paie : la période n'est pas entièrement couverte par un contrat daté confirmé ; aucun fallback n'est utilisé."
    const val MULTIPLE_WARNING =
        "Contrat de paie : plusieurs versions contractuelles couvrent cette période ; le contrat unique est indisponible et les segments datés confirmés doivent être calculés séparément."

    fun resolve(
        employerId: String,
        periodStartEpochDay: Long,
        periodEndEpochDay: Long,
        sourceReliable: Boolean,
        snapshots: List<EmploymentContractSnapshotV2>
    ): EmploymentContractPeriodResolutionV2 {
        val normalizedEmployerId = employerId.trim()
        require(normalizedEmployerId.isNotBlank()) { "Employeur obligatoire" }
        require(periodEndEpochDay >= periodStartEpochDay) { "Période invalide" }

        if (!sourceReliable) {
            return EmploymentContractPeriodResolutionV2(
                employerId = normalizedEmployerId,
                periodStartEpochDay = periodStartEpochDay,
                periodEndEpochDay = periodEndEpochDay,
                sourceReliable = false,
                coverage = null,
                contract = null,
                warnings = listOf(UNRELIABLE_WARNING)
            )
        }

        val history = runCatching { EmploymentContractHistoryV2(snapshots) }.getOrNull()
            ?: return EmploymentContractPeriodResolutionV2(
                employerId = normalizedEmployerId,
                periodStartEpochDay = periodStartEpochDay,
                periodEndEpochDay = periodEndEpochDay,
                sourceReliable = false,
                coverage = null,
                contract = null,
                warnings = listOf(UNRELIABLE_WARNING)
            )

        val coverage = history.coverage(
            employerId = normalizedEmployerId,
            periodStartEpochDay = periodStartEpochDay,
            periodEndEpochDay = periodEndEpochDay
        )

        if (!coverage.fullyCovered) {
            return EmploymentContractPeriodResolutionV2(
                employerId = normalizedEmployerId,
                periodStartEpochDay = periodStartEpochDay,
                periodEndEpochDay = periodEndEpochDay,
                sourceReliable = true,
                coverage = coverage,
                contract = null,
                warnings = listOf(INCOMPLETE_WARNING)
            )
        }

        if (coverage.requiresMultipleContractVersions) {
            return EmploymentContractPeriodResolutionV2(
                employerId = normalizedEmployerId,
                periodStartEpochDay = periodStartEpochDay,
                periodEndEpochDay = periodEndEpochDay,
                sourceReliable = true,
                coverage = coverage,
                contract = null,
                warnings = listOf(MULTIPLE_WARNING)
            )
        }

        val contract = coverage.singleSnapshotForWholePeriod?.contract
        return EmploymentContractPeriodResolutionV2(
            employerId = normalizedEmployerId,
            periodStartEpochDay = periodStartEpochDay,
            periodEndEpochDay = periodEndEpochDay,
            sourceReliable = true,
            coverage = coverage,
            contract = contract,
            warnings = if (contract == null) listOf(INCOMPLETE_WARNING) else emptyList()
        )
    }
}
