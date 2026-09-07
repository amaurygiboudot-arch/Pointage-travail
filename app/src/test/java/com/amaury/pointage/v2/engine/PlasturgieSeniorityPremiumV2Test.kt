package com.amaury.pointage.v2.engine

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlasturgieSeniorityPremiumV2Test {
    private val reference = LocalDate.of(2026, 9, 30)

    @Test
    fun `coefficient 800 a trois ans applique 2 virgule 4 pourcent`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            idcc = "0292",
            coefficient = 800,
            referenceDate = reference,
            confirmedSeniorityDate = LocalDate.of(2023, 9, 1),
            monthlyBaseGross = 2200.0,
            monthlyRttDifferential = 0.0
        )
        assertTrue(result.applicable)
        assertTrue(result.reliable)
        assertEquals(3, result.stepYears)
        assertEquals(0.024, result.rate!!, 0.000001)
        assertEquals(52.8, result.monthlyAmount!!, 0.001)
    }

    @Test
    fun `les paliers utilisent 3 6 9 12 et 15 ans`() {
        val expectations = mapOf(3 to 0.024, 6 to 0.048, 9 to 0.072, 12 to 0.096, 15 to 0.12)
        expectations.forEach { (years, expectedRate) ->
            val result = PlasturgieSeniorityPremiumV2.calculate(
                "292", 800, reference,
                reference.minusYears(years.toLong()).withDayOfMonth(1),
                2000.0, 0.0
            )
            assertEquals(years, result.stepYears)
            assertEquals(expectedRate, result.rate!!, 0.000001)
        }
    }

    @Test
    fun `moins de trois ans confirme zero sans exiger la base`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            "292", 800, reference,
            LocalDate.of(2024, 1, 1),
            monthlyBaseGross = null,
            monthlyRttDifferential = null
        )
        assertTrue(result.reliable)
        assertEquals(0.0, result.monthlyAmount!!, 0.001)
    }

    @Test
    fun `coefficient cadre 900 exclut la prime collaborateurs`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            "292", 900, reference,
            null, null, null
        )
        assertFalse(result.applicable)
        assertTrue(result.reliable)
    }

    @Test
    fun `coefficient 830 reste dans avenant collaborateurs`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            "292", 830, reference,
            LocalDate.of(2020, 1, 1),
            3000.0, 0.0
        )
        assertTrue(result.applicable)
        assertTrue(result.reliable)
        assertEquals(6, result.stepYears)
    }

    @Test
    fun `date anciennete non confirmee bloque le brut fiable`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            "292", 800, reference,
            null, 2200.0, 0.0
        )
        assertFalse(result.reliable)
        assertNull(result.monthlyAmount)
        assertTrue(result.warnings.any { it.contains("date d'ancienneté conventionnelle") })
    }

    @Test
    fun `differentiel rtt doit etre explicitement confirme meme a zero`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            "292", 800, reference,
            LocalDate.of(2020, 1, 1),
            2200.0, null
        )
        assertFalse(result.reliable)
        assertNull(result.monthlyAmount)
        assertTrue(result.warnings.any { it.contains("différentiel RTT") })
    }

    @Test
    fun `differentiel rtt confirme entre dans la base`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            "292", 800, reference,
            LocalDate.of(2020, 1, 1),
            2000.0, 100.0
        )
        assertTrue(result.reliable)
        assertEquals(100.8, result.monthlyAmount!!, 0.001)
    }

    @Test
    fun `changement de palier au milieu du mois ne fabrique pas une mensualite`() {
        val result = PlasturgieSeniorityPremiumV2.calculate(
            "292", 800, LocalDate.of(2026, 9, 30),
            LocalDate.of(2023, 9, 15),
            2200.0, 0.0
        )
        assertFalse(result.reliable)
        assertNull(result.monthlyAmount)
        assertTrue(result.warnings.any { it.contains("change pendant ce mois") })
    }
}
