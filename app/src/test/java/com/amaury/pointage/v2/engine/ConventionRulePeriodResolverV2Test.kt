package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConventionRulePeriodResolverV2Test {
    @Test
    fun `une version couvrant toute la periode est directement calculable`() {
        val result = ConventionRulePeriodResolverV2.resolve(
            idcc = "292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(rule("r1", 0, null))
        )

        assertTrue(result.readyForCalculation)
        assertFalse(result.requiresMultipleRuleVersions)
        assertEquals("0292", result.idcc)
        assertEquals(1, result.calculationSegments.size)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `un idcc absent bloque proprement sans inventer de convention`() {
        val result = ConventionRulePeriodResolverV2.resolve(
            idcc = "   ",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(rule("r1", 0, null))
        )

        assertFalse(result.readyForCalculation)
        assertFalse(result.sourceReliable)
        assertEquals("", result.idcc)
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.MISSING_IDCC_WARNING))
    }

    @Test
    fun `deux versions successives sont exposees sans etre aplaties`() {
        val result = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                rule("r1", 0, 14),
                rule("r2", 15, null)
            )
        )

        assertTrue(result.readyForCalculation)
        assertTrue(result.requiresMultipleRuleVersions)
        assertEquals(listOf(0L to 14L, 15L to 30L), result.calculationSegments.map { it.startEpochDay to it.endEpochDay })
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.MULTIPLE_WARNING))
    }

    @Test
    fun `un trou de couverture bloque tous les segments calculables`() {
        val result = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                rule("r1", 0, 10),
                rule("r2", 12, null)
            )
        )

        assertFalse(result.readyForCalculation)
        assertTrue(result.calculationSegments.isEmpty())
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.INCOMPLETE_WARNING))
    }

    @Test
    fun `une source non fiable bloque meme une version valide`() {
        val result = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = false,
            snapshots = listOf(rule("r1", 0, null))
        )

        assertFalse(result.readyForCalculation)
        assertFalse(result.sourceReliable)
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.UNRELIABLE_WARNING))
    }

    @Test
    fun `des versions qui se chevauchent rendent la resolution non fiable`() {
        val result = ConventionRulePeriodResolverV2.resolve(
            idcc = "0292",
            periodStartEpochDay = 0,
            periodEndEpochDay = 30,
            sourceReliable = true,
            snapshots = listOf(
                rule("r1", 0, 20),
                rule("r2", 15, null)
            )
        )

        assertFalse(result.readyForCalculation)
        assertFalse(result.sourceReliable)
        assertTrue(result.warnings.contains(ConventionRulePeriodResolverV2.UNRELIABLE_WARNING))
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
