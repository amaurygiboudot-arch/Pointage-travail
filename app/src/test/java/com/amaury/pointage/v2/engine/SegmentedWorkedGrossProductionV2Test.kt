package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.SegmentedProrationSourceV2

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
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
    fun twoProvenContractSegmentsReachB20WithExplicitZeroVariables() {
        val f = fixture()
        val result = SegmentedWorkedGrossProductionV2.calculate(
            contracts = f.contracts,
            rules = f.rules,
            prorationSource = f.proration,
            source = f.source,
            premiums = f.premiums,
            nowMs = f.nowMs
        )

        assertTrue(result.reliable)
        assertEquals(2_275.0, result.baseGross!!, 0.0001)
        assertEquals(0.0, result.variableGross!!, 0.0)
        assertEquals(2_275.0, result.workedGross!!, 0.0001)
    }

    @Test
    fun anonymizedReadonlyExportBlocksUntilEmployerIsExplicitlyConfirmed() {
        val f = readonlyExportFixture(assignFirstEmployer = false)
        val result = SegmentedWorkedGrossProductionV2.calculateDetailed(
            contracts = f.contracts,
            rules = f.rules,
            prorationSource = f.proration,
            source = f.source,
            premiums = f.premiums,
            nowMs = f.nowMs
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(result.evidence.warnings.contains(WorkSessionEmployerAssignmentV2.WARNING))
    }

    @Test
    fun anonymizedReadonlyExportReachesB20AfterExplicitEmployerConfirmation() {
        val f = readonlyExportFixture(assignFirstEmployer = true)
        val result = SegmentedWorkedGrossProductionV2.calculateDetailed(
            contracts = f.contracts,
            rules = f.rules,
            prorationSource = f.proration,
            source = f.source,
            premiums = f.premiums,
            nowMs = f.nowMs
        )

        assertTrue(result.reliable)
        assertEquals(6, result.evidence.contributingSessionIds.size)
        assertEquals(listOf(43, 2019), result.evidence.slices.single().weeks.map { it.week.paidMinutes })
        assertEquals(43, result.evidence.slices.single().weeks.first().week.sundayMinutes)
        assertEquals(0.0, result.variables.pieces.single().variableGross, 0.0)
        assertEquals(result.base.baseGross!!, result.workedGross!!, 0.0001)
    }

    @Test
    fun missingCoverageBlocksWholeB20AndKeepsB21Warning() {
        val f = fixture()
        val result = SegmentedWorkedGrossProductionV2.calculate(
            contracts = f.contracts,
            rules = f.rules,
            prorationSource = f.proration,
            source = f.source.copy(exhaustive = false),
            premiums = f.premiums,
            nowMs = f.nowMs
        )

        assertFalse(result.reliable)
        assertNull(result.workedGross)
        assertTrue(result.warnings.contains(SegmentedPayrollSessionEvidenceBuilderV2.SOURCE_WARNING))
        assertTrue(result.warnings.contains(SegmentedWorkedGrossAssemblerV2.VARIABLE_RELIABILITY_WARNING))
    }

    @Test
    fun ruleChangeInsideContractBlocksBaseBeforeAssembly() {
        val f = fixture()
        val changedRules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 4,
            periodEndEpochDay = 17,
            sourceReliable = true,
            snapshots = listOf(
                ConventionRuleSnapshotV2(
                    "0292", "r1", "rules", 4, 7,
                    PayrollRulesV2(weeklyRegularMinutes = 2100), 1L
                ),
                ConventionRuleSnapshotV2(
                    "0292", "r2", "rules", 8, null,
                    PayrollRulesV2(weeklyRegularMinutes = 2100, saturdayMultiplier = 1.25), 1L
                )
            )
        )

        val base = SegmentedMonthlyBaseBridgeV2.calculate(
            contracts = f.contracts,
            rules = changedRules,
            proration = f.proration.proration
        )

        assertFalse(base.reliable)
        assertNull(base.baseGross)
        assertTrue(base.warnings.contains(SegmentedMonthlyBaseBridgeV2.RULE_CHANGES_WITHIN_CONTRACT_WARNING))
    }

    private data class Fixture(
        val contracts: EmploymentContractPeriodResolutionV2,
        val rules: ConventionRulePeriodResolutionV2,
        val proration: SegmentedProrationSourceV2,
        val source: SegmentedPayrollSessionSourceV2,
        val premiums: List<SegmentedPayrollPremiumEvidenceV2>,
        val nowMs: Long
    )

    private fun readonlyExportFixture(assignFirstEmployer: Boolean): Fixture {
        val start = LocalDate.of(2024, 9, 22).toEpochDay()
        val end = LocalDate.of(2024, 9, 27).toEpochDay()
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = start,
            periodEndEpochDay = end,
            sourceReliable = true,
            snapshots = listOf(contractSnapshot("real-contract", start, null, 10.0))
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = start,
            periodEndEpochDay = end,
            sourceReliable = true,
            snapshots = listOf(
                ConventionRuleSnapshotV2(
                    "0292",
                    "real-rules",
                    "anonymized-readonly-export-rules",
                    start,
                    null,
                    PayrollRulesV2(
                        weeklyRegularMinutes = 2100,
                        overtimeTiers = listOf(OvertimeTierV2(2100, null, 1.25))
                    ),
                    1L
                )
            )
        )
        val proration = SegmentedProrationSourceV2(
            proration = ConfirmedSegmentedMonthlyProrationV2(
                sourceId = "anonymized-readonly-export-proration",
                checkedAtMs = 1L,
                segments = listOf(
                    ConfirmedProrationSegmentV2("real-contract", start, end, 2100)
                )
            ),
            reliable = true,
            warnings = emptyList()
        )
        val checkedAt = LocalDate.of(2024, 9, 30)
            .atTime(12, 0)
            .atZone(ZoneId.of("Europe/Paris"))
            .toInstant()
            .toEpochMilli()
        val source = SegmentedPayrollSessionSourceV2(
            employerId = "company",
            sessions = readonlyExportSessions(assignFirstEmployer),
            sourceId = "anonymized-readonly-export-2026-09-25",
            reliable = true,
            exhaustive = true,
            coveredStartEpochDay = LocalDate.of(2024, 9, 16).toEpochDay(),
            coveredEndEpochDay = LocalDate.of(2024, 9, 29).toEpochDay(),
            checkedAtMs = checkedAt,
            timeZoneId = "Europe/Paris"
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        val premiums = timeline.slices.map {
            SegmentedPayrollPremiumEvidenceV2(
                slice = it,
                sourceId = "anonymized-readonly-export-premiums",
                reliable = true,
                nightRule = null,
                holidayScope = FrenchPublicHolidayCalendarV2.Scope(
                    FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE,
                    true
                )
            )
        }
        return Fixture(contracts, rules, proration, source, premiums, checkedAt)
    }

    // Export RuntimeV2 réel anonymisé : dates décalées de 104 semaines, identifiants remplacés ; durées et pauses conservées.
    private fun readonlyExportSessions(assignFirstEmployer: Boolean): List<WorkSessionV2> = listOf(
        WorkSessionV2(
            "real-1",
            if (assignFirstEmployer) "company" else null,
            1_726_985_436_558L,
            1_726_986_600_000L,
            1_726_989_188_965L,
            1_726_989_188_965L,
            status = SessionStatusV2.CLOSED
        ),
        WorkSessionV2(
            "real-2", "company", 1_727_157_401_808L, 1_727_157_600_000L,
            1_727_196_631_158L, 1_727_196_631_158L, status = SessionStatusV2.CLOSED
        ),
        WorkSessionV2(
            "real-3", "company", 1_727_196_651_268L, 1_727_197_200_000L,
            1_727_240_098_278L, 1_727_240_098_278L,
            pauses = listOf(
                PauseV2(
                    1_727_196_660_989L,
                    1_727_240_098_278L,
                    false,
                    EventSourceV2.MANUAL
                )
            ),
            status = SessionStatusV2.CLOSED
        ),
        WorkSessionV2(
            "real-4", "company", 1_727_243_598_851L, 1_727_244_000_000L,
            1_727_250_403_814L, 1_727_250_403_814L, status = SessionStatusV2.CLOSED
        ),
        WorkSessionV2(
            "real-5", "company", 1_727_329_910_877L, 1_727_330_400_000L,
            1_727_367_104_494L, 1_727_367_104_494L, status = SessionStatusV2.CLOSED
        ),
        WorkSessionV2(
            "real-6", "company", 1_727_416_363_660L, 1_727_416_800_000L,
            1_727_455_928_092L, 1_727_455_928_092L, status = SessionStatusV2.CLOSED
        )
    )

    private fun fixture(): Fixture {
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = 4,
            periodEndEpochDay = 17,
            sourceReliable = true,
            snapshots = listOf(
                contractSnapshot("c1", 4, 10, 10.0),
                contractSnapshot("c2", 11, null, 20.0)
            )
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 4,
            periodEndEpochDay = 17,
            sourceReliable = true,
            snapshots = listOf(
                ConventionRuleSnapshotV2(
                    "0292", "r1", "rules", 4, null,
                    PayrollRulesV2(
                        weeklyRegularMinutes = 2100,
                        overtimeTiers = listOf(OvertimeTierV2(2100, null, 1.25))
                    ),
                    1L
                )
            )
        )
        val proration = SegmentedProrationSourceV2(
            proration = ConfirmedSegmentedMonthlyProrationV2(
                sourceId = "planning-confirme",
                checkedAtMs = 1L,
                segments = listOf(
                    ConfirmedProrationSegmentV2("c1", 4, 10, 2100),
                    ConfirmedProrationSegmentV2("c2", 11, 17, 2100)
                )
            ),
            reliable = true,
            warnings = emptyList()
        )
        val now = ms(18, 12)
        val source = SegmentedPayrollSessionSourceV2(
            employerId = "company",
            sessions = emptyList(),
            sourceId = "coverage-test",
            reliable = true,
            exhaustive = true,
            coveredStartEpochDay = 4,
            coveredEndEpochDay = 17,
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
        return Fixture(contracts, rules, proration, source, premiums, now)
    }

    private fun contractSnapshot(
        id: String,
        from: Long,
        to: Long?,
        rate: Double
    ) = EmploymentContractSnapshotV2(
        versionId = id,
        sourceId = "contract-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        contract = ContractV2(
            id = id,
            employerId = "company",
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 2100,
            grossHourlyRate = rate,
            hireDateEpochDay = 0L
        ),
        checkedAtMs = 1L,
        note = null
    )

    private fun ms(day: Long, hour: Int): Long =
        LocalDate.ofEpochDay(day).atTime(hour, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
}
