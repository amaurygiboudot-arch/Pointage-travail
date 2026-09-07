package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ConventionSicknessMaintenanceV2Test {
    private val zone = ZoneId.of("Europe/Paris")

    private fun sickness(id: String, start: LocalDate, days: Int) = AbsenceV2(
        id = id,
        employerId = "company-a",
        type = AbsencePayrollImpactV2.TYPE_SICKNESS,
        startMs = start.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMs = start.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
        fullDay = true,
        status = DecisionStatusV2.CONFIRMED
    )

    @Test
    fun `plasturgie non cadre preserves validated collaborator schedule`() {
        val first = sickness("first", LocalDate.of(2026, 2, 1), 45)
        val current = sickness("current", LocalDate.of(2026, 9, 10), 10)
        val result = ConventionSicknessMaintenanceV2.calculate(
            rules = PlasturgieSicknessRulesV2.rules(),
            idcc = "0292",
            classification = ConventionClassificationV2(coefficient = 800),
            professionalStatus = "NON_CADRE",
            currentAbsence = current,
            allAbsences = listOf(first, current),
            entryDate = LocalDate.of(2022, 5, 1),
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone
        )

        assertTrue(result.reliable)
        assertEquals(3, result.employerWaitingDays)
        assertEquals(45, result.alreadyConsumedIndemnifiedDays)
        assertEquals(7, result.currentIndemnifiableDays)
        assertEquals(0.75, result.bands.single().targetNetRate, 0.0001)
        assertTrue(result.socialSecurityCoverageRequired)
    }

    @Test
    fun `plasturgie cadre uses cadre schedule instead of collaborator schedule`() {
        val current = sickness("current", LocalDate.of(2026, 9, 10), 100)
        val result = ConventionSicknessMaintenanceV2.calculate(
            rules = PlasturgieSicknessRulesV2.rules(),
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 900),
            professionalStatus = "CADRE",
            currentAbsence = current,
            allAbsences = listOf(current),
            entryDate = LocalDate.of(2025, 1, 1),
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone
        )

        assertTrue(result.reliable)
        assertEquals(0, result.employerWaitingDays)
        assertEquals(90, result.annualLimitDays)
        assertEquals(90, result.currentIndemnifiableDays)
        assertEquals(45, result.bands[0].calendarDays)
        assertEquals(1.0, result.bands[0].targetNetRate, 0.0001)
        assertEquals(45, result.bands[1].calendarDays)
        assertEquals(0.50, result.bands[1].targetNetRate, 0.0001)
    }

    @Test
    fun `three year cadre receives ninety plus ninety days`() {
        val current = sickness("current", LocalDate.of(2026, 9, 10), 180)
        val result = ConventionSicknessMaintenanceV2.calculate(
            rules = PlasturgieSicknessRulesV2.rules(),
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 920),
            professionalStatus = "CADRE",
            currentAbsence = current,
            allAbsences = listOf(current),
            entryDate = LocalDate.of(2023, 1, 1),
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone
        )

        assertEquals(180, result.annualLimitDays)
        assertEquals(90, result.bands[0].calendarDays)
        assertEquals(90, result.bands[1].calendarDays)
    }

    @Test
    fun `missing professional status blocks plasturgie selection`() {
        val current = sickness("current", LocalDate.of(2026, 9, 10), 10)
        val result = ConventionSicknessMaintenanceV2.calculate(
            rules = PlasturgieSicknessRulesV2.rules(),
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 800),
            professionalStatus = null,
            currentAbsence = current,
            allAbsences = listOf(current),
            entryDate = LocalDate.of(2020, 1, 1),
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("statut", ignoreCase = true) || it.contains("barème", ignoreCase = true) })
    }
}
