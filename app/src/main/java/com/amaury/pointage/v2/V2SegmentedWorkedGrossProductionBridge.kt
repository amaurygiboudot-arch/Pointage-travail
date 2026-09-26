package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionRulePeriodResolutionV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2
import com.amaury.pointage.v2.engine.FrenchPublicHolidayCalendarV2
import com.amaury.pointage.v2.engine.SegmentedPayrollPremiumEvidenceBridgeV2
import com.amaury.pointage.v2.engine.SegmentedPayrollPremiumEvidenceV2
import com.amaury.pointage.v2.engine.SegmentedWorkedGrossAssemblyResultV2
import com.amaury.pointage.v2.engine.SegmentedWorkedGrossProductionResultV2
import com.amaury.pointage.v2.engine.SegmentedWorkedGrossProductionV2
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionEvidenceResultV2
import com.amaury.pointage.v2.engine.SegmentedWorkedVariableGrossSourceResultV2
import com.amaury.pointage.v2.engine.SegmentedMonthlyBaseResultV2
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Pont production entre les stores confirmés et la chaîne B21 -> B20.
 *
 * Les contextes juridiques de primes restent explicitement fournis par la couche d'arbitrage :
 * ce bridge ne transforme jamais l'absence d'une règle en preuve d'absence.
 */
object V2SegmentedWorkedGrossProductionBridge {
    fun calculateFromStores(
        context: Context,
        companyId: String,
        companyAddress: String,
        year: Int,
        monthZeroBased: Int,
        timeZoneId: String,
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedWorkedGrossAssemblyResultV2 =
        calculateDetailedFromStores(
            context = context,
            companyId = companyId,
            companyAddress = companyAddress,
            year = year,
            monthZeroBased = monthZeroBased,
            timeZoneId = timeZoneId,
            contracts = contracts,
            rules = rules,
            nowMs = nowMs
        ).assembly

    fun calculateDetailedFromStores(
        context: Context,
        companyId: String,
        companyAddress: String,
        year: Int,
        monthZeroBased: Int,
        timeZoneId: String,
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedWorkedGrossProductionResultV2 {
        val night = V2ConventionNightRuleStore.readConfirmed(context)
        val premiumContext = SegmentedPayrollPremiumEvidenceBridgeV2.build(
            contracts = contracts,
            rules = rules,
            nightSnapshots = night.snapshots,
            nightSourceReliable = night.reliable,
            nightWarnings = night.warnings,
            holidayScope = FrenchPublicHolidayCalendarV2.scopeForAddress(companyAddress),
            nowMs = nowMs
        )
        if (!premiumContext.reliable) return blockedDetailed(premiumContext.warnings)
        return calculateDetailed(
            context = context,
            companyId = companyId,
            year = year,
            monthZeroBased = monthZeroBased,
            timeZoneId = timeZoneId,
            contracts = contracts,
            rules = rules,
            premiums = premiumContext.evidence,
            nowMs = nowMs
        )
    }

    fun calculate(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int,
        timeZoneId: String,
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedWorkedGrossAssemblyResultV2 =
        calculateDetailed(
            context = context,
            companyId = companyId,
            year = year,
            monthZeroBased = monthZeroBased,
            timeZoneId = timeZoneId,
            contracts = contracts,
            rules = rules,
            premiums = premiums,
            nowMs = nowMs
        ).assembly

    fun calculateDetailed(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int,
        timeZoneId: String,
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedWorkedGrossProductionResultV2 {
        val period = runCatching { YearMonth.of(year, monthZeroBased + 1) }.getOrNull()
            ?: return blockedDetailed("Brut segmenté : période mensuelle invalide.")
        val bounds = coverageBounds(
            contracts.periodStartEpochDay,
            contracts.periodEndEpochDay
        ) ?: return blockedDetailed("Brut segmenté : bornes de couverture hebdomadaire invalides.")

        val proration = V2SegmentedProrationStore.resolve(
            context = context,
            companyId = companyId,
            period = period
        )
        val source = V2PayrollCoverageStore.source(
            context = context,
            employerId = companyId,
            coveredStartEpochDay = bounds.first,
            coveredEndEpochDay = bounds.second,
            timeZoneId = timeZoneId,
            nowMs = nowMs
        )
        return SegmentedWorkedGrossProductionV2.calculateDetailed(
            contracts = contracts,
            rules = rules,
            prorationSource = proration,
            source = source,
            premiums = premiums,
            nowMs = nowMs
        )
    }

    internal fun coverageBounds(
        periodStartEpochDay: Long,
        periodEndEpochDay: Long
    ): Pair<Long, Long>? {
        if (periodEndEpochDay < periodStartEpochDay) return null
        return runCatching {
            val start = LocalDate.ofEpochDay(periodStartEpochDay)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val end = LocalDate.ofEpochDay(periodEndEpochDay)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            start.toEpochDay() to end.toEpochDay()
        }.getOrNull()
    }

    private fun blocked(warning: String) = blocked(listOf(warning))

    private fun blocked(warnings: List<String>) = blockedDetailed(warnings).assembly

    internal fun blockedDetailed(warning: String) = blockedDetailed(listOf(warning))

    internal fun blockedDetailed(warnings: List<String>): SegmentedWorkedGrossProductionResultV2 {
        val uniqueWarnings = warnings.distinct()
        val evidence = SegmentedPayrollSessionEvidenceResultV2(
            slices = emptyList(),
            reliable = false,
            warnings = uniqueWarnings,
            sourceId = "v2-segmented-production-bridge-blocked",
            contributingSessionIds = emptyList()
        )
        val variables = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = emptyList(),
            reliable = false,
            warnings = uniqueWarnings
        )
        val base = SegmentedMonthlyBaseResultV2(
            pieces = emptyList(),
            baseGross = null,
            reliable = false,
            warnings = uniqueWarnings
        )
        val assembly = SegmentedWorkedGrossAssemblyResultV2(
            baseGross = null,
            variableGross = null,
            workedGross = null,
            reliable = false,
            warnings = uniqueWarnings
        )
        return SegmentedWorkedGrossProductionResultV2(
            evidence = evidence,
            variables = variables,
            base = base,
            assembly = assembly
        )
    }
}
