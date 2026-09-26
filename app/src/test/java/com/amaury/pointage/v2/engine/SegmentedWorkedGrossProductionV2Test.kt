package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedWorkedGrossProductionV2Test {
    @Test
    fun `source B21 fiable atteint B20 sans perdre le warning global`() {
        val f = fixture()
        val result = SegmentedWorkedGrossProductionV2.calculateFromSource(
            contracts = f.contracts,
            rules = f.rules,
            base = f.base,
            source = f.source.copy(warnings = listOf("trace-source")),
            premiums = f.premiums,
            nowMs = f.now
        )

        assertTrue(result.reliable)
        assertEquals(62.5, result.variables.pieces.single().variableGross, 0.0001)
        assertEquals(1_062.5, result.worked.workedGross!!, 0.0001)
        assertTrue(result.warnings.contains("trace-source"))
        assertTrue(result.worked.warnings.contains("trace-source"))
    }

    @Test
    fun `couverture non exhaustive bloque B21 et B20`() {
        val f = fixture()
        val result = SegmentedWorkedGrossProductionV2.calculateFromSource(
            contracts = f.contracts,
            rules = f.rules,
            base = f.base,
            source = f.source.copy(exhaustive = false),
            premiums = f.premiums,
            nowMs = f.now
        )

        assertFalse(result.reliable)
        assertFalse(result.variables.reliable)
        assertNull(result.worked.workedGross)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `bornes de mois sont etendues aux semaines ISO completes`() {
        assertEquals(
            4L to 10L,
            SegmentedWorkedGrossProductionV2.requiredCoverageBounds(6L, 8L)
        )
        assertEquals(
            -3L to 3L,
            SegmentedWorkedGrossProductionV2.requiredCoverageBounds(0L, 0L)
        )
    }

    @Test
    fun `bornes extremes bloquent sans overflow`() {
        assertNull(
            SegmentedWorkedGrossProductionV2.requiredCoverageBounds(
                Long.MAX_VALUE,
                Long.MAX_VALUE
            )
        )
        assertNull(
            SegmentedWorkedGrossProductionV2.requiredCoverageBounds(
                Long.MIN_VALUE,
                Long.MIN_VALUE
            )
        )
    }

    private data class Fixture(
        val contracts: EmploymentContractPeriodResolutionV2,
        val rules: ConventionRulePeriodResolutionV2,
        val base: SegmentedMonthlyBaseResultV2,
        val source: SegmentedPayrollSessionSourceV2,
        val premiums: List<SegmentedPayrollPremiumEvidenceV2>,
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
                        contractualWeeklyMinutes = 2100,
                        grossHourlyRate = 10.0,
                        hireDateEpochDay = 0L
                    ),
                    checkedAtMs = 1L,
                    note = null
                )
            )
        )
        val payrollRules = PayrollRulesV2(
            weeklyRegularMinutes = 2100,
            overtimeTiers = listOf(OvertimeTierV2(2100, null, 1.25))
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
        val now = ms(11L, 12)
        val source = SegmentedPayrollSessionSourceV2(
            employerId = "company",
            sessions = sessions,
            sourceId = "coverage-test",
            reliable = true,
            exhaustive = true,
            coveredStartEpochDay = 4L,
            coveredEndEpochDay = 10L,
            checkedAtMs = now,
            timeZoneId = "UTC"
        )
        val premiums = PayrollCalculationTimelineV2.align(contracts, rules).slices.map {
            SegmentedPayrollPremiumEvidenceV2(
                slice = it,
                sourceId = "premium-test",
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
                    scheduledMinutes = 2100,
                    factor = 1.0,
                    fullMonthBaseGross = 1_000.0,
                    proratedBaseGross = 1_000.0
                )
            ),
            baseGross = 1_000.0,
            reliable = true,
            warnings = emptyList()
        )
        return Fixture(contracts, rules, base, source, premiums, now)
    }

    private fun ms(day: Long, hour: Int): Long =
        LocalDate.ofEpochDay(day).atTime(hour, 0)
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
}
