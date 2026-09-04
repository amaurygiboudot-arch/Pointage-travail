package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Impact paie minimal et certain des absences V2.
 *
 * Seules les journées complètes, confirmées et explicitement non rémunérées
 * réduisent le nombre de jours rémunérés utilisé pour le plafond SS.
 * Une absence partielle n'est jamais convertie en journée entière.
 */
object AbsencePayrollImpactV2 {
    data class Snapshot(
        val unpaidFullCalendarDays: Int,
        val hasUnpaidAbsence: Boolean,
        val warnings: List<String>
    )

    fun forMonth(
        absences: List<AbsenceV2>,
        referenceDate: LocalDate,
        acceptedEmployerIds: Set<String>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Snapshot {
        val monthStart = referenceDate.withDayOfMonth(1)
        val monthEndExclusive = monthStart.plusMonths(1)
        val monthStartMs = monthStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val monthEndMs = monthEndExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val unpaidDays = linkedSetOf<LocalDate>()
        var hasUnpaid = false
        val warnings = mutableListOf<String>()

        absences.forEach { absence ->
            if (acceptedEmployerIds.isNotEmpty() && absence.employerId !in acceptedEmployerIds) return@forEach
            if (absence.endMs <= absence.startMs) return@forEach
            if (absence.startMs >= monthEndMs || absence.endMs <= monthStartMs) return@forEach

            if (absence.status != DecisionStatusV2.CONFIRMED) {
                warnings += "Absence à confirmer sur cette période : aucun impact automatique sur la paie."
                return@forEach
            }
            if (absence.salaryTreatment != AbsenceSalaryTreatmentV2.UNPAID) return@forEach

            hasUnpaid = true
            if (!absence.fullDay) {
                warnings += "Absence non rémunérée partielle : le plafond SS n'est pas réduit automatiquement."
                return@forEach
            }

            val startDate = Instant.ofEpochMilli(absence.startMs).atZone(zoneId).toLocalDate()
            val endExclusiveDate = Instant.ofEpochMilli(absence.endMs).atZone(zoneId).toLocalDate()
            var day = maxOf(startDate, monthStart)
            val lastExclusive = minOf(endExclusiveDate, monthEndExclusive)
            while (day.isBefore(lastExclusive)) {
                unpaidDays += day
                day = day.plusDays(1)
            }
        }

        if (unpaidDays.isNotEmpty()) {
            warnings += "${unpaidDays.size} jour(s) d'absence non rémunérée complète pris en compte pour le plafond SS."
        }

        return Snapshot(
            unpaidFullCalendarDays = unpaidDays.size,
            hasUnpaidAbsence = hasUnpaid,
            warnings = warnings.distinct()
        )
    }
}
