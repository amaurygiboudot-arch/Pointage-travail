package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionRulePeriodResolverV2
import com.amaury.pointage.v2.engine.ConventionRuleSnapshotV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionRulePayrollBridgeTest {
    @Test
    fun `un mois complet conserve les changements de version conventionnelle`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val change = LocalDate.of(2026, 1, 16).toEpochDay()
        val stored = V2ConventionRuleStore.ReadResult(
            snapshots = listOf(
                rule("r1", start - 20, change - 1),
                rule("r2", change, null)
            ),
            reliable = true,
            warnings = emptyList()
        )

        val result = V2ConventionRulePayrollBridge.resolveStored(
            stored = stored,
            idcc = "292",
            year = 2026,
            monthZeroBased = 0
        )

        assertTrue(result.resolution.readyForCalculation)
        assertTrue(result.resolution.requiresMultipleRuleVersions)
        assertEquals("0292", result.resolution.idcc)
        assertEquals(2, result.resolution.calculationSegments.size)
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.MULTIPLE_WARNING))
    }

    @Test
    fun `un stockage non fiable bloque toute regle`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val stored = V2ConventionRuleStore.ReadResult(
            snapshots = listOf(rule("r1", start - 20, null)),
            reliable = false,
            warnings = listOf("stockage test non fiable")
        )

        val result = V2ConventionRulePayrollBridge.resolveStored(
            stored = stored,
            idcc = "0292",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.resolution.readyForCalculation)
        assertTrue(result.resolution.calculationSegments.isEmpty())
        assertTrue(result.warnings.contains("stockage test non fiable"))
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.UNRELIABLE_WARNING))
    }

    @Test
    fun `un trou dans le mois reste incomplet sans fallback`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val stored = V2ConventionRuleStore.ReadResult(
            snapshots = listOf(rule("r1", start + 1, null)),
            reliable = true,
            warnings = emptyList()
        )

        val result = V2ConventionRulePayrollBridge.resolveStored(
            stored = stored,
            idcc = "0292",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.resolution.readyForCalculation)
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.INCOMPLETE_WARNING))
    }

    @Test
    fun `un idcc absent reste bloque sans exception`() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val stored = V2ConventionRuleStore.ReadResult(
            snapshots = listOf(rule("r1", start - 20, null)),
            reliable = true,
            warnings = emptyList()
        )

        val result = V2ConventionRulePayrollBridge.resolveStored(
            stored = stored,
            idcc = "   ",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.resolution.readyForCalculation)
        assertTrue(result.resolution.sourceReliable)
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.MISSING_IDCC_WARNING))
    }

    private fun rule(version: String, from: Long, to: Long?) = ConventionRuleSnapshotV2(
        idcc = "0292",
        versionId = version,
        sourceId = "KALI-test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        rules = PayrollRulesV2(weeklyRegularMinutes = 35 * 60),
        checkedAtMs = 1L
    )
}
