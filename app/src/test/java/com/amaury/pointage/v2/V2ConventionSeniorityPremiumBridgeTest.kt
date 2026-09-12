package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionSeniorityPremiumBridgeTest {
    private val referenceDate = LocalDate.of(2026, 9, 1)
    private val classification = ConventionClassificationV2(coefficient = 800)

    @Test
    fun `empty reliable store never activates a built-in seniority rule`() {
        val selection = V2ConventionSeniorityPremiumBridge.selectOfficialRuntimeSource(
            stored = V2ConventionSeniorityPremiumStore.ReadResult(
                rules = emptyList(),
                reliable = true,
                warnings = emptyList()
            ),
            coverage = ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.INCOMPLETE,
                record = null,
                reliable = false,
                warnings = emptyList()
            ),
            idcc = "0292",
            classification = classification,
            referenceDate = referenceDate,
            accoKnowledge = PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
        )

        assertFalse(selection.reliable)
        assertFalse(selection.confirmedNoRule)
        assertTrue(selection.rules.isEmpty())
    }

    @Test
    fun `stored monetary rule requires both confirmed KALI and confirmed ACCO absence`() {
        val rule = seniorityRule()
        val stored = V2ConventionSeniorityPremiumStore.ReadResult(
            rules = listOf(rule),
            reliable = true,
            warnings = emptyList()
        )
        val unprovenCoverage = coverage(
            state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
            authorities = emptySet()
        )

        val blockedByKali = V2ConventionSeniorityPremiumBridge.selectOfficialRuntimeSource(
            stored = stored,
            coverage = unprovenCoverage,
            idcc = "292",
            classification = classification,
            referenceDate = referenceDate,
            accoKnowledge = PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
        )
        assertFalse(blockedByKali.reliable)
        assertTrue(blockedByKali.rules.isEmpty())

        val confirmedKaliCoverage = coverage(
            state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
            authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
        )
        val blockedByAcco = V2ConventionSeniorityPremiumBridge.selectOfficialRuntimeSource(
            stored = stored,
            coverage = confirmedKaliCoverage,
            idcc = "292",
            classification = classification,
            referenceDate = referenceDate,
            accoKnowledge = PayrollLegalArbitratorV2.Knowledge.UNKNOWN
        )
        assertFalse(blockedByAcco.reliable)
        assertTrue(blockedByAcco.rules.isEmpty())

        val confirmed = V2ConventionSeniorityPremiumBridge.selectOfficialRuntimeSource(
            stored = stored,
            coverage = confirmedKaliCoverage,
            idcc = "292",
            classification = classification,
            referenceDate = referenceDate,
            accoKnowledge = PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
        )
        assertTrue(confirmed.reliable)
        assertFalse(confirmed.confirmedNoRule)
        assertEquals(listOf(rule), confirmed.rules)
    }

    @Test
    fun `zero seniority right also requires confirmed ACCO absence`() {
        val stored = V2ConventionSeniorityPremiumStore.ReadResult(
            rules = emptyList(),
            reliable = true,
            warnings = emptyList()
        )
        val confirmedNoRuleCoverage = coverage(
            state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
            authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
        )

        val blocked = V2ConventionSeniorityPremiumBridge.selectOfficialRuntimeSource(
            stored = stored,
            coverage = confirmedNoRuleCoverage,
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 900),
            referenceDate = referenceDate,
            accoKnowledge = PayrollLegalArbitratorV2.Knowledge.UNKNOWN
        )
        assertFalse(blocked.reliable)
        assertFalse(blocked.confirmedNoRule)

        val confirmed = V2ConventionSeniorityPremiumBridge.selectOfficialRuntimeSource(
            stored = stored,
            coverage = confirmedNoRuleCoverage,
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 900),
            referenceDate = referenceDate,
            accoKnowledge = PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
        )
        assertTrue(confirmed.reliable)
        assertTrue(confirmed.confirmedNoRule)
        assertTrue(confirmed.rules.isEmpty())
    }

    @Test
    fun `corrupt source knowledge blocks seniority even when ACCO value says absent`() {
        val selection = V2ConventionSeniorityPremiumBridge.selectOfficialRuntimeSource(
            stored = V2ConventionSeniorityPremiumStore.ReadResult(
                rules = listOf(seniorityRule()),
                reliable = true,
                warnings = emptyList()
            ),
            coverage = coverage(
                state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
                authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
            ),
            idcc = "292",
            classification = classification,
            referenceDate = referenceDate,
            accoKnowledge = PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE,
            sourceKnowledgeReliable = false,
            sourceKnowledgeWarnings = listOf("stockage source incohérent")
        )

        assertFalse(selection.reliable)
        assertTrue(selection.rules.isEmpty())
        assertTrue(selection.warnings.any { it.contains("stockage source incohérent") })
    }

    private fun seniorityRule() = ConventionSeniorityPremiumV2.Rule(
        idcc = "292",
        ruleId = "kali_292_seniority_800",
        effectiveFrom = LocalDate.of(2011, 6, 28),
        classification = classification,
        basis = ConventionSeniorityPremiumV2.Basis.ACTUAL_MONTHLY_BASE,
        steps = listOf(ConventionSeniorityPremiumV2.Step(years = 3, rate = 0.024)),
        includeConfirmedMonthlySupplement = true,
        source = "Légifrance KALI — règle vérifiée",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2012, 1, 5)
    )

    private fun coverage(
        state: ConventionMatterCoverageV2.State,
        authorities: Set<ConventionMatterCoverageV2.Authority>
    ): ConventionMatterCoverageV2.Snapshot {
        val record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM,
            effectiveFrom = referenceDate.withDayOfMonth(1),
            effectiveTo = referenceDate.withDayOfMonth(referenceDate.lengthOfMonth()),
            classification = classification,
            state = state,
            source = "Légifrance KALI — audit ancienneté",
            checkedAtMs = 1L,
            authorities = authorities
        )
        return ConventionMatterCoverageV2.Snapshot(
            state = state,
            record = record,
            reliable = state != ConventionMatterCoverageV2.State.INCOMPLETE,
            warnings = emptyList()
        )
    }
}
