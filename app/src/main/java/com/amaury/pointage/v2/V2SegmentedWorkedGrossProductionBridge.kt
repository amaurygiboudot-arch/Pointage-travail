package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionRulePeriodResolutionV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2
import com.amaury.pointage.v2.engine.FrenchPublicHolidayCalendarV2
import com.amaury.pointage.v2.engine.SegmentedPayrollPremiumEvidenceBridgeV2
import com.amaury.pointage.v2.engine.SegmentedPayrollPremiumEvidenceV2
import com.amaury.pointage.v2.engine.SegmentedWorkedGrossAssemblyResultV2
import com.amaury.pointage.v2.engine.SegmentedWorkedGrossProductionV2
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
    ): SegmentedWorkedGrossAssemblyResultV2 {
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
        if (!premiumContext.reliable) return blocked(premiumContext.warnings)
        return calculate(
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
    ): SegmentedWorkedGrossAssemblyResultV2 {
        val period = runCatching { YearMonth.of(year, monthZeroBased + 1) }.getOrNull()
            ?: return blocked("Brut segmenté : période mensuelle invalide.")
        val bounds = coverageBounds(
            contracts.periodStartEpochDay,
            contracts.periodEndEpochDay
        ) ?: return blocked("Brut segmenté : bornes de couverture hebdomadaire invalides.")

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
        return SegmentedWorkedGrossProductionV2.calculate(
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

    private fun blocked(warnings: List<String>) = SegmentedWorkedGrossAssemblyResultV2(
        baseGross = null,
        variableGross = null,
        workedGross = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
