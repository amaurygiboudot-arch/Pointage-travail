package com.amaury.pointage.v2.engine

import android.content.Context

data class SegmentedWorkedGrossProductionResultV2(
    val source: SegmentedPayrollSessionSourceV2,
    val variables: SegmentedWorkedVariableGrossSourceResultV2,
    val worked: SegmentedWorkedGrossAssemblyResultV2,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Point d'entrée interne stores V2 -> B21 -> B20.
 *
 * Aucun calcul monétaire parallèle : la couverture vient du registre V2, B21 reste propriétaire
 * des variables et B20 reste propriétaire de l'assemblage base + variables.
 */
object SegmentedWorkedGrossProductionV2 {
    const val BOUNDARY_WARNING =
        "Brut segmenté : bornes de période non représentables pour une couverture hebdomadaire complète."

    fun calculateFromStores(
        context: Context,
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        requestedTimeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedWorkedGrossProductionResultV2 {
        val bounds = requiredCoverageBounds(
            contracts.periodStartEpochDay,
            contracts.periodEndEpochDay
        ) ?: return blocked(
            contracts, base, requestedTimeZoneId, nowMs, BOUNDARY_WARNING
        )

        val source = V2SegmentedPayrollSessionSourceBridgeV2.source(
            context = context,
            employerId = contracts.employerId,
            startEpochDay = bounds.first,
            endEpochDay = bounds.second,
            timeZoneId = requestedTimeZoneId,
            nowMs = nowMs
        )
        return calculateFromSource(
            contracts = contracts,
            rules = rules,
            base = base,
            source = source,
            premiums = premiums,
            nowMs = nowMs
        )
    }

    fun calculateFromSource(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        source: SegmentedPayrollSessionSourceV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long
    ): SegmentedWorkedGrossProductionResultV2 {
        val variables = SegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(
            contracts = contracts,
            rules = rules,
            source = source,
            premiums = premiums,
            nowMs = nowMs
        )
        val worked = SegmentedWorkedGrossAssemblerV2.assembleFromSource(
            contracts = contracts,
            base = base,
            source = variables
        )
        val warnings = (source.warnings + variables.warnings + worked.warnings).distinct()
        val normalizedWorked = worked.copy(warnings = warnings)
        return SegmentedWorkedGrossProductionResultV2(
            source = source,
            variables = variables,
            worked = normalizedWorked,
            reliable = normalizedWorked.reliable,
            warnings = warnings
        )
    }

    internal fun requiredCoverageBounds(
        periodStartEpochDay: Long,
        periodEndEpochDay: Long
    ): Pair<Long, Long>? {
        if (periodEndEpochDay < periodStartEpochDay) return null
        return runCatching {
            val startShifted = Math.addExact(periodStartEpochDay, 3L)
            val endShifted = Math.addExact(periodEndEpochDay, 3L)
            val firstMonday = Math.subtractExact(
                periodStartEpochDay,
                Math.floorMod(startShifted, 7L)
            )
            val lastMonday = Math.subtractExact(
                periodEndEpochDay,
                Math.floorMod(endShifted, 7L)
            )
            firstMonday to Math.addExact(lastMonday, 6L)
        }.getOrNull()
    }

    private fun blocked(
        contracts: EmploymentContractPeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        requestedTimeZoneId: String,
        nowMs: Long,
        warning: String
    ): SegmentedWorkedGrossProductionResultV2 {
        val warnings = (contracts.warnings + base.warnings + warning).distinct()
        val source = SegmentedPayrollSessionSourceV2(
            employerId = contracts.employerId,
            sessions = emptyList(),
            sourceId = "",
            reliable = false,
            exhaustive = false,
            coveredStartEpochDay = 0L,
            coveredEndEpochDay = -1L,
            checkedAtMs = nowMs,
            timeZoneId = requestedTimeZoneId,
            warnings = warnings
        )
        val variables = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = emptyList(),
            reliable = false,
            warnings = warnings
        )
        val worked = SegmentedWorkedGrossAssemblyResultV2(
            baseGross = null,
            variableGross = null,
            workedGross = null,
            reliable = false,
            warnings = warnings
        )
        return SegmentedWorkedGrossProductionResultV2(
            source = source,
            variables = variables,
            worked = worked,
            reliable = false,
            warnings = warnings
        )
    }
}
