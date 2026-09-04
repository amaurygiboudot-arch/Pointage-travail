package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
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
        employerId: String = "company-a",
        type: String = AbsencePayrollImpactV2.TYPE_UNPAID
    ) = AbsenceV2(
        id = "a-${start}-$type",
        employerId = employerId,
        type = type,
        startMs = start.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMs = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        salaryTreatment = treatment,
        fullDay = fullDay,
        status = status
    )

    private fun session(day: LocalDate, employerId: String = "company-a"): WorkSessionV2 {
        val start = day.atTime(5, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(13, 0).atZone(zone).toInstant().toEpochMilli()
        return WorkSessionV2(
            id = "s-$day",
            employerId = employerId,
            realArrivalMs = start,
            countedEntryMs = start,
            countedExitMs = end,
            realExitMs = end
        )
    }

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
        assertTrue(result.requiresPayrollReview)
    }

    @Test
    fun `jour avec pointage est exclu du prorata absence complete`() {
        val result = AbsencePayrollImpactV2.forMonth(
            absences = listOf(absence(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9))),
            referenceDate = reference,
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone,
            workSessions = listOf(session(LocalDate.of(2026, 9, 8)))
        )
        assertEquals(2, result.unpaidFullCalendarDays)
        assertTrue(result.hasUnpaidAbsence)
        assertTrue(result.warnings.any { it.contains("pointage le 08/09/2026") })
    }

    @Test
    fun `pointage autre entreprise ne bloque pas l'absence`() {
        val result = AbsencePayrollImpactV2.forMonth(
            absences = listOf(absence(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 8))),
            referenceDate = reference,
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone,
            workSessions = listOf(session(LocalDate.of(2026, 9, 8), employerId = "company-b"))
        )
        assertEquals(1, result.unpaidFullCalendarDays)
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
        assertFalse(result.requiresPayrollReview)
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
        assertFalse(result.requiresPayrollReview)
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
        assertTrue(result.requiresPayrollReview)
        assertTrue(result.warnings.any { it.contains("partielle") })
    }

    @Test
    fun `absence partielle de quelques heures le meme jour est detectee sans prorata plafond`() {
        val start = LocalDateTime.of(2026, 9, 14, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2026, 9, 14, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val partial = AbsenceV2(
            id = "partial",
            employerId = "company-a",
            type = AbsencePayrollImpactV2.TYPE_UNPAID,
            startMs = start,
            endMs = end,
            salaryTreatment = AbsenceSalaryTreatmentV2.UNPAID,
            fullDay = false,
            status = DecisionStatusV2.CONFIRMED
        )
        val result = AbsencePayrollImpactV2.forMonth(listOf(partial), reference, setOf("company-a"), zone)
        assertEquals(0, result.unpaidFullCalendarDays)
        assertTrue(result.hasUnpaidAbsence)
        assertTrue(result.warnings.any { it.contains("partielle") })
    }

    @Test
    fun `absence maintenue non remuneree ne devient pas retenue`() {
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
    fun `conge paye maintenu ne reduit pas le plafond mais bloque le brut precis`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(
                absence(
                    LocalDate.of(2026, 9, 14),
                    LocalDate.of(2026, 9, 18),
                    treatment = AbsenceSalaryTreatmentV2.FULLY_MAINTAINED,
                    type = AbsencePayrollImpactV2.TYPE_PAID_LEAVE
                )
            ),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(0, result.unpaidFullCalendarDays)
        assertFalse(result.hasUnpaidAbsence)
        assertTrue(result.hasCompensatedAbsence)
        assertTrue(result.requiresPayrollReview)
        assertTrue(result.warnings.any { it.contains("Congé payé") })
    }

    @Test
    fun `arret maladie a confirmer bloque la paie sans inventer de retenue`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(
                absence(
                    LocalDate.of(2026, 9, 21),
                    LocalDate.of(2026, 9, 23),
                    treatment = AbsenceSalaryTreatmentV2.TO_CONFIRM,
                    type = AbsencePayrollImpactV2.TYPE_SICKNESS
                )
            ),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(0, result.unpaidFullCalendarDays)
        assertFalse(result.hasUnpaidAbsence)
        assertTrue(result.requiresPayrollReview)
        assertTrue(result.warnings.any { it.contains("Arrêt maladie") })
    }

    @Test
    fun `arret maladie sans maintien garde le prorata plafond et signale les IJSS`() {
        val result = AbsencePayrollImpactV2.forMonth(
            listOf(
                absence(
                    LocalDate.of(2026, 9, 21),
                    LocalDate.of(2026, 9, 23),
                    treatment = AbsenceSalaryTreatmentV2.UNPAID,
                    type = AbsencePayrollImpactV2.TYPE_SICKNESS
                )
            ),
            reference,
            setOf("company-a"),
            zone
        )
        assertEquals(3, result.unpaidFullCalendarDays)
        assertTrue(result.hasUnpaidAbsence)
        assertTrue(result.requiresPayrollReview)
        assertTrue(result.warnings.any { it.contains("IJSS") })
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
        assertFalse(result.requiresPayrollReview)
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
        assertTrue(result.requiresPayrollReview)
        assertTrue(result.warnings.any { it.contains("à confirmer") })
    }
}
