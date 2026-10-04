package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SegmentedPayrollSessionEvidenceBuilderV2Test {
    @Test fun realV2SessionsReachB21WithoutMonthlyBaseDuplication() {
        val f = fixture((4L..8L).map { session("s$it", it, 8, it, 16) })
        val proof = f.build()
        assertTrue(proof.reliable)
        assertEquals(2400, proof.slices.single().weeks.single().week.paidMinutes)
        assertEquals(5, proof.contributingSessionIds.size)
        val result = f.calculate()
        assertTrue(result.reliable)
        assertEquals(62.5, result.pieces.single().variableGross, 0.0001)
    }

    @Test fun paidAndUnpaidPausesUseCanonicalAllocation() {
        val s = session("s", 4, 8, 4, 16).copy(pauses = listOf(
            PauseV2(ms(4, 12), ms(4, 12, 30), false, EventSourceV2.MANUAL),
            PauseV2(ms(4, 14), ms(4, 14, 15), true, EventSourceV2.MANUAL)))
        val f = fixture(listOf(s))
        val result = f.build()
        assertTrue(result.reliable)
        assertEquals(PaidWorkAllocationV2.paidOverlap(s, ms(4, 0), ms(11, 0)) / 60000,
            result.slices.single().weeks.single().week.paidMinutes.toLong())
        assertEquals(450, result.slices.single().weeks.single().week.paidMinutes)
    }

    @Test fun unknownPauseDoesNotBecomeUnpaidOrZero() {
        val s = session("s", 4, 8, 4, 16).copy(pauses = listOf(
            PauseV2(ms(4, 12), ms(4, 12, 30), null, EventSourceV2.IMPORT)))
        assertBlocked(fixture(listOf(s)))
    }
    @Test fun openSessionBlocks() {
        assertBlocked(fixture(listOf(session("s", 4, 8, 4, 16).copy(
            status = SessionStatusV2.OPEN, countedExitMs = null, realExitMs = null))))
    }
    @Test fun storageReliabilityDoesNotProveExhaustiveHistory() {
        val f = fixture(emptyList())
        assertBlocked(f.copy(source = f.source.copy(exhaustive = false)))
    }
    @Test fun corruptSourceNeverBecomesEmptyConfirmedWeek() {
        val f = fixture(emptyList())
        assertBlocked(f.copy(source = f.source.copy(reliable = false)))
    }
    @Test fun explicitlyCoveredNoWorkWeekIsReliableZero() {
        val f = fixture(emptyList())
        val proof = f.build()
        assertTrue(proof.reliable)
        assertEquals(1, proof.slices.single().weeks.size)
        assertEquals(0, proof.slices.single().weeks.single().week.paidMinutes)
        val result = f.calculate()
        assertTrue(result.reliable)
        assertEquals(0.0, result.pieces.single().variableGross, 0.0)
    }
    @Test fun firstMissingCoverageDayBlocks() {
        val f = fixture(listOf(session("s", 4, 8, 4, 16)))
        assertBlocked(f.copy(source = f.source.copy(coveredStartEpochDay = 5)))
    }
    @Test fun lastMissingCoverageDayBlocks() {
        val f = fixture(listOf(session("s", 4, 8, 4, 16)))
        assertBlocked(f.copy(source = f.source.copy(coveredEndEpochDay = 9)))
    }
    @Test fun unfinishedWeekCannotBeCertified() {
        val f = fixture(emptyList())
        assertBlocked(f.copy(source = f.source.copy(checkedAtMs = ms(8, 12))))
    }
    @Test fun futureCertificateIsRejected() {
        val f = fixture(emptyList())
        assertBlocked(f.copy(source = f.source.copy(checkedAtMs = f.now + 1)))
    }
    @Test fun sourceEmployerMustMatchContractOwner() {
        val f = fixture(emptyList())
        assertBlocked(f.copy(source = f.source.copy(employerId = "other")))
    }
    @Test fun otherKnownEmployerDoesNotContaminateWeek() {
        val f = fixture(listOf(session("own", 4, 8, 4, 16),
            session("other", 4, 8, 4, 16).copy(employerId = "other")))
        val proof = f.build()
        assertTrue(proof.reliable)
        assertEquals(listOf("own"), proof.contributingSessionIds)
        assertEquals(480, proof.slices.single().weeks.single().week.paidMinutes)
    }
    @Test fun unassignedRelevantSessionBlocks() {
        assertBlocked(fixture(listOf(session("s", 4, 8, 4, 16).copy(employerId = null))))
    }
    @Test fun overlappingSessionsBlock() {
        assertBlocked(fixture(listOf(session("s1", 4, 8, 4, 16), session("s2", 4, 15, 4, 17))))
    }
    @Test fun duplicatedIdentityOnDifferentDaysBlocks() {
        assertBlocked(fixture(listOf(session("s", 4, 8, 4, 16), session("s", 5, 8, 5, 16))))
    }
    @Test fun paidTimeOutsideSliceDoesNotLeakIntoPeriod() {
        val f = fixture(listOf(session("outside", 4, 8, 4, 16), session("inside", 6, 8, 6, 16)), start = 6)
        val proof = f.build()
        assertFalse(proof.reliable)
        assertTrue(proof.slices.isEmpty())
        assertTrue(proof.warnings.contains(SegmentedPayrollSessionEvidenceBuilderV2.EDGE_WARNING))
    }
    @Test fun partialWeekWithConfirmedNoWorkOutsideRemainsUsable() {
        val f = fixture(listOf(session("s", 6, 8, 6, 16)), start = 6)
        assertTrue(f.build().reliable)
        assertTrue(f.calculate().reliable)
    }
    @Test fun structuredNightUsesExplicitTimeZoneAndCanonicalPause() {
        val zone = "America/New_York"
        val f = fixture(listOf(session("s", 4, 22, 5, 6, zone)), zone = zone,
            payrollRules = rules(night = 1.25), night = NightPremiumRuleV2(22 * 60, 6 * 60, 1.25))
        val proof = f.build()
        assertTrue(proof.reliable)
        assertEquals(480, proof.slices.single().weeks.single().week.nightMinutes)
        assertEquals(20.0, f.calculate().pieces.single().variableGross, 0.0001)
    }
    @Test fun missingNightRuleBlocksRatherThanGuessingShift() {
        assertBlocked(fixture(listOf(session("s", 4, 22, 5, 6)), payrollRules = rules(night = 1.25)))
    }
    @Test fun sundayComesFromActualPaidDates() {
        val f = fixture(listOf(session("s", 10, 8, 10, 16)), payrollRules = rules(sunday = 1.5))
        assertEquals(480, f.build().slices.single().weeks.single().week.sundayMinutes)
        assertEquals(40.0, f.calculate().pieces.single().variableGross, 0.0001)
    }
    @Test fun globalSourceWarningSurvivesB21() {
        val f = fixture(listOf(session("s", 4, 8, 4, 16)))
        val result = f.copy(source = f.source.copy(warnings = listOf("trace-import", "trace-import"))).calculate()
        assertTrue(result.reliable)
        assertEquals(1, result.warnings.count { it == "trace-import" })
    }
    @Test fun missingPremiumProofBlocks() {
        assertBlocked(fixture(emptyList()).copy(premiums = emptyList()))
    }
    @Test fun missingHolidayScopeBlocks() {
        val f = fixture(emptyList())
        assertBlocked(f.copy(premiums = f.premiums.map { it.copy(holidayScope = null) }))
    }
    @Test fun invalidTimeZoneBlocksRatherThanFallingBackToUtc() {
        val f = fixture(emptyList())
        assertBlocked(f.copy(source = f.source.copy(timeZoneId = "Not/AZone")))
    }
    @Test fun dedicatedMayFirstIsNotPaidByGenericHolidayRate() {
        val start = LocalDate.of(2026, 4, 27).toEpochDay()
        assertBlocked(fixture(listOf(session("s", start + 4, 8, start + 4, 16)), start, start + 6))
    }
    @Test fun springClockChangeCountsActualElapsedNight() {
        val start = LocalDate.of(2026, 3, 23).toEpochDay()
        val f = fixture(listOf(session("s", start + 5, 22, start + 6, 6, "Europe/Paris")),
            start, start + 6, "Europe/Paris", rules(night = 1.25), NightPremiumRuleV2(22 * 60, 6 * 60, 1.25))
        val proof = f.build()
        assertTrue(proof.reliable)
        assertEquals(420, proof.slices.single().weeks.single().week.paidMinutes)
        assertEquals(420, proof.slices.single().weeks.single().week.nightMinutes)
    }

    private fun assertBlocked(f: Fixture) {
        val proof = f.build()
        assertFalse(proof.reliable)
        assertTrue(proof.slices.isEmpty())
        assertTrue(proof.warnings.isNotEmpty())
        val result = f.calculate()
        assertFalse(result.reliable)
        assertTrue(result.pieces.isEmpty())
        assertTrue(result.warnings.containsAll(proof.warnings))
    }

    private data class Fixture(
        val contracts: EmploymentContractPeriodResolutionV2,
        val convention: ConventionRulePeriodResolutionV2,
        val source: SegmentedPayrollSessionSourceV2,
        val premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        val now: Long
    ) {
        fun build() = SegmentedPayrollSessionEvidenceBuilderV2.build(contracts, convention, source, premiums, now)
        fun calculate() = SegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(contracts, convention, source, premiums, now)
    }
    private fun fixture(sessions: List<WorkSessionV2>, start: Long = 4, end: Long = 10,
                        zone: String = "UTC", payrollRules: PayrollRulesV2 = rules(),
                        night: NightPremiumRuleV2? = null): Fixture {
        val contracts = EmploymentContractPeriodResolverV2.resolve("company", start, end, true, listOf(
            EmploymentContractSnapshotV2("c1", "contract-test", start, null,
                ContractV2("c1", "company", ContractTypeV2.FULL_TIME, 2100, 10.0, 0L), 1L, null)))
        val convention = ConventionRulePeriodResolverV2.resolve("0292", start, end, true, listOf(
            ConventionRuleSnapshotV2("0292", "r1", "rule-test", start, null, payrollRules, 1L)))
        val slices = PayrollCalculationTimelineV2.align(contracts, convention).slices
        val firstMonday = start - ((start % 7 + 10) % 7)
        val lastSunday = end - ((end % 7 + 10) % 7) + 6
        val now = ms(lastSunday + 1, 12, zone = zone)
        val source = SegmentedPayrollSessionSourceV2("company", sessions, "runtime-snapshot-test", true, true,
            firstMonday, lastSunday, now, zone)
        return Fixture(contracts, convention, source, slices.map {
            SegmentedPayrollPremiumEvidenceV2(it, "rules-snapshot-test", true, night,
                FrenchPublicHolidayCalendarV2.Scope(FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE, true))
        }, now)
    }
    private fun rules(night: Double? = null, sunday: Double? = null) = PayrollRulesV2(
        weeklyRegularMinutes = 2100, overtimeTiers = listOf(OvertimeTierV2(2100, null, 1.25)),
        nightMultiplier = night, sundayMultiplier = sunday)
    private fun session(id: String, day: Long, hour: Int, endDay: Long, endHour: Int, zone: String = "UTC") =
        WorkSessionV2(id, "company", ms(day, hour, zone = zone), ms(day, hour, zone = zone),
            ms(endDay, endHour, zone = zone), ms(endDay, endHour, zone = zone), status = SessionStatusV2.CLOSED)
    private fun ms(day: Long, hour: Int, minute: Int = 0, zone: String = "UTC") =
        LocalDate.ofEpochDay(day).atTime(hour, minute).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
}
