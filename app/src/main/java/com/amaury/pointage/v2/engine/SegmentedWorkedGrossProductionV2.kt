package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.SegmentedPayrollSessionSourceFactoryV2

data class SegmentedWorkedGrossProductionResultV2(
    val source: SegmentedPayrollSessionSourceV2,
    val variables: SegmentedWorkedVariableGrossSourceResultV2,
    val worked: SegmentedWorkedGrossAssemblyResultV2,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Point d'entrée interne store -> B21 -> B20.
 *
 * Il ne calcule ni net ni brut social final. Il orchestre uniquement des briques déjà propriétaires
 * de leurs règles : couverture des pointages, preuves hebdomadaires B21 et brut de travail B20.
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
            contracts = contracts,
            base = base,
            requestedTimeZoneId = requestedTimeZoneId,
            nowMs = nowMs,
            warning = BOUNDARY_WARNING
        )
        val source = SegmentedPayrollSessionSourceFactoryV2.fromStores(
            context = context,
            employerId = contracts.employerId,
            requiredStartEpochDay = bounds.first,
            requiredEndEpochDay = bounds.second,
            requestedTimeZoneId = requestedTimeZoneId,
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
        val worked = SegmentedWorkedGrossAssemblerV2.assemble(
            contracts = contracts,
            base = base,
            variables = variables
        )
        val warnings = (
            source.warnings +
                variables.warnings +
                worked.warnings
            ).distinct()
        return SegmentedWorkedGrossProductionResultV2(
            source = source,
            variables = variables,
            worked = worked.copy(warnings = warnings),
            reliable = worked.reliable,
            warnings = warnings
        )
    }

    internal fun requiredCoverageBounds(
        periodStartEpochDay: Long,
        periodEndEpochDay: Long
    ): Pair<Long, Long>? {
        if (periodEndEpochDay < periodStartEpochDay) return null
        return runCatching {
            val firstMonday = Math.subtractExact(
                periodStartEpochDay,
                Math.floorMod(periodStartEpochDay + 3L, 7L)
            )
            val lastMonday = Math.subtractExact(
                periodEndEpochDay,
                Math.floorMod(periodEndEpochDay + 3L, 7L)
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
