package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionRulePeriodResolverV2
import com.amaury.pointage.v2.engine.ConventionRuleSnapshotV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolverV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.*
import org.junit.Test

class SegmentedPayrollRuntimeSourceV2Test {
    @Test
    fun partialPayrollWeekRequiresWholeMondayToSundayCoverage() {
        val (contracts, rules) = fixture(start = 6L, end = 8L)

        val requirement = SegmentedPayrollRuntimeSourceV2.requirement(contracts, rules)

        assertTrue(requirement.reliable)
        assertEquals(4L, requirement.startEpochDay)
        assertEquals(10L, requirement.endEpochDay)
    }

    @Test
    fun multiplePayrollWeeksExpandOnlyToTheirOuterWeekBoundaries() {
        val (contracts, rules) = fixture(start = 6L, end = 19L)

        val requirement = SegmentedPayrollRuntimeSourceV2.requirement(contracts, rules)

        assertTrue(requirement.reliable)
        assertEquals(4L, requirement.startEpochDay)
        assertEquals(24L, requirement.endEpochDay)
    }

    @Test
    fun alreadyCompleteMondaySundayPeriodIsUnchanged() {
        val (contracts, rules) = fixture(start = 4L, end = 10L)

        val requirement = SegmentedPayrollRuntimeSourceV2.requirement(contracts, rules)

        assertTrue(requirement.reliable)
        assertEquals(4L, requirement.startEpochDay)
        assertEquals(10L, requirement.endEpochDay)
    }

    @Test
    fun unreliableTimelineNeverProducesTrustedCoverageRequirement() {
        val (contracts, _) = fixture(start = 6L, end = 8L)
        val unreliableRules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 6L,
            periodEndEpochDay = 8L,
            sourceReliable = false,
            snapshots = emptyList()
        )

        val requirement = SegmentedPayrollRuntimeSourceV2.requirement(
            contracts,
            unreliableRules
        )

        assertFalse(requirement.reliable)
        assertTrue(
            requirement.warnings.contains(
                SegmentedPayrollRuntimeSourceV2.REQUIREMENT_WARNING
            )
        )
    }

    private fun fixture(
        start: Long,
        end: Long
    ): Pair<
        com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2,
        com.amaury.pointage.v2.engine.ConventionRulePeriodResolutionV2
    > {
        val contract = ContractV2(
            id = "c1",
            employerId = "company",
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 2100,
            grossHourlyRate = 10.0,
            hireDateEpochDay = 0L
        )
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
                    contract = contract,
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
        return contracts to rules
    }
}
