package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calendar identity regressions; the existing B21 suite remains unchanged. */
class SegmentedWorkedVariableGrossCoverageV2Test {
    @Test
    fun missingFirstWeekBlocksTheWholeSlice() {
        assertWeekCoverageBlocked(calculateSingleSliceWeeks(listOf(weekEvidence(3, 40 * 60))))
    }

    @Test
    fun missingLastWeekBlocksTheWholeSlice() {
        assertWeekCoverageBlocked(calculateSingleSliceWeeks(listOf(weekEvidence(2, 40 * 60))))
    }

    @Test
    fun outOfPeriodWeekWithMatchingCountIsRejected() {
        assertWeekCoverageBlocked(
            calculateSingleSliceWeeks(listOf(weekEvidence(2, 40 * 60), weekEvidence(4, 40 * 60)))
        )
    }

    @Test
    fun wrongWeekYearWithMatchingCountIsRejected() {
        assertWeekCoverageBlocked(
            calculateSingleSliceWeeks(listOf(
                weekEvidence(2, 40 * 60).copy(weekYear = 1971),
                weekEvidence(3, 40 * 60).copy(weekYear = 1971)
            ))
        )
    }

    @Test
    fun invalidWeekNumbersAreRejected() {
        for (invalid in listOf(0, 54, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertWeekCoverageBlocked(
                calculateSingleSliceWeeks(listOf(weekEvidence(invalid, 40 * 60), weekEvidence(3, 40 * 60)))
            )
        }
    }

    @Test
    fun missingZeroWeekDoesNotProveReliableZero() {
        assertWeekCoverageBlocked(calculateSingleSliceWeeks(listOf(weekEvidence(2, 0))))
    }

    @Test
    fun partTimeMissingWeekDoesNotProveReliableZero() {
        assertWeekCoverageBlocked(calculateSingleSliceWeeks(
            weeks = listOf(weekEvidence(2, 0)),
            contractType = ContractTypeV2.PART_TIME,
            contractualWeeklyMinutes = 20 * 60
        ))
    }

    @Test
    fun reorderedCompleteWeeksKeepTheSameVariable() {
        val result = calculateSingleSliceWeeks(listOf(weekEvidence(3, 40 * 60), weekEvidence(2, 40 * 60)))
        assertTrue(result.reliable)
        assertEquals(125.0, result.pieces.single().variableGross, 0.0001)
    }

    @Test
    fun isoWeekYearTransitionKeepsBothCompleteWeeks() {
        val result = calculateSingleSliceWeeks(
            weeks = listOf(
                weekEvidence(53, 40 * 60).copy(weekYear = 2020),
                weekEvidence(1, 40 * 60).copy(weekYear = 2021)
            ),
            periodStart = 18624,
            periodEnd = 18637
        )
        assertTrue(result.reliable)
        assertEquals(125.0, result.pieces.single().variableGross, 0.0001)
    }

    @Test
    fun invalidLaterSliceDiscardsPreviouslyCalculatedMoneyAndKeepsWarnings() {
        val contracts = contracts(4, 17, listOf(
            contract("c1", 4, 10, 10.0, ContractTypeV2.FULL_TIME, 35 * 60),
            contract("c2", 11, null, 20.0, ContractTypeV2.FULL_TIME, 35 * 60)
        ))
        val rules = rules(4, 17, listOf(rule("r1", 4, null)))
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        assertEquals(2, timeline.slices.size)
        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = timeline.slices.mapIndexed { index, slice ->
                evidence(slice, 1970, if (index == 0) 2 else 4, 40 * 60)
                    .copy(warnings = if (index == 1) listOf("semaine-hors-periode") else emptyList())
            }
        )
        assertWeekCoverageBlocked(result)
        assertTrue(result.warnings.contains("semaine-hors-periode"))
    }

    private fun assertWeekCoverageBlocked(result: SegmentedWorkedVariableGrossSourceResultV2) {
        assertFalse(result.reliable)
        assertTrue(result.pieces.isEmpty())
        assertTrue(result.warnings.contains(SegmentedWorkedVariableGrossSourceV2.WEEK_COVERAGE_WARNING))
    }

