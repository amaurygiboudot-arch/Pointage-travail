package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.SegmentedProrationSourceV2

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
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
            proration = f.proration
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
