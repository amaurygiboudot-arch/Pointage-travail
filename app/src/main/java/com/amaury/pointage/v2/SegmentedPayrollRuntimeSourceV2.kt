package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionRulePeriodResolutionV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2
import com.amaury.pointage.v2.engine.PayrollCalculationTimelineV2
import com.amaury.pointage.v2.engine.SegmentedPayrollPremiumEvidenceV2
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionEvidenceBuilderV2
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.engine.SegmentedWorkedVariableGrossSourceResultV2

data class SegmentedPayrollCoverageRequirementV2(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Raccord canonique RuntimeV2 -> couverture attestée -> preuves B21.
 *
 * La plage demandée couvre les semaines ISO entières touchées par la timeline.
 * Aucun écran ni export ne choisit lui-même ces bornes.
 */
object SegmentedPayrollRuntimeSourceV2 {
    const val REQUIREMENT_WARNING =
        "Preuves B21 : impossible de déterminer la plage hebdomadaire complète à certifier."

    fun requirement(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2
    ): SegmentedPayrollCoverageRequirementV2 {
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        if (!timeline.reliable || timeline.slices.isEmpty()) {
            return SegmentedPayrollCoverageRequirementV2(
                timeline.periodStartEpochDay,
                timeline.periodEndEpochDay,
                false,
                (timeline.warnings + REQUIREMENT_WARNING).distinct()
            )
        }
        return try {
            val first = timeline.slices.minOf { it.startEpochDay }
            val last = timeline.slices.maxOf { it.endEpochDay }
            val firstMonday = previousOrSameMonday(first)
            val lastMonday = previousOrSameMonday(last)
            val lastSunday = Math.addExact(lastMonday, 6L)
            if (lastSunday < firstMonday) throw ArithmeticException()
            SegmentedPayrollCoverageRequirementV2(
                firstMonday,
                lastSunday,
                true,
                timeline.warnings.distinct()
            )
        } catch (_: ArithmeticException) {
            SegmentedPayrollCoverageRequirementV2(
                timeline.periodStartEpochDay,
                timeline.periodEndEpochDay,
                false,
                (timeline.warnings + REQUIREMENT_WARNING).distinct()
            )
        }
    }

    fun source(
        context: Context,
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val requirement = requirement(contracts, rules)
        if (!requirement.reliable) {
            val runtime = V2RuntimeReader.allSessions(context, nowMs)
            return SegmentedPayrollSessionSourceV2(
                employerId = contracts.employerId.trim(),
                sessions = runtime.sessions,
                sourceId = "coverage-requirement-unavailable",
                reliable = false,
                exhaustive = false,
                coveredStartEpochDay = requirement.startEpochDay,
                coveredEndEpochDay = requirement.endEpochDay,
                checkedAtMs = nowMs,
                timeZoneId = timeZoneId,
                warnings = (runtime.warnings + requirement.warnings).distinct()
            )
        }

        val source = SegmentedPayrollCoverageStoreV2.source(
            context = context,
            employerId = contracts.employerId,
            requiredStartEpochDay = requirement.startEpochDay,
            requiredEndEpochDay = requirement.endEpochDay,
            timeZoneId = timeZoneId,
            nowMs = nowMs
        )
        return source.copy(warnings = (source.warnings + requirement.warnings).distinct())
    }

    fun calculateVariables(
        context: Context,
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedWorkedVariableGrossSourceResultV2 =
        SegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(
            contracts = contracts,
            rules = rules,
            source = source(context, contracts, rules, timeZoneId, nowMs),
            premiums = premiums,
            nowMs = nowMs
        )

    internal fun previousOrSameMonday(epochDay: Long): Long =
        epochDay - ((epochDay % 7L + 10L) % 7L)
}
