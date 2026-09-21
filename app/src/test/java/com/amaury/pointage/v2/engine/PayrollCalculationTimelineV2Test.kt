package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayrollCalculationTimelineV2Test {
    @Test
    fun `les changements contrat et regle produisent les tranches minimales exactes`() {
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                contract("c1", 0, 14, 12.0),
                contract("c2", 15, null, 13.0)
            )
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                rule("r1", 0, 19),
                rule("r2", 20, null)
            )
        )

        val result = PayrollCalculationTimelineV2.align(contracts, rules)

        assertTrue(result.reliable)
        assertEquals(3, result.slices.size)
        assertEquals(listOf(
            SliceExpectation(0, 14, "c1", "r1"),
            SliceExpectation(15, 19, "c2", "r1"),
            SliceExpectation(20, 30, "c2", "r2")
        ), result.slices.map {
            SliceExpectation(it.startEpochDay, it.endEpochDay, it.contractVersionId, it.ruleVersionId)
        })
    }

    @Test
    fun `une regle qui change au meme jour que le contrat ne cree pas de tranche artificielle`() {
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                contract("c1", 0, 14, 12.0),
                contract("c2", 15, null, 13.0)
            )
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                rule("r1", 0, 14),
                rule("r2", 15, null)
            )
        )

        val result = PayrollCalculationTimelineV2.align(contracts, rules)

        assertTrue(result.reliable)
        assertEquals(2, result.slices.size)
        assertEquals("c1", result.slices[0].contractVersionId)
        assertEquals("r1", result.slices[0].ruleVersionId)
        assertEquals("c2", result.slices[1].contractVersionId)
        assertEquals("r2", result.slices[1].ruleVersionId)
    }

    @Test
    fun `des periodes differentes entre contrat et regles bloquent le calcul`() {
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(contract("c1", 0, null, 12.0))
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 1,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(rule("r1", 1, null))
        )

        val result = PayrollCalculationTimelineV2.align(contracts, rules)

        assertFalse(result.reliable)
        assertTrue(result.slices.isEmpty())
        assertTrue(result.warnings.contains(PayrollCalculationTimelineV2.PERIOD_MISMATCH_WARNING))
    }

    @Test
    fun `un trou dans les regles bloque lintersection meme si le contrat est complet`() {
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(contract("c1", 0, null, 12.0))
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                rule("r1", 0, 10),
                rule("r2", 12, null)
            )
        )

        val result = PayrollCalculationTimelineV2.align(contracts, rules)

        assertFalse(result.reliable)
        assertTrue(result.slices.isEmpty())
        assertTrue(result.warnings.contains(PayrollCalculationTimelineV2.INCOMPLETE_WARNING))
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.INCOMPLETE_WARNING))
    }

    @Test
    fun `une source contractuelle non fiable bloque toutes les tranches`() {
        val contracts = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = false,
            snapshots = listOf(contract("c1", 0, null, 12.0))
        )
        val rules = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(rule("r1", 0, null))
        )

        val result = PayrollCalculationTimelineV2.align(contracts, rules)

        assertFalse(result.reliable)
        assertTrue(result.slices.isEmpty())
        assertTrue(result.warnings.contains(PayrollCalculationTimelineV2.UNRELIABLE_WARNING))
        assertTrue(result.warnings.contains(EmploymentContractPeriodResolverV2.UNRELIABLE_WARNING))
    }

    private data class SliceExpectation(
        val start: Long,
        val end: Long,
        val contractVersion: String,
        val ruleVersion: String
    )

    private fun contract(version: String, from: Long, to: Long?, rate: Double) = EmploymentContractSnapshotV2(
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
            hireDateEpochDay = 0L
        ),
        checkedAtMs = 1L,
        note = null
    )

    private fun rule(version: String, from: Long, to: Long?) = ConventionRuleSnapshotV2(
        idcc = "0292",
        versionId = version,
        sourceId = "rule-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        rules = PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
        checkedAtMs = 1L
    )
}
