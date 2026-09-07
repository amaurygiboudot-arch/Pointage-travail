package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionMatterCoverageV2Test {
    private val date = LocalDate.of(2026, 9, 1)

    private fun record(state: ConventionMatterCoverageV2.State, checkedAt: Long = 1L) = ConventionMatterCoverageV2.Record(
        idcc = "1486",
        matter = ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        state = state,
        source = "Légifrance KALI",
        checkedAtMs = checkedAt
    )

    @Test
    fun `missing audit is incomplete and never means no rule`() {
        val result = ConventionMatterCoverageV2.resolve(
            emptyList(), "1486", ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM, date
        )

        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, result.state)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("non confirmée", ignoreCase = true) })
    }

    @Test
    fun `confirmed no rule is explicit reliable state`() {
        val result = ConventionMatterCoverageV2.resolve(
            listOf(record(ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE)),
            "01486",
            ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM,
            date
        )

        assertEquals(ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE, result.state)
        assertTrue(result.reliable)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `conflicting coverage states block automatic conclusion`() {
        val result = ConventionMatterCoverageV2.resolve(
            listOf(
                record(ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE, 1L),
                record(ConventionMatterCoverageV2.State.CONFIRMED_RULES, 2L)
            ),
            "1486",
            ConventionMatterCoverageV2.Matter.SENIORITY_PREMIUM,
            date
        )

        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, result.state)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("contradictoires", ignoreCase = true) })
    }

    @Test
    fun `cadre no-rule coverage does not leak to non-cadre`() {
        val cadreOnly = ConventionMatterCoverageV2.Record(
            idcc = "1486",
            matter = ConventionMatterCoverageV2.Matter.SICKNESS_MAINTENANCE,
            effectiveFrom = LocalDate.of(2026, 1, 1),
            professionalStatus = "CADRE",
            state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
            source = "Légifrance KALI",
            checkedAtMs = 3L
        )

        val cadre = ConventionMatterCoverageV2.resolve(
            listOf(cadreOnly), "1486", ConventionMatterCoverageV2.Matter.SICKNESS_MAINTENANCE,
            date, professionalStatus = "CADRE"
        )
        val nonCadre = ConventionMatterCoverageV2.resolve(
            listOf(cadreOnly), "1486", ConventionMatterCoverageV2.Matter.SICKNESS_MAINTENANCE,
            date, professionalStatus = "NON_CADRE"
        )

        assertEquals(ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE, cadre.state)
        assertTrue(cadre.reliable)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, nonCadre.state)
        assertFalse(nonCadre.reliable)
    }
}
