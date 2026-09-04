package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SicknessDailyAllowanceV2Test {
    @Test
    fun `calcule les ijss sur trois bulletins confirmes`() {
        val result = SicknessDailyAllowanceV2.calculate(
            absenceStart = LocalDate.of(2026, 9, 10),
            absenceEndExclusive = LocalDate.of(2026, 9, 17),
            confirmedGrossByMonth = mapOf(
                YearMonth.of(2026, 6) to 2000.0,
                YearMonth.of(2026, 7) to 2000.0,
                YearMonth.of(2026, 8) to 2000.0
            )
        )
        assertTrue(result.complete)
        assertEquals(32.8767, result.dailyGross!!, 0.001)
        assertEquals(4, result.payableDays)
        assertEquals(131.5068, result.estimatedGrossTotal!!, 0.01)
    }

    @Test
    fun `plafonne le salaire et l'ij journaliere en septembre 2026`() {
        val result = SicknessDailyAllowanceV2.calculate(
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 11),
            mapOf(
                YearMonth.of(2026, 6) to 4000.0,
                YearMonth.of(2026, 7) to 4000.0,
                YearMonth.of(2026, 8) to 4000.0
            )
        )
        assertTrue(result.complete)
        assertEquals(42.967, result.dailyGross!!, 0.01)
        assertEquals(7, result.payableDays)
    }

    @Test
    fun `trois jours ou moins restent sans ij dans le cas general`() {
        val result = SicknessDailyAllowanceV2.calculate(
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 4),
            mapOf(
                YearMonth.of(2026, 6) to 2000.0,
                YearMonth.of(2026, 7) to 2000.0,
                YearMonth.of(2026, 8) to 2000.0
            )
        )
        assertTrue(result.complete)
        assertEquals(0, result.payableDays)
        assertEquals(0.0, result.estimatedGrossTotal!!, 0.001)
    }

    @Test
    fun `refuse d'inventer un salaire de reference manquant`() {
        val result = SicknessDailyAllowanceV2.calculate(
            LocalDate.of(2026, 9, 10),
            LocalDate.of(2026, 9, 17),
            mapOf(
                YearMonth.of(2026, 6) to 2000.0,
                YearMonth.of(2026, 8) to 2000.0
            )
        )
        assertFalse(result.complete)
        assertEquals(null, result.dailyGross)
        assertTrue(result.warnings.any { it.contains("07/2026") })
    }

    @Test
    fun `n'integre pas silencieusement un ancien bareme inconnu`() {
        val result = SicknessDailyAllowanceV2.calculate(
            LocalDate.of(2026, 5, 10),
            LocalDate.of(2026, 5, 17),
            emptyMap()
        )
        assertFalse(result.complete)
        assertTrue(result.warnings.any { it.contains("barème HoraTrack non intégré") })
    }
}
