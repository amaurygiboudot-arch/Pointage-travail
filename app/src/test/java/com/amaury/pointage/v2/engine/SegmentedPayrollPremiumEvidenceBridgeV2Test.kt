package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedPayrollPremiumEvidenceBridgeV2Test {
    @Test
    fun noNightMultiplierDoesNotRequireNightStore() {
        val f = fixture()
        val result = SegmentedPayrollPremiumEvidenceBridgeV2.build(
            f.contracts, f.rules, emptyList(), false, listOf("night-store-down"),
            completeScope(), 10L
        )
        assertTrue(result.reliable)
        assertEquals(1, result.evidence.size)
        assertNull(result.evidence.single().nightRule)
    }

    @Test
    fun matchingNightSnapshotCoversWholeSlice() {
        val f = fixture(nightMultiplier = 1.25)
        val result = SegmentedPayrollPremiumEvidenceBridgeV2.build(
            f.contracts, f.rules,
            listOf(night("n1", 4, null, 1.25)),
            true, emptyList(), completeScope(), 10L
        )
        assertTrue(result.reliable)
        assertEquals(1.25, result.evidence.single().nightRule!!.multiplier, 0.0)
    }

    @Test
    fun nightRuleChangeInsideSliceBlocks() {
        val f = fixture(nightMultiplier = 1.25)
        val result = SegmentedPayrollPremiumEvidenceBridgeV2.build(
            f.contracts, f.rules,
            listOf(night("n1", 4, 6, 1.25), night("n2", 7, null, 1.25)),
            true, emptyList(), completeScope(), 10L
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.contains(
            SegmentedPayrollPremiumEvidenceBridgeV2.NIGHT_COVERAGE_WARNING
        ))
    }

    @Test
    fun incompleteHolidayScopeBlocks() {
        val f = fixture()
        val result = SegmentedPayrollPremiumEvidenceBridgeV2.build(
            f.contracts, f.rules, emptyList(), true, emptyList(),
            FrenchPublicHolidayCalendarV2.Scope(
                FrenchPublicHolidayCalendarV2.Jurisdiction.ADDRESS_UNKNOWN,
                false,
                warning = "scope-incomplete"
            ),
            10L
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.contains("scope-incomplete"))
        assertTrue(result.warnings.contains(
            SegmentedPayrollSessionEvidenceBuilderV2.HOLIDAY_WARNING
        ))
    }

    private data class Fixture(
        val contracts: EmploymentContractPeriodResolutionV2,
        val rules: ConventionRulePeriodResolutionV2
    )

    private fun fixture(nightMultiplier: Double? = null): Fixture {
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            "company", 4, 10, true,
            listOf(
                EmploymentContractSnapshotV2(
                    "c1", "contract-source", 4, null,
                    ContractV2(
                        "c1", "company", ContractTypeV2.FULL_TIME,
                        2100, 10.0, 0L
                    ),
                    1L, null
                )
            )
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            "0292", 4, 10, true,
            listOf(
                ConventionRuleSnapshotV2(
                    "0292", "r1", "rule-source", 4, null,
                    PayrollRulesV2(
                        weeklyRegularMinutes = 2100,
                        nightMultiplier = nightMultiplier
                    ),
                    1L
                )
            )
        )
        return Fixture(contracts, rules)
    }

    private fun night(
        version: String,
        from: Long,
        to: Long?,
        multiplier: Double
    ) = ConventionNightRuleSnapshotV2(
        "0292", version, "night-$version", from, to,
        NightPremiumRuleV2(21 * 60, 6 * 60, multiplier),
        1L
    )

    private fun completeScope() = FrenchPublicHolidayCalendarV2.Scope(
        FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE,
        true,
        "75001"
    )
}
