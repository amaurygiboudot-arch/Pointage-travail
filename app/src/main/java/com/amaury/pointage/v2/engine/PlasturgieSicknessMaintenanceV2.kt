package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Barème de maintien maladie de la Plasturgie (IDCC 0292), article 13 de
 * l'avenant ouvriers/collaborateurs/employés/techniciens/agents de maîtrise.
 *
 * Cette couche calcule uniquement les jours et les taux conventionnels.
 * Le montant employeur en euros n'est jamais inventé : la convention vise la
 * rémunération nette que le salarié aurait perçue en travaillant normalement,
 * puis déduit IJSS et, le cas échéant, la prévoyance financée par l'employeur.
 */
object PlasturgieSicknessMaintenanceV2 {
    const val IDCC = "292"

    data class Band(
        val calendarDays: Int,
        val targetNetRate: Double,
        val label: String
    )

    data class Result(
        val applicable: Boolean,
        val eligibilityConfirmed: Boolean,
        val employerWaitingDays: Int?,
        val firstRecordedStopOfYear: Boolean?,
        val annualLimitDays: Int?,
        val alreadyConsumedIndemnifiedDays: Int?,
        val currentIndemnifiableDays: Int?,
        val bands: List<Band>,
        val exactEmployerAmountAvailable: Boolean,
        val warnings: List<String>
    )

    fun calculate(
        idcc: String?,
        currentAbsence: AbsenceV2,
        allAbsences: List<AbsenceV2>,
        entryDate: LocalDate?,
        acceptedEmployerIds: Set<String>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result {
        val normalizedIdcc = idcc.orEmpty().filter(Char::isDigit).trimStart('0')
        if (normalizedIdcc != IDCC || currentAbsence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) {
            return Result(false, false, null, null, null, null, null, emptyList(), false, emptyList())
        }

        val start = localDate(currentAbsence.startMs, zoneId)
        val endExclusive = localDate(currentAbsence.endMs, zoneId)
        if (!endExclusive.isAfter(start)) {
            return Result(true, false, null, null, null, null, null, emptyList(), false, listOf("Maintien Plasturgie : période d'arrêt invalide."))
        }
        if (currentAbsence.status != DecisionStatusV2.CONFIRMED) {
            return Result(true, false, null, null, null, null, null, emptyList(), false, listOf("Maintien Plasturgie : arrêt à confirmer avant application du barème."))
        }

        if (entryDate == null) {
            return Result(
                applicable = true,
                eligibilityConfirmed = false,
                employerWaitingDays = null,
                firstRecordedStopOfYear = null,
                annualLimitDays = null,
                alreadyConsumedIndemnifiedDays = null,
                currentIndemnifiableDays = null,
                bands = emptyList(),
                exactEmployerAmountAvailable = false,
                warnings = listOf("Maintien Plasturgie : date d'entrée manquante, ancienneté impossible à contrôler.")
            )
        }

        val seniorityYears = ChronoUnit.YEARS.between(entryDate, start).toInt().coerceAtLeast(0)
        if (seniorityYears < 1) {
            return Result(
                applicable = true,
                eligibilityConfirmed = true,
                employerWaitingDays = null,
                firstRecordedStopOfYear = null,
                annualLimitDays = 0,
                alreadyConsumedIndemnifiedDays = 0,
                currentIndemnifiableDays = 0,
                bands = emptyList(),
                exactEmployerAmountAvailable = false,
                warnings = listOf("Maintien Plasturgie : ancienneté inférieure à 1 an, l'indemnisation conventionnelle maladie ordinaire n'est pas ouverte.")
            )
        }

        val fiveYears = seniorityYears >= 5
        val fullRateLimit = if (fiveYears) 60 else 45
        val reducedRateLimit = if (fiveYears) 75 else 60
        val annualLimit = fullRateLimit + reducedRateLimit
        val year = start.year

        val recordedStops = allAbsences
            .asSequence()
            .filter { it.type == AbsencePayrollImpactV2.TYPE_SICKNESS }
            .filter { it.status == DecisionStatusV2.CONFIRMED }
            .filter { acceptedEmployerIds.isEmpty() || it.employerId in acceptedEmployerIds }
            .filter { absence ->
                val s = localDate(absence.startMs, zoneId)
                s.year == year && s.isBefore(start)
            }
            .sortedBy { it.startMs }
            .toList()

        val firstRecordedStop = recordedStops.isEmpty()
        // Article 13 : pas de carence conventionnelle sur le premier arrêt de l'année.
        // Pour les suivants, 3 jours, hors exceptions (ALD notamment) qui restent à confirmer.
        val waiting = if (firstRecordedStop) 0 else 3

        var consumed = 0
        recordedStops.forEachIndexed { index, absence ->
            val s = localDate(absence.startMs, zoneId)
            val e = localDate(absence.endMs, zoneId)
            if (!e.isAfter(s)) return@forEachIndexed
            val days = ChronoUnit.DAYS.between(s, e).toInt().coerceAtLeast(0)
            val priorWaiting = if (index == 0) 0 else 3
            consumed += (days - priorWaiting).coerceAtLeast(0)
        }
        consumed = consumed.coerceAtMost(annualLimit)

        val currentCalendarDays = ChronoUnit.DAYS.between(start, endExclusive).toInt().coerceAtLeast(0)
        val afterWaiting = (currentCalendarDays - waiting).coerceAtLeast(0)
        val remainingAnnual = (annualLimit - consumed).coerceAtLeast(0)
        val indemnifiable = minOf(afterWaiting, remainingAnnual)

        val fullRemaining = (fullRateLimit - consumed).coerceAtLeast(0)
        val fullDays = minOf(indemnifiable, fullRemaining)
        val reducedDays = (indemnifiable - fullDays).coerceAtLeast(0)
        val bands = buildList {
            if (fullDays > 0) add(Band(fullDays, 1.00, "Maintien conventionnel à 100 % du net de référence"))
            if (reducedDays > 0) add(Band(reducedDays, 0.75, "Maintien conventionnel à 75 % du net de référence"))
        }

        val warnings = buildList {
            add("Barème Plasturgie calculé sur les arrêts enregistrés dans HoraTrack pour cette entreprise et cette année.")
            if (!firstRecordedStop) add("Carence conventionnelle de 3 jours appliquée ; les exceptions (notamment ALD) restent à confirmer.")
            add("Le montant exact du complément employeur exige la rémunération nette qui aurait été perçue sur la période, puis la déduction des IJSS et des prestations de prévoyance financées par l'employeur.")
            if (consumed >= annualLimit) add("Plafond annuel conventionnel déjà atteint selon les absences enregistrées dans HoraTrack.")
        }

        return Result(
            applicable = true,
            eligibilityConfirmed = true,
            employerWaitingDays = waiting,
            firstRecordedStopOfYear = firstRecordedStop,
            annualLimitDays = annualLimit,
            alreadyConsumedIndemnifiedDays = consumed,
            currentIndemnifiableDays = indemnifiable,
            bands = bands,
            exactEmployerAmountAvailable = false,
            warnings = warnings
        )
    }

    private fun localDate(ms: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zoneId).toLocalDate()
}
