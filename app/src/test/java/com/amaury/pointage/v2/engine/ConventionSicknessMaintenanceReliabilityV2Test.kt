package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceSourceStateV2
import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ConventionSicknessMaintenanceReliabilityV2Test {
    private val zone = ZoneId.of("Europe/Paris")

    private class StoredAbsences(
        private val values: List<AbsenceV2>,
        override val absenceSourceReliable: Boolean,
        override val absenceSourceWarnings: List<String>
    ) : AbstractList<AbsenceV2>(), AbsenceSourceStateV2 {
        override val size: Int get() = values.size
        override fun get(index: Int): AbsenceV2 = values[index]
    }

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
    fun `un historique persistant non fiable ne peut pas certifier les jours deja consommes`() {
        val current = sickness("current", LocalDate.of(2026, 9, 10), 10)
        val absences = StoredAbsences(
            values = listOf(current),
            absenceSourceReliable = false,
            absenceSourceWarnings = listOf("Absences : paquet local corrompu")
        )

        val result = ConventionSicknessMaintenanceV2.calculate(
            rules = PlasturgieSicknessRulesV2.rules(),
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 800),
            professionalStatus = "NON_CADRE",
            currentAbsence = current,
            allAbsences = absences,
            entryDate = LocalDate.of(2022, 5, 1),
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone
        )

        assertFalse(result.reliable)
        assertTrue(result.applicable)
        assertFalse(result.eligibilityConfirmed)
        assertNotNull(result.selectedRule)
        assertNull(result.alreadyConsumedIndemnifiedDays)
        assertNull(result.currentIndemnifiableDays)
        assertTrue(result.warnings.any { it.contains("paquet local corrompu") })
        assertTrue(result.warnings.any { it.contains("historique", ignoreCase = true) })
    }

    @Test
    fun `une liste ordinaire reste compatible avec le moteur pur`() {
        val current = sickness("current", LocalDate.of(2026, 9, 10), 10)

        val result = ConventionSicknessMaintenanceV2.calculate(
            rules = PlasturgieSicknessRulesV2.rules(),
            idcc = "292",
            classification = ConventionClassificationV2(coefficient = 800),
            professionalStatus = "NON_CADRE",
            currentAbsence = current,
            allAbsences = listOf(current),
            entryDate = LocalDate.of(2022, 5, 1),
            acceptedEmployerIds = setOf("company-a"),
            zoneId = zone
        )

        assertTrue(result.reliable)
    }
}
