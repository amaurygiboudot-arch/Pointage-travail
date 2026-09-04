package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AbsencePayrollImpactV2Test {
    private val zone = ZoneId.of("Europe/Paris")
    private val reference = LocalDate.of(2026, 9, 30)

    private fun absence(
        start: LocalDate,
        endInclusive: LocalDate,
        treatment: AbsenceSalaryTreatmentV2 = AbsenceSalaryTreatmentV2.UNPAID,
        fullDay: Boolean = true,
        status: DecisionStatusV2 = DecisionStatusV2.CONFIRMED,
        employerId: String = "company-a"
    ) = AbsenceV2(
        id = "a-${start}",
        employerId = employerId,
        type = "ABSENCE_NON_REMUNEREE",
        startMs = start.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMs = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        salaryTreatment = treatment,
        fullDay = fullDay,
        status = status
    )

    @Test
    fun `compte uniquement les jours complets non remuneres du mois`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9))),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(3, result.unpaidFullCalendarDays)
        assertTrue(result.hasUnpaidAbsence)
    }

    @Test
    fun `dedoublonne deux absences qui se chevauchent`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(
                absence(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9)),
                absence(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
            ),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(4, result.unpaidFullCalendarDays)
    }

    @Test
    fun `absence qui traverse deux mois ne compte que septembre`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2))),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(2, result.unpaidFullCalendarDays)
        assertTrue(result.hasUnpaidAbsence)
    }

    @Test
    fun `absence d'un autre mois est totalement ignoree`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 9))),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(0, result.unpaidFullCalendarDays)
        assertFalse(result.hasUnpaidAbsence)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `absence non confirmee d'un autre mois ne cree pas d'alerte`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 7), status = DecisionStatusV2.TO_CONFIRM)),
            reference,
            setOf("company-a"),
            zone
        )
        assertFalse(result.hasUnpaidAbsence)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `absence partielle ne reduit pas le plafond`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7), fullDay = false)),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(0, result.unpaidFullCalendarDays)
        assertTrue(result.hasUnpaidAbsence)
        assertTrue(result.warnings.any { it.contains("partielle") })
    }

    @Test
    fun `absence maintenue ne devient pas non remuneree`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7), treatment = AbsenceSalaryTreatmentV2.FULLY_MAINTAINED)),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(0, result.unpaidFullCalendarDays)
        assertFalse(result.hasUnpaidAbsence)
    }

    @Test
    fun `absence autre entreprise est ignoree`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7), employerId = "company-b")),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(0, result.unpaidFullCalendarDays)
        assertFalse(result.hasUnpaidAbsence)
    }

    @Test
    fun `absence non confirmee n'impacte pas automatiquement la paie`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(absence(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7), status = DecisionStatusV2.TO_CONFIRM)),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(0, result.unpaidFullCalendarDays)
        assertFalse(result.hasUnpaidAbsence)
        assertTrue(result.warnings.any { it.contains("à confirmer") })
    }
}
