package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedWorkedVariableGrossSourceV2Test {
    @Test
    fun fullTimeMondaySegmentsProduceOnlyVariableOvertime() {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(
                contract("c1", 4, 10, 10.0, ContractTypeV2.FULL_TIME, 35 * 60),
                contract("c2", 11, null, 20.0, ContractTypeV2.FULL_TIME, 35 * 60)
            )
        )
        val rules = rules(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(rule("r1", 4, null))
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)

        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = timeline.slices.mapIndexed { index, slice ->
                evidence(
                    slice = slice,
                    weekYear = 1970,
                    weekOfYear = index + 2,
                    paidMinutes = 40 * 60
                )
            }
        )

        assertTrue(result.reliable)
        assertEquals(2, result.pieces.size)
        assertEquals(62.5, result.pieces[0].variableGross, 0.0001)
        assertEquals(125.0, result.pieces[1].variableGross, 0.0001)
        assertEquals(2, result.breakdowns.size)
        assertEquals(62.5, result.breakdowns[0].overtimeGross, 0.0001)
        assertEquals(0.0, result.breakdowns[0].complementaryGross, 0.0)
        assertEquals(0, result.breakdowns[0].complementaryMinutes)
        assertEquals(0.0, result.breakdowns[0].premiumGross, 0.0)
        assertEquals(125.0, result.breakdowns[1].overtimeGross, 0.0001)
        assertEquals(0.0, result.breakdowns[1].complementaryGross, 0.0)
        assertEquals(0, result.breakdowns[1].complementaryMinutes)
        assertEquals(0.0, result.breakdowns[1].premiumGross, 0.0)
    }

    @Test
    fun materialMidweekTransitionFailsClosed() {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(
                contract("c1", 4, 8, 10.0, ContractTypeV2.FULL_TIME, 35 * 60),
                contract("c2", 9, null, 20.0, ContractTypeV2.FULL_TIME, 35 * 60)
            )
        )
        val rules = rules(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(rule("r1", 4, null))
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)

        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = timeline.slices.mapIndexed { index, slice ->
                evidence(
                    slice = slice,
                    weekYear = 1970,
                    weekOfYear = index + 2,
                    paidMinutes = 35 * 60
                )
            }
        )

        assertFalse(result.reliable)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedVariableGrossSourceV2.WEEK_CONTEXT_WARNING
            )
        )
    }

    @Test
    fun sameWeekCannotBeOwnedByTwoSlices() {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(
                contract("c1", 4, 10, 10.0, ContractTypeV2.FULL_TIME, 35 * 60),
                contract("c2", 11, null, 20.0, ContractTypeV2.FULL_TIME, 35 * 60)
            )
        )
        val rules = rules(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(rule("r1", 4, null))
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)

        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = timeline.slices.map { slice ->
                evidence(
                    slice = slice,
                    weekYear = 1970,
                    weekOfYear = 2,
                    paidMinutes = 35 * 60
                )
            }
        )

        assertFalse(result.reliable)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedVariableGrossSourceV2.WEEK_CONTEXT_WARNING
            )
        )
    }

    @Test
    fun explicitZeroVariableIsReliable() {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 10,
            snapshots = listOf(
                contract("c1", 4, null, 10.0, ContractTypeV2.FULL_TIME, 35 * 60)
            )
        )
        val rules = rules(
            periodStart = 4,
            periodEnd = 10,
            snapshots = listOf(rule("r1", 4, null))
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)

        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = listOf(
                evidence(
                    slice = timeline.slices.single(),
                    weekYear = 1970,
                    weekOfYear = 2,
                    paidMinutes = 35 * 60
                )
            )
        )

        assertTrue(result.reliable)
        assertEquals(0.0, result.pieces.single().variableGross, 0.0)
    }

    @Test
    fun partTimeComplementaryHoursRemainBlocked() {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 10,
            snapshots = listOf(
                contract("c1", 4, null, 12.0, ContractTypeV2.PART_TIME, 20 * 60)
            )
        )
        val rules = rules(
            periodStart = 4,
            periodEnd = 10,
            snapshots = listOf(
                rule(
                    version = "r1",
                    from = 4,
                    to = null,
                    weeklyRegularMinutes = 20 * 60
                )
            )
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)

        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = listOf(
                evidence(
                    slice = timeline.slices.single(),
                    weekYear = 1970,
                    weekOfYear = 2,
                    paidMinutes = 22 * 60
                )
            )
        )

        assertFalse(result.reliable)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedVariableGrossSourceV2.PART_TIME_COMPLEMENTARY_WARNING
            )
        )
    }

    @Test
    fun unreliableWeekContextNeverBecomesZero() {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 10,
            snapshots = listOf(
                contract("c1", 4, null, 10.0, ContractTypeV2.FULL_TIME, 35 * 60)
            )
        )
        val rules = rules(
            periodStart = 4,
            periodEnd = 10,
            snapshots = listOf(rule("r1", 4, null))
        )
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        val slice = timeline.slices.single()

        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = listOf(
                SegmentedPayrollSliceEvidenceV2(
                    startEpochDay = slice.startEpochDay,
                    endEpochDay = slice.endEpochDay,
                    contractVersionId = slice.contractVersionId,
                    ruleVersionId = slice.ruleVersionId,
                    weeks = listOf(
                        SegmentedPayrollWeekEvidenceV2(
                            weekYear = 1970,
                            weekOfYear = 2,
                            week = PayrollWeekV2(paidMinutes = 35 * 60),
                            fullWeekContextReliable = false
                        )
                    ),
                    paidTimeReliable = true,
                    premiumTimeBreakdownReliable = true,
                    payrollRulesReliable = true
                )
            )
        )

        assertFalse(result.reliable)
        assertTrue(
            result.warnings.contains(
                SegmentedWorkedVariableGrossSourceV2.EVIDENCE_WARNING
            )
        )
    }

    @Test
    fun identicalWeekWithinOneSliceFailsClosed() {
        val week = weekEvidence(2, 40 * 60)
        assertDuplicateWeekBlocked(calculateSingleSliceWeeks(listOf(week, week)))
    }

    @Test
    fun conflictingWeekWithinOneSliceFailsClosed() {
        assertDuplicateWeekBlocked(
            calculateSingleSliceWeeks(
                listOf(weekEvidence(2, 40 * 60), weekEvidence(2, 36 * 60))
            )
        )
    }

    @Test
    fun nonAdjacentDuplicateWeekWithinOneSliceFailsClosed() {
        val week = weekEvidence(2, 40 * 60)
        assertDuplicateWeekBlocked(
            calculateSingleSliceWeeks(listOf(week, weekEvidence(3, 36 * 60), week))
        )
    }

    @Test
    fun duplicateZeroVariableWeekDoesNotBecomeReliableZero() {
        val week = weekEvidence(2, 35 * 60)
        assertDuplicateWeekBlocked(calculateSingleSliceWeeks(listOf(week, week)))
    }

    @Test
    fun distinctWeeksWithinOneSliceRemainReliable() {
        val result = calculateSingleSliceWeeks(
            listOf(weekEvidence(2, 40 * 60), weekEvidence(3, 40 * 60))
        )

        assertTrue(result.reliable)
        assertEquals(1, result.pieces.size)
        assertEquals(125.0, result.pieces.single().variableGross, 0.0001)
        assertFalse(result.warnings.contains(SegmentedWorkedVariableGrossSourceV2.DUPLICATE_WEEK_WARNING))
    }

    @Test
    fun negativePaidMinutesFailClosedBeforeFullTimeCalculation() {
        assertInvalidPaidTimeBlocked(
            calculateSingleSliceWeeks(listOf(weekEvidence(2, -1), weekEvidence(3, 0)))
        )
    }

    @Test
    fun minimumPaidMinutesFailClosedBeforeFullTimeCalculation() {
        assertInvalidPaidTimeBlocked(
            calculateSingleSliceWeeks(listOf(weekEvidence(2, Int.MIN_VALUE), weekEvidence(3, 0)))
        )
    }

    @Test
    fun negativePaidMinutesAfterValidWeekDoNotPublishPartialGross() {
        assertInvalidPaidTimeBlocked(
            calculateSingleSliceWeeks(listOf(weekEvidence(2, 40 * 60), weekEvidence(3, -1)))
        )
    }

    @Test
    fun negativePaidMinutesInLaterSliceDiscardEarlierVariable() {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(
                contract("c1", 4, 10, 10.0, ContractTypeV2.FULL_TIME, 35 * 60),
                contract("c2", 11, null, 20.0, ContractTypeV2.FULL_TIME, 35 * 60)
            )
        )
        val rules = rules(4, 17, listOf(rule("r1", 4, null)))
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        assertEquals(2, timeline.slices.size)
        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = timeline.slices.mapIndexed { index, slice ->
                evidence(slice, 1970, index + 2, if (index == 0) 40 * 60 else -1)
                    .copy(warnings = if (index == 1) listOf("preuve-import") else emptyList())
            }
        )
        assertInvalidPaidTimeBlocked(result)
        assertTrue(result.warnings.contains("preuve-import"))
    }

    @Test
    fun partTimeNegativePaidMinutesFailClosed() {
        assertInvalidPaidTimeBlocked(
            calculateSingleSliceWeeks(
                weeks = listOf(weekEvidence(2, -1), weekEvidence(3, 0)),
                contractType = ContractTypeV2.PART_TIME,
                contractualWeeklyMinutes = 20 * 60
            )
        )
    }

    @Test
    fun explicitZeroPaidTimeRemainsReliable() {
        val result = calculateSingleSliceWeeks(listOf(weekEvidence(2, 0), weekEvidence(3, 0)))
        assertTrue(result.reliable)
        assertEquals(1, result.pieces.size)
        assertEquals(0.0, result.pieces.single().variableGross, 0.0)
        assertFalse(result.warnings.contains(SegmentedWorkedVariableGrossSourceV2.INVALID_PAID_TIME_WARNING))
    }

    @Test
    fun partTimeExplicitZeroPaidTimeRemainsReliable() {
        val result = calculateSingleSliceWeeks(
            weeks = listOf(weekEvidence(2, 0), weekEvidence(3, 0)),
            contractType = ContractTypeV2.PART_TIME,
            contractualWeeklyMinutes = 20 * 60
        )
        assertTrue(result.reliable)
        assertEquals(1, result.pieces.size)
        assertEquals(0.0, result.pieces.single().variableGross, 0.0)
        assertFalse(result.warnings.contains(SegmentedWorkedVariableGrossSourceV2.INVALID_PAID_TIME_WARNING))
    }

    @Test
    fun emptyFullTimeWeeksNeverBecomeReliableZero() {
        assertMissingWeeksBlocked(calculateSingleSliceWeeks(emptyList()))
    }

    @Test
    fun emptyPartTimeWeeksNeverBecomeReliableZero() {
        assertMissingWeeksBlocked(
            calculateSingleSliceWeeks(
                weeks = emptyList(),
                contractType = ContractTypeV2.PART_TIME,
                contractualWeeklyMinutes = 20 * 60
            )
        )
    }

    @Test
    fun emptyEarlierSliceBlocksOtherwiseValidPeriod() {
        assertMissingWeeksBlocked(calculateWithEmptySlice(emptyIndex = 0))
    }

    @Test
    fun emptyLaterSliceDiscardsEarlierVariableAndPreservesWarnings() {
        assertMissingWeeksBlocked(calculateWithEmptySlice(emptyIndex = 1))
    }

    private fun calculateWithEmptySlice(emptyIndex: Int): SegmentedWorkedVariableGrossSourceResultV2 {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(
                contract("c1", 4, 10, 10.0, ContractTypeV2.FULL_TIME, 35 * 60),
                contract("c2", 11, null, 20.0, ContractTypeV2.FULL_TIME, 35 * 60)
            )
        )
        val rules = rules(4, 17, listOf(rule("r1", 4, null)))
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        assertEquals(2, timeline.slices.size)
        val result = SegmentedWorkedVariableGrossSourceV2.calculate(
            contracts = contracts,
            rules = rules,
            sliceEvidence = timeline.slices.mapIndexed { index, slice ->
                val supplied = evidence(slice, 1970, index + 2, 40 * 60)
                if (index == emptyIndex) {
                    supplied.copy(weeks = emptyList(), warnings = listOf("preuve-vide"))
                } else {
                    supplied
                }
            }
        )
        assertTrue(result.warnings.contains("preuve-vide"))
        return result
    }

    private fun assertMissingWeeksBlocked(result: SegmentedWorkedVariableGrossSourceResultV2) {
        assertFalse(result.reliable)
        assertTrue(result.pieces.isEmpty())
        assertTrue(result.warnings.contains(SegmentedWorkedVariableGrossSourceV2.MISSING_WEEKS_WARNING))
    }

    private fun assertInvalidPaidTimeBlocked(result: SegmentedWorkedVariableGrossSourceResultV2) {
        assertFalse(result.reliable)
        assertTrue(result.pieces.isEmpty())
        assertTrue(result.warnings.contains(SegmentedWorkedVariableGrossSourceV2.INVALID_PAID_TIME_WARNING))
    }

    private fun assertDuplicateWeekBlocked(result: SegmentedWorkedVariableGrossSourceResultV2) {
        assertFalse(result.reliable)
        assertTrue(result.pieces.isEmpty())
        assertTrue(result.warnings.contains(SegmentedWorkedVariableGrossSourceV2.DUPLICATE_WEEK_WARNING))
    }

    private fun calculateSingleSliceWeeks(
        weeks: List<SegmentedPayrollWeekEvidenceV2>,
        contractType: ContractTypeV2 = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes: Int = 35 * 60
    ): SegmentedWorkedVariableGrossSourceResultV2 {
        val contracts = contracts(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(
                contract("c1", 4, null, 10.0, contractType, contractualWeeklyMinutes)
            )
        )
        val rules = rules(
            periodStart = 4,
            periodEnd = 17,
            snapshots = listOf(rule("r1", 4, null, weeklyRegularMinutes = contractualWeeklyMinutes))
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
