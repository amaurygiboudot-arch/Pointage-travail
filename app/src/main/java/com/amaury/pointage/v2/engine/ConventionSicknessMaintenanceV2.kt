package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Maintien maladie conventionnel générique, daté et classifié. */
object ConventionSicknessMaintenanceV2 {
    enum class WaitingPolicy {
        NONE,
        FIRST_STOP_FREE_THEN_THREE_DAYS_SHORT_FIRST_CARRY
    }

    data class Band(
        val calendarDays: Int,
        val targetNetRate: Double,
        val label: String
    )

    data class SeniorityTier(
        val minimumYears: Int,
        val bands: List<Band>
    )

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        /** CADRE / NON_CADRE lorsque la convention distingue les statuts. */
        val professionalStatus: String? = null,
        val minimumSeniorityYears: Int,
        val tiers: List<SeniorityTier>,
        val waitingPolicy: WaitingPolicy,
        /** Nombre de jours au-delà duquel la prise en charge SS doit être confirmée ; null = aucune condition structurée. */
        val socialSecurityCoverageRequiredAfterDays: Int? = null,
        val source: String,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null
    ) {
        fun structurallyValid(): Boolean {
            if (ConventionMinimumSalaryV2.normalizeIdcc(idcc).isBlank() || ruleId.isBlank() || source.isBlank()) return false
            if (effectiveTo?.isBefore(effectiveFrom) == true || minimumSeniorityYears < 0 || tiers.isEmpty()) return false
            if (extensionEffectiveFrom != null && extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) return false
            if (socialSecurityCoverageRequiredAfterDays != null && socialSecurityCoverageRequiredAfterDays < 0) return false
            if (tiers.any { it.minimumYears < minimumSeniorityYears || it.bands.isEmpty() }) return false
            if (tiers.map { it.minimumYears }.distinct().size != tiers.size) return false
            return tiers.all { tier ->
                tier.bands.all { band ->
                    band.calendarDays > 0 && band.targetNetRate.isFinite() && band.targetNetRate in 0.0..1.0 && band.label.isNotBlank()
                }
            }
        }

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun statusMatches(value: String?): Boolean {
            val wanted = professionalStatus?.trim()?.uppercase()
            return wanted == null || wanted == value?.trim()?.uppercase()
        }

        fun applicableToCompany(companyApplicabilityConfirmed: Boolean, date: LocalDate): Boolean = when (extensionStatus) {
            ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED -> companyApplicabilityConfirmed ||
                !date.isBefore(extensionEffectiveFrom ?: effectiveFrom)
            ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED -> companyApplicabilityConfirmed
            ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN -> false
        }
    }

    data class Result(
        val applicable: Boolean,
        val eligibilityConfirmed: Boolean,
        val reliable: Boolean,
        val selectedRule: Rule?,
        val employerWaitingDays: Int?,
        val firstRecordedStopOfYear: Boolean?,
        val annualLimitDays: Int?,
        val alreadyConsumedIndemnifiedDays: Int?,
        val currentIndemnifiableDays: Int?,
        val bands: List<Band>,
        val socialSecurityCoverageRequired: Boolean,
        val exactEmployerAmountAvailable: Boolean,
        val warnings: List<String>
    )

    fun calculate(
        rules: List<Rule>,
        idcc: String?,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        currentAbsence: AbsenceV2,
        allAbsences: List<AbsenceV2>,
        entryDate: LocalDate?,
        acceptedEmployerIds: Set<String>,
        companyApplicabilityConfirmed: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result {
        if (currentAbsence.type != AbsencePayrollImpactV2.TYPE_SICKNESS) return notApplicable()
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        if (normalized.isBlank()) return unavailable("Maintien maladie : IDCC manquant, aucun barème conventionnel n'est inventé.")

        val start = localDate(currentAbsence.startMs, zoneId)
        val candidates = rules
            .filter { it.structurallyValid() }
            .filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
            .filter { it.activeOn(start) }
            .filter { classification.matches(it.classification) }
            .filter { it.statusMatches(professionalStatus) }

        if (candidates.isEmpty()) return unavailable("Maintien maladie IDCC $normalized : aucun barème confirmé ne correspond au statut et à la classification pour cette période.")

        val applicable = candidates.filter { it.applicableToCompany(companyApplicabilityConfirmed, start) }
        val selected = select(applicable)
        if (selected == null) {
            return unavailable("Maintien maladie IDCC $normalized : barème présent mais applicabilité non démontrée ou plusieurs règles de même précision se contredisent.")
                .copy(applicable = true)
        }

        val endExclusive = localDate(currentAbsence.endMs, zoneId)
        if (!endExclusive.isAfter(start)) return unavailable("Maintien maladie : période d'arrêt invalide.").copy(applicable = true, selectedRule = selected)
        if (currentAbsence.status != DecisionStatusV2.CONFIRMED) {
            return unavailable("Maintien maladie : arrêt à confirmer avant application du barème. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected)
        }
        if (entryDate == null) {
            return unavailable("Maintien maladie : date d'entrée manquante, ancienneté impossible à contrôler. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected)
        }
        if (entryDate.isAfter(start)) {
            return unavailable("Maintien maladie : date d'entrée postérieure au début de l'arrêt. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected)
        }

        val seniorityYears = ChronoUnit.YEARS.between(entryDate, start).toInt().coerceAtLeast(0)
        if (seniorityYears < selected.minimumSeniorityYears) {
            return Result(
                applicable = true,
                eligibilityConfirmed = true,
                reliable = true,
                selectedRule = selected,
                employerWaitingDays = null,
                firstRecordedStopOfYear = null,
                annualLimitDays = 0,
                alreadyConsumedIndemnifiedDays = 0,
                currentIndemnifiableDays = 0,
                bands = emptyList(),
                socialSecurityCoverageRequired = false,
                exactEmployerAmountAvailable = false,
                warnings = listOf("Maintien maladie IDCC $normalized : ancienneté inférieure au minimum conventionnel de ${selected.minimumSeniorityYears} an(s). Source : ${selected.source}.")
            )
        }

        val tier = selected.tiers.filter { seniorityYears >= it.minimumYears }.maxByOrNull { it.minimumYears }
            ?: return unavailable("Maintien maladie IDCC $normalized : aucun palier d'ancienneté confirmé ne correspond. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected)
        val annualLimit = tier.bands.sumOf { it.calendarDays }
        val year = start.year
        val recordedStops = allAbsences
            .asSequence()
            .filter { it.type == AbsencePayrollImpactV2.TYPE_SICKNESS && it.status == DecisionStatusV2.CONFIRMED }
            .filter { acceptedEmployerIds.isEmpty() || it.employerId in acceptedEmployerIds }
            .filter { localDate(it.startMs, zoneId).let { date -> date.year == year && date.isBefore(start) } }
            .sortedBy { it.startMs }
            .toList()

        fun durationDays(absence: AbsenceV2): Int {
            val s = localDate(absence.startMs, zoneId)
            val e = localDate(absence.endMs, zoneId)
            return if (e.isAfter(s)) ChronoUnit.DAYS.between(s, e).toInt().coerceAtLeast(0) else 0
        }

        fun waitingForStop(index: Int): Int = when (selected.waitingPolicy) {
            WaitingPolicy.NONE -> 0
            WaitingPolicy.FIRST_STOP_FREE_THEN_THREE_DAYS_SHORT_FIRST_CARRY -> when {
                index == 0 -> 0
                index == 1 && recordedStops.firstOrNull()?.let(::durationDays)?.let { it < 3 } == true -> durationDays(recordedStops.first())
                else -> 3
            }
        }

        val firstRecordedStop = recordedStops.isEmpty()
        val waiting = waitingForStop(recordedStops.size)
        var consumed = 0
        recordedStops.forEachIndexed { index, absence ->
            consumed += (durationDays(absence) - waitingForStop(index)).coerceAtLeast(0)
        }
        consumed = consumed.coerceAtMost(annualLimit)

        val currentCalendarDays = ChronoUnit.DAYS.between(start, endExclusive).toInt().coerceAtLeast(0)
        val afterWaiting = (currentCalendarDays - waiting).coerceAtLeast(0)
        val indemnifiable = minOf(afterWaiting, (annualLimit - consumed).coerceAtLeast(0))
        var remaining = indemnifiable
        var already = consumed
        val bands = buildList {
            tier.bands.forEach { band ->
                val consumedInBand = minOf(already, band.calendarDays)
                already = (already - consumedInBand).coerceAtLeast(0)
                val available = (band.calendarDays - consumedInBand).coerceAtLeast(0)
                val days = minOf(remaining, available)
                if (days > 0) add(Band(days, band.targetNetRate, band.label))
                remaining -= days
            }
        }
        val ssRequired = selected.socialSecurityCoverageRequiredAfterDays?.let { currentCalendarDays > it } == true

        return Result(
            applicable = true,
            eligibilityConfirmed = true,
            reliable = true,
            selectedRule = selected,
            employerWaitingDays = waiting,
            firstRecordedStopOfYear = firstRecordedStop,
            annualLimitDays = annualLimit,
            alreadyConsumedIndemnifiedDays = consumed,
            currentIndemnifiableDays = indemnifiable,
            bands = bands,
            socialSecurityCoverageRequired = ssRequired,
            exactEmployerAmountAvailable = false,
            warnings = buildList {
                add("Maintien maladie conventionnel calculé sur les arrêts enregistrés dans HoraTrack pour cette entreprise et cette année. Source : ${selected.source}.")
                if (waiting > 0) add("Carence conventionnelle de $waiting jour(s) appliquée selon la règle sélectionnée.")
                if (ssRequired) add("Prise en charge par la Sécurité sociale à confirmer pour sécuriser le complément conventionnel.")
                add("Le montant exact du complément employeur exige la rémunération nette théorique de la période, puis la déduction des IJSS et des prestations de prévoyance employeur qui chevauchent réellement le maintien.")
                if (consumed >= annualLimit) add("Plafond annuel conventionnel atteint selon les absences enregistrées dans HoraTrack.")
            }
        )
    }

    private fun select(rules: List<Rule>): Rule? {
        if (rules.isEmpty()) return null
        val latestDate = rules.maxOf { it.effectiveFrom }
        val latest = rules.filter { it.effectiveFrom == latestDate }
        fun specificity(rule: Rule) = rule.classification.specificity() + if (rule.professionalStatus == null) 0 else 1
        val maxSpecificity = latest.maxOf(::specificity)
        val best = latest.filter { specificity(it) == maxSpecificity }
        return best.singleOrNull()
    }

    private fun unavailable(message: String) = Result(
        applicable = false,
        eligibilityConfirmed = false,
        reliable = false,
        selectedRule = null,
        employerWaitingDays = null,
        firstRecordedStopOfYear = null,
        annualLimitDays = null,
        alreadyConsumedIndemnifiedDays = null,
        currentIndemnifiableDays = null,
        bands = emptyList(),
        socialSecurityCoverageRequired = false,
        exactEmployerAmountAvailable = false,
        warnings = listOf(message)
    )

    private fun notApplicable() = unavailable("Maintien maladie : absence hors arrêt maladie.").copy(warnings = emptyList())

    private fun localDate(ms: Long, zoneId: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zoneId).toLocalDate()
}
