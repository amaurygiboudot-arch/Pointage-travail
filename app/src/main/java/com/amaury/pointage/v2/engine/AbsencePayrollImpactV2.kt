package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Impact paie minimal et certain des absences V2.
 *
 * Seules les journées complètes, confirmées et explicitement non rémunérées
 * réduisent le nombre de jours rémunérés utilisé pour le plafond SS.
 * Une absence partielle n'est jamais convertie en journée entière.
 * Un jour comportant aussi un pointage est exclu du prorata automatique.
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
        zoneId: ZoneId = ZoneId.systemDefault(),
        workSessions: List<WorkSessionV2> = emptyList()
    ): Snapshot {
        val monthStart = referenceDate.withDayOfMonth(1)
        val monthEndExclusive = monthStart.plusMonths(1)
        val monthStartMs = monthStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val monthEndMs = monthEndExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val workedDays = workedCalendarDays(workSessions, acceptedEmployerIds, monthStartMs, monthEndMs, zoneId)
        val unpaidDays = linkedSetOf<LocalDate>()
        var hasUnpaid = false
        val warnings = mutableListOf<String>()
        val displayDate = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

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
                if (day in workedDays) {
                    warnings += "Absence complète et pointage le ${day.format(displayDate)} : journée exclue du prorata automatique."
                } else {
                    unpaidDays += day
                }
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

    private fun workedCalendarDays(
        sessions: List<WorkSessionV2>,
        acceptedEmployerIds: Set<String>,
        monthStartMs: Long,
        monthEndMs: Long,
        zoneId: ZoneId
    ): Set<LocalDate> = buildSet {
        sessions.forEach { session ->
            if (acceptedEmployerIds.isNotEmpty() && session.employerId !in acceptedEmployerIds) return@forEach
            val start = session.realArrivalMs ?: session.countedEntryMs ?: return@forEach
            val end = session.realExitMs ?: session.countedExitMs ?: return@forEach
            if (end <= start || start >= monthEndMs || end <= monthStartMs) return@forEach
            val clippedStart = maxOf(start, monthStartMs)
            val clippedEnd = minOf(end, monthEndMs)
            var day = Instant.ofEpochMilli(clippedStart).atZone(zoneId).toLocalDate()
            val lastDay = Instant.ofEpochMilli(clippedEnd - 1L).atZone(zoneId).toLocalDate()
            while (!day.isAfter(lastDay)) {
                add(day)
                day = day.plusDays(1)
            }
        }
    }
}
