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
 *
 * Les absences avec maintien/indemnisation sont détectées mais aucun montant
 * d'IJSS, de maintien employeur ou d'indemnité de congés payés n'est inventé.
 */
object AbsencePayrollImpactV2 {
    const val TYPE_UNPAID = "ABSENCE_NON_REMUNEREE"
    const val TYPE_SICKNESS = "ARRET_MALADIE"
    const val TYPE_PAID_LEAVE = "CONGE_PAYE"
    const val TYPE_WORK_ACCIDENT = "ACCIDENT_TRAVAIL"
    const val TYPE_PARENTAL = "MATERNITE_PATERNITE"
    const val TYPE_OTHER = "AUTRE"

    data class Snapshot(
        val unpaidFullCalendarDays: Int,
        val hasUnpaidAbsence: Boolean,
        val hasCompensatedAbsence: Boolean,
        val requiresPayrollReview: Boolean,
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
        var hasCompensated = false
        var requiresReview = false
        val warnings = mutableListOf<String>()
        val displayDate = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

        absences.forEach { absence ->
            if (acceptedEmployerIds.isNotEmpty() && absence.employerId !in acceptedEmployerIds) return@forEach
            if (absence.endMs <= absence.startMs) return@forEach
            if (absence.startMs >= monthEndMs || absence.endMs <= monthStartMs) return@forEach

            if (absence.status != DecisionStatusV2.CONFIRMED) {
                requiresReview = true
                warnings += "Absence à confirmer sur cette période : aucun impact automatique sur la paie."
                return@forEach
            }

            when (absence.salaryTreatment) {
                AbsenceSalaryTreatmentV2.TO_CONFIRM -> {
                    requiresReview = true
                    warnings += "${label(absence.type)} : maintien de salaire à confirmer avant le calcul précis."
                    return@forEach
                }
                AbsenceSalaryTreatmentV2.FULLY_MAINTAINED,
                AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED -> {
                    if (absence.type != TYPE_UNPAID) {
                        hasCompensated = true
                        requiresReview = true
                        warnings += compensatedWarning(absence.type, absence.salaryTreatment)
                    }
                    return@forEach
                }
                AbsenceSalaryTreatmentV2.UNPAID -> Unit
            }

            hasUnpaid = true
            requiresReview = true
            if (absence.type == TYPE_SICKNESS || absence.type == TYPE_WORK_ACCIDENT || absence.type == TYPE_PARENTAL) {
                warnings += "${label(absence.type)} sans maintien employeur : IJSS/indemnisation éventuelle à intégrer avant de calculer le net exact."
            }
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
            hasCompensatedAbsence = hasCompensated,
            requiresPayrollReview = requiresReview,
            warnings = warnings.distinct()
        )
    }

    fun label(type: String): String = when (type) {
        TYPE_UNPAID -> "Absence non rémunérée"
        TYPE_SICKNESS -> "Arrêt maladie"
        TYPE_PAID_LEAVE -> "Congé payé"
        TYPE_WORK_ACCIDENT -> "Accident du travail"
        TYPE_PARENTAL -> "Maternité / paternité"
        TYPE_OTHER -> "Autre absence"
        else -> "Absence"
    }

    private fun compensatedWarning(type: String, treatment: AbsenceSalaryTreatmentV2): String {
        val level = if (treatment == AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED) "maintien partiel" else "maintien"
        return when (type) {
            TYPE_SICKNESS -> "Arrêt maladie avec $level : IJSS, carence, subrogation et règle de maintien restent à vérifier avant le calcul précis."
            TYPE_PAID_LEAVE -> "Congé payé : l'indemnité doit être contrôlée selon la méthode applicable ; aucun montant n'est inventé."
            TYPE_WORK_ACCIDENT -> "Accident du travail avec $level : indemnisation et maintien applicables restent à vérifier."
            TYPE_PARENTAL -> "Maternité / paternité avec $level : indemnisation et éventuel maintien employeur restent à vérifier."
            else -> "Absence avec $level : traitement de paie à vérifier avant le calcul précis."
        }
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