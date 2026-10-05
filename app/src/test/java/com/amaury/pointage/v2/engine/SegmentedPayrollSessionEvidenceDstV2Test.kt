package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SegmentedPayrollSessionEvidenceDstV2Test {
    @Test fun ambiguousFallNightBoundaryBlocks() = assertBlocked(LocalDate.of(2026, 10, 19))
    @Test fun nonexistentSpringNightBoundaryBlocks() = assertBlocked(LocalDate.of(2026, 3, 23))

    private fun assertBlocked(monday: LocalDate) {
        val zone = ZoneId.of("Europe/Paris")
        val start = monday.toEpochDay()
        val end = start + 6
        val sessionStart = monday.plusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessionEnd = monday.plusDays(6).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        val now = monday.plusDays(7).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val contracts = EmploymentContractPeriodResolverV2.resolve("company", start, end, true, listOf(
            EmploymentContractSnapshotV2("c1", "contract-test", start, null,
                ContractV2("c1", "company", ContractTypeV2.FULL_TIME, 2100, 10.0, 0L), 1L, null)))
        val rules = ConventionRulePeriodResolverV2.resolve("0292", start, end, true, listOf(
            ConventionRuleSnapshotV2("0292", "r1", "rule-test", start, null,
                PayrollRulesV2(weeklyRegularMinutes = 2100,
                    overtimeTiers = listOf(OvertimeTierV2(2100, null, 1.25)), nightMultiplier = 1.25), 1L)))
        val source = SegmentedPayrollSessionSourceV2("company", listOf(
            WorkSessionV2(id = "s", employerId = "company", realArrivalMs = sessionStart,
                countedEntryMs = sessionStart, realExitMs = sessionEnd, countedExitMs = sessionEnd,
                status = SessionStatusV2.CLOSED)), "confirmed-snapshot", true, true, start, end, now, zone.id)
        val premiums = PayrollCalculationTimelineV2.align(contracts, rules).slices.map {
            SegmentedPayrollPremiumEvidenceV2(it, "rule-snapshot", true,
                NightPremiumRuleV2(150, 360, 1.25),
                FrenchPublicHolidayCalendarV2.Scope(FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE, true))
        }
        val proof = SegmentedPayrollSessionEvidenceBuilderV2.build(contracts, rules, source, premiums, now)
        assertFalse(proof.reliable)
        assertTrue(proof.slices.isEmpty())
        assertTrue(proof.warnings.contains(SegmentedPayrollSessionEvidenceBuilderV2.CALENDAR_WARNING))
        val variable = SegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(contracts, rules, source, premiums, now)
        assertFalse(variable.reliable)
        assertTrue(variable.pieces.isEmpty())
        assertTrue(variable.warnings.containsAll(proof.warnings))
    }
}
