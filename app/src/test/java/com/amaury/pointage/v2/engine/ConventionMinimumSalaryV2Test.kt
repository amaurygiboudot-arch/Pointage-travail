package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionMinimumSalaryV2Test {
    private val date = LocalDate.of(2026, 9, 1)

    private fun rule(
        id: String,
        from: LocalDate,
        amount: Double,
        status: ConventionMinimumSalaryV2.ExtensionStatus,
        classification: ConventionClassificationV2 = ConventionClassificationV2(level = "III", echelon = "2")
    ) = ConventionMinimumSalaryV2.Rule(
        idcc = "1486",
        ruleId = id,
        effectiveFrom = from,
        classification = classification,
        amount = amount,
        periodicity = ConventionMinimumSalaryV2.Periodicity.MONTHLY,
        source = "Légifrance KALI $id",
        extensionStatus = status
    )

    @Test
    fun `extended arbitrary idcc rule is selected without plasturgie dependency`() {
        val result = ConventionMinimumSalaryV2.resolve(
            rules = listOf(rule("r1", LocalDate.of(2026, 1, 1), 2200.0, ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED)),
            idcc = "01486",
            date = date,
            classification = ConventionClassificationV2(level = "iii", echelon = "2")
        )

        assertTrue(result.reliable)
        assertEquals(2200.0, result.selected?.amount ?: 0.0, 0.001)
    }

    @Test
    fun `newer non extended rule does not silently replace extended rule`() {
        val result = ConventionMinimumSalaryV2.resolve(
            rules = listOf(
                rule("old", LocalDate.of(2025, 1, 1), 2100.0, ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED),
                rule("new", LocalDate.of(2026, 6, 1), 2300.0, ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED)
            ),
            idcc = "1486",
            date = date,
            classification = ConventionClassificationV2(level = "III", echelon = "2")
        )

        assertTrue(result.reliable)
        assertEquals(2100.0, result.selected?.amount ?: 0.0, 0.001)
        assertEquals(2300.0, result.latestKnown?.amount ?: 0.0, 0.001)
        assertTrue(result.warnings.any { it.contains("plus récent", ignoreCase = true) })
    }

    @Test
    fun `company confirmation allows non extended rule`() {
        val result = ConventionMinimumSalaryV2.resolve(
            rules = listOf(rule("new", LocalDate.of(2026, 6, 1), 2300.0, ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED)),
            idcc = "1486",
            date = date,
            classification = ConventionClassificationV2(level = "III", echelon = "2"),
            companyApplicabilityConfirmed = true
        )

        assertTrue(result.reliable)
        assertEquals(2300.0, result.selected?.amount ?: 0.0, 0.001)
    }

    @Test
    fun `equal conflicting rules block automatic minimum`() {
        val sameDate = LocalDate.of(2026, 1, 1)
        val result = ConventionMinimumSalaryV2.resolve(
            rules = listOf(
                rule("a", sameDate, 2200.0, ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED),
                rule("b", sameDate, 2250.0, ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED)
            ),
            idcc = "1486",
            date = date,
            classification = ConventionClassificationV2(level = "III", echelon = "2")
        )

        assertFalse(result.reliable)
        assertNull(result.selected)
        assertTrue(result.warnings.any { it.contains("se contredisent", ignoreCase = true) })
    }

    @Test
    fun `more specific classification wins on same date`() {
        val sameDate = LocalDate.of(2026, 1, 1)
        val result = ConventionMinimumSalaryV2.resolve(
            rules = listOf(
                rule("level", sameDate, 2100.0, ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED, ConventionClassificationV2(level = "III")),
                rule("echelon", sameDate, 2250.0, ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED, ConventionClassificationV2(level = "III", echelon = "2"))
            ),
            idcc = "1486",
            date = date,
            classification = ConventionClassificationV2(level = "III", echelon = "2")
        )

        assertTrue(result.reliable)
        assertEquals(2250.0, result.selected?.amount ?: 0.0, 0.001)
    }
}
