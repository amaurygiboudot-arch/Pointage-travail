package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.SegmentedProrationSourceV2
import com.amaury.pointage.v2.V2SegmentedMonthlyBaseProduction
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.*
import org.junit.Test

class SegmentedMonthlyBaseBridgeV2Test {
    @Test fun contractAndRuleVersionIdsRemainIndependent() {
        val contracts = contracts()
        val rules = rules(
            rule("rules-A", 0, 14),
            rule("rules-B", 15, null)
        )
        val result = SegmentedMonthlyBaseBridgeV2.calculate(
            contracts, rules, source()
        )
        assertTrue(result.reliable)
        assertEquals(2, result.pieces.size)
        assertEquals(2_275.0, result.baseGross!!, 0.0001)
    }

    @Test fun ruleChangeInsideOneContractSegmentBlocksBase() {
        val contracts = contracts()
        val rules = rules(
            rule("r1", 0, 6, regularMinutes = 35 * 60),
            rule("r2", 7, 14, regularMinutes = 36 * 60),
            rule("r3", 15, null, regularMinutes = 35 * 60)
        )
        val result = SegmentedMonthlyBaseBridgeV2.calculate(
            contracts, rules, source()
        )
        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertTrue(result.warnings.contains(
            SegmentedMonthlyBaseBridgeV2.RULE_CHANGES_WITHIN_CONTRACT_WARNING
        ))
    }

    @Test fun missingProrationSourceStaysUnknown() {
        val result = SegmentedMonthlyBaseBridgeV2.calculate(
            contracts(),
            rules(rule("r1", 0, 14), rule("r2", 15, null)),
            SegmentedProrationSourceV2(
                proration = null,
                reliable = false,
                warnings = listOf("missing confirmed planning")
            )
        )
        assertFalse(result.reliable)
        assertNull(result.baseGross)
        assertTrue(result.warnings.contains("missing confirmed planning"))
    }

    @Test fun productionAppliesOnlyToUnreconciledMultipleContractMonth() {
        val multi = contracts()
        assertTrue(V2SegmentedMonthlyBaseProduction.isApplicable(multi))

        val single = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(contract("one", 0, null, 10.0))
        )
        assertFalse(V2SegmentedMonthlyBaseProduction.isApplicable(single))
    }

    private fun source() = SegmentedProrationSourceV2(
        proration = ConfirmedSegmentedMonthlyProrationV2(
            sourceId = "planning-confirmed",
            checkedAtMs = 1,
            segments = listOf(
                ConfirmedProrationSegmentV2("contract-A", 0, 14, 4_200),
                ConfirmedProrationSegmentV2("contract-B", 15, 30, 4_200)
            )
        ),
        reliable = true,
        warnings = emptyList()
    )

    private fun contracts() = EmploymentContractPeriodResolverV2.resolve(
        employerId = "company",
        periodStartEpochDay = 0,
        periodEndEpochDay = 30,
        sourceReliable = true,
        snapshots = listOf(
            contract("contract-A", 0, 14, 10.0),
            contract("contract-B", 15, null, 20.0)
        )
    )

    private fun rules(vararg snapshots: ConventionRuleSnapshotV2) =
        ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = snapshots.toList()
        )

    private fun contract(
        version: String,
        from: Long,
        to: Long?,
        rate: Double
    ) = EmploymentContractSnapshotV2(
        versionId = version,
        sourceId = "contract-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        contract = ContractV2(
            id = version,
            employerId = "company",
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 35 * 60,
            grossHourlyRate = rate,
            hireDateEpochDay = 0
        ),
        checkedAtMs = 1,
        note = null
    )

    private fun rule(
        version: String,
        from: Long,
        to: Long?,
        regularMinutes: Int = 35 * 60
    ) = ConventionRuleSnapshotV2(
            idcc = "0292",
            versionId = version,
            sourceId = "rule-test",
            effectiveFromEpochDay = from,
            effectiveToEpochDay = to,
            rules = PayrollRulesV2(weeklyRegularMinutes = regularMinutes),
            checkedAtMs = 1
        )
}
