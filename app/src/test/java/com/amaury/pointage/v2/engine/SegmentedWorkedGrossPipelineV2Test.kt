package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SegmentedWorkedGrossPipelineV2Test {
    @Test
    fun sessionsReachB20WithoutLosingB21Warnings() {
        val f = fixture()
        val result = SegmentedWorkedGrossPipelineV2.calculate(
            f.contracts,
            f.rules,
            f.source.copy(warnings = listOf("trace-runtime")),
            f.premiums,
            f.base,
            f.now
        )

        assertTrue(result.reliable)
        assertEquals(1_500.0, result.baseGross!!, 0.0001)
        assertEquals(62.5, result.variableGross!!, 0.0001)
        assertEquals(1_562.5, result.workedGross!!, 0.0001)
        assertTrue(result.warnings.contains("trace-runtime"))
    }

    @Test
    fun missingExhaustiveCoverageBlocksTheWholeB21ToB20Chain() {
        val f = fixture()
        val result = SegmentedWorkedGrossPipelineV2.calculate(
            f.contracts,
            f.rules,
            f.source.copy(exhaustive = false, warnings = listOf("coverage-missing")),
            f.premiums,
            f.base,
            f.now
        )

        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertNull(result.variableGross)
        assertNull(result.workedGross)
        assertTrue(result.warnings.contains("coverage-missing"))
        assertTrue(result.warnings.contains(SegmentedPayrollSessionEvidenceBuilderV2.SOURCE_WARNING))
        assertTrue(result.warnings.contains(SegmentedWorkedGrossAssemblerV2.VARIABLE_RELIABILITY_WARNING))
    }

    @Test
    fun baseFailureAndB21WarningAreBothPreserved() {
        val f = fixture()
        val result = SegmentedWorkedGrossPipelineV2.calculate(
            f.contracts,
            f.rules,
            f.source.copy(warnings = listOf("trace-b21")),
            f.premiums,
            f.base.copy(reliable = false, warnings = listOf("base-bloquee")),
            f.now
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.contains("trace-b21"))
        assertTrue(result.warnings.contains("base-bloquee"))
        assertTrue(result.warnings.contains(SegmentedWorkedGrossAssemblerV2.BASE_WARNING))
    }

    private data class Fixture(
        val contracts: EmploymentContractPeriodResolutionV2,
        val rules: ConventionRulePeriodResolutionV2,
        val source: SegmentedPayrollSessionSourceV2,
        val premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        val base: SegmentedMonthlyBaseResultV2,
        val now: Long
    )

    private fun fixture(): Fixture {
        val start = 4L
        val end = 10L
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = start,
            periodEndEpochDay = end,
            sourceReliable = true,
            snapshots = listOf(
                EmploymentContractSnapshotV2(
                    versionId = "c1",
                    sourceId = "contract-test",
                    effectiveFromEpochDay = start,
                    effectiveToEpochDay = null,
                    contract = ContractV2(
                        id = "c1",
                        employerId = "company",
                        type = ContractTypeV2.FULL_TIME,
                        contractualWeeklyMinutes = 2_100,
                        grossHourlyRate = 10.0,
                        hireDateEpochDay = 0L
                    ),
                    checkedAtMs = 1L,
                    note = null
                )
            )
        )
        val payrollRules = PayrollRulesV2(
            weeklyRegularMinutes = 2_100,
            overtimeTiers = listOf(OvertimeTierV2(2_100, null, 1.25))
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = start,
            periodEndEpochDay = end,
            sourceReliable = true,
            snapshots = listOf(
                ConventionRuleSnapshotV2(
                    idcc = "0292",
                    versionId = "r1",
                    sourceId = "rule-test",
                    effectiveFromEpochDay = start,
                    effectiveToEpochDay = null,
                    rules = payrollRules,
                    checkedAtMs = 1L
                )
            )
        )
        val sessions = (4L..8L).map { day ->
            WorkSessionV2(
                id = "s$day",
                employerId = "company",
                realArrivalMs = ms(day, 8),
                countedEntryMs = ms(day, 8),
                countedExitMs = ms(day, 16),
                realExitMs = ms(day, 16),
                status = SessionStatusV2.CLOSED
            )
        }
        val now = ms(11, 12)
        val source = SegmentedPayrollSessionSourceV2(
            employerId = "company",
            sessions = sessions,
            sourceId = "runtime-test",
            reliable = true,
            exhaustive = true,
            coveredStartEpochDay = 4,
            coveredEndEpochDay = 10,
            checkedAtMs = now,
            timeZoneId = "UTC"
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        val premiums = timeline.slices.map {
            SegmentedPayrollPremiumEvidenceV2(
                slice = it,
                sourceId = "rules-test",
                reliable = true,
                nightRule = null,
                holidayScope = FrenchPublicHolidayCalendarV2.Scope(
                    FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE,
                    true
                )
            )
        }
        val base = SegmentedMonthlyBaseResultV2(
            pieces = listOf(
                SegmentedMonthlyBasePieceV2(
                    versionId = "c1",
                    startEpochDay = start,
                    endEpochDay = end,
                    scheduledMinutes = 2_100,
                    factor = 1.0,
                    fullMonthBaseGross = 1_500.0,
                    proratedBaseGross = 1_500.0
                )
            ),
            baseGross = 1_500.0,
            reliable = true,
            warnings = emptyList()
        )
        return Fixture(contracts, rules, source, premiums, base, now)
    }

    private fun ms(day: Long, hour: Int): Long =
        LocalDate.ofEpochDay(day)
            .atTime(hour, 0)
            .atZone(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
}