    private fun calculateSingleSliceWeeks(
        weeks: List<SegmentedPayrollWeekEvidenceV2>,
        contractType: ContractTypeV2 = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes: Int = 35 * 60,
        periodStart: Long = 4,
        periodEnd: Long = 17
    ): SegmentedWorkedVariableGrossSourceResultV2 {
        val contracts = contracts(
            periodStart = periodStart,
            periodEnd = periodEnd,
            snapshots = listOf(
                contract("c1", periodStart, null, 10.0, contractType, contractualWeeklyMinutes)
            )
        )
        val rules = rules(
            periodStart = periodStart,
            periodEnd = periodEnd,
            snapshots = listOf(rule("r1", periodStart, null, weeklyRegularMinutes = contractualWeeklyMinutes))
        )
        val slice = PayrollCalculationTimelineV2.align(contracts, rules).slices.single()
        return SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = listOf(
                evidence(slice, 1970, 2, 35 * 60).copy(weeks = weeks)
            )
        )
    }

    private fun weekEvidence(weekOfYear: Int, paidMinutes: Int) =
        SegmentedPayrollWeekEvidenceV2(
            weekYear = 1970,
            weekOfYear = weekOfYear,
            week = PayrollWeekV2(paidMinutes = paidMinutes),
            fullWeekContextReliable = true
        )

    private fun contracts(
        periodStart: Long,
        periodEnd: Long,
        snapshots: List<EmploymentContractSnapshotV2>
    ) = EmploymentContractPeriodResolverV2.resolve(
        employerId = "company",
        periodStartEpochDay = periodStart,
        periodEndEpochDay = periodEnd,
        sourceReliable = true,
        snapshots = snapshots
    )

    private fun rules(
        periodStart: Long,
        periodEnd: Long,
        snapshots: List<ConventionRuleSnapshotV2>
    ) = ConventionRulePeriodResolverV2.resolve(
        idcc = "0292",
        periodStartEpochDay = periodStart,
        periodEndEpochDay = periodEnd,
        sourceReliable = true,
        snapshots = snapshots
    )

    private fun contract(
        version: String,
        from: Long,
        to: Long?,
        rate: Double,
        type: ContractTypeV2,
        weeklyMinutes: Int
    ) = EmploymentContractSnapshotV2(
        versionId = version,
        sourceId = "contract-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        contract = ContractV2(
            id = version,
            employerId = "company",
            type = type,
            contractualWeeklyMinutes = weeklyMinutes,
            grossHourlyRate = rate,
            hireDateEpochDay = 0L
        ),
        checkedAtMs = 1L,
        note = null
    )

    private fun rule(
        version: String,
        from: Long,
        to: Long?,
        weeklyRegularMinutes: Int = 35 * 60
    ) = ConventionRuleSnapshotV2(
        idcc = "0292",
        versionId = version,
        sourceId = "rule-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        rules = PayrollRulesV2(
            weeklyRegularMinutes = weeklyRegularMinutes,
            overtimeTiers = listOf(
                OvertimeTierV2(
                    fromMinutes = 35 * 60,
                    toMinutes = 43 * 60,
                    multiplier = 1.25
                ),
                OvertimeTierV2(
                    fromMinutes = 43 * 60,
                    toMinutes = null,
                    multiplier = 1.50
                )
            )
        ),
        checkedAtMs = 1L
    )

    private fun evidence(
        slice: PayrollCalculationSliceV2,
        weekYear: Int,
        weekOfYear: Int,
        paidMinutes: Int
    ) = SegmentedPayrollSliceEvidenceV2(
        startEpochDay = slice.startEpochDay,
        endEpochDay = slice.endEpochDay,
        contractVersionId = slice.contractVersionId,
        ruleVersionId = slice.ruleVersionId,
        weeks = listOf(
            SegmentedPayrollWeekEvidenceV2(
                weekYear = weekYear,
                weekOfYear = weekOfYear,
                week = PayrollWeekV2(paidMinutes = paidMinutes),
                fullWeekContextReliable = true
            )
        ),
        paidTimeReliable = true,
        premiumTimeBreakdownReliable = true,
        payrollRulesReliable = true
    )
}
