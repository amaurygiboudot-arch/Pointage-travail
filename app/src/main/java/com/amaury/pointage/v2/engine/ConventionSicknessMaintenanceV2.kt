package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Maintien maladie conventionnel générique, daté et classifié. */
object ConventionSicknessMaintenanceV2 {
    enum class ReferenceBasis { NET, GROSS, UNKNOWN }

    enum class WaitingPolicy {
        NONE,
        FIXED_EACH_STOP,
        FIRST_STOP_FREE_THEN_FIXED_SHORT_FIRST_CARRY
    }

    /**
     * Portée des bandes jours/taux.
     * ANNUAL_CUMULATIVE : les jours déjà indemnisés consomment les bandes pour l'année.
     * PER_STOP : chaque nouvel arrêt repart à la première bande, sous réserve du plafond annuel.
     * UNKNOWN : aucune application automatique n'est autorisée.
     */
    enum class BandConsumptionScope { ANNUAL_CUMULATIVE, PER_STOP, UNKNOWN }

    data class Band(
        val calendarDays: Int,
        val targetRate: Double,
        val label: String
    ) {
        /** Alias conservé pour les calculs nets existants. */
        val targetNetRate: Double get() = targetRate
    }

    data class SeniorityTier(
        val minimumSeniorityMonths: Int,
        val bands: List<Band>,
        /** Null = somme des bandes. */
        val annualLimitDays: Int? = null,
        /** Null = somme des bandes. */
        val perStopLimitDays: Int? = null,
        val bandConsumptionScope: BandConsumptionScope = BandConsumptionScope.UNKNOWN
    )

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        /** CADRE / NON_CADRE lorsque la convention distingue les statuts. */
        val professionalStatus: String? = null,
        val minimumSeniorityMonths: Int,
        val tiers: List<SeniorityTier>,
        val referenceBasis: ReferenceBasis,
        val waitingPolicy: WaitingPolicy,
        val waitingDays: Int = 0,
        /** Nombre de jours au-delà duquel la prise en charge SS doit être confirmée ; null = aucune condition structurée. */
        val socialSecurityCoverageRequiredAfterDays: Int? = null,
        val source: String,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus,
        val extensionEffectiveFrom: LocalDate? = null
    ) {
        fun structurallyValid(): Boolean {
            if (ConventionMinimumSalaryV2.normalizeIdcc(idcc).isBlank() || ruleId.isBlank() || source.isBlank()) return false
            if (effectiveTo?.isBefore(effectiveFrom) == true || minimumSeniorityMonths < 0 || tiers.isEmpty()) return false
            if (extensionEffectiveFrom != null && extensionStatus != ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) return false
            if (socialSecurityCoverageRequiredAfterDays != null && socialSecurityCoverageRequiredAfterDays < 0) return false
            if (waitingDays < 0 || (waitingPolicy == WaitingPolicy.NONE && waitingDays != 0)) return false
            if (tiers.any { it.minimumSeniorityMonths < minimumSeniorityMonths || it.bands.isEmpty() }) return false
            if (tiers.map { it.minimumSeniorityMonths }.distinct().size != tiers.size) return false
            return tiers.all { tier ->
                val totalBandDays = tier.bands.sumOf { it.calendarDays }
                if (tier.bandConsumptionScope == BandConsumptionScope.UNKNOWN) return@all false
                val annualLimitValid = tier.annualLimitDays?.let { limit ->
                    limit in 1..366 &&
                        (tier.bandConsumptionScope != BandConsumptionScope.ANNUAL_CUMULATIVE || limit <= totalBandDays)
                } != false
                val perStopLimitValid = tier.perStopLimitDays?.let { it in 1..totalBandDays } != false
                annualLimitValid && perStopLimitValid && tier.bands.all { band ->
                    band.calendarDays > 0 && band.targetRate.isFinite() && band.targetRate in 0.0..1.0 && band.label.isNotBlank()
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
        val referenceBasis: ReferenceBasis,
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
        if (!endExclusive.isAfter(start)) return unavailable("Maintien maladie : période d'arrêt invalide.").copy(applicable = true, selectedRule = selected, referenceBasis = selected.referenceBasis)
        if (currentAbsence.status != DecisionStatusV2.CONFIRMED) {
            return unavailable("Maintien maladie : arrêt à confirmer avant application du barème. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected, referenceBasis = selected.referenceBasis)
        }
        if (entryDate == null) {
            return unavailable("Maintien maladie : date d'entrée manquante, ancienneté impossible à contrôler. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected, referenceBasis = selected.referenceBasis)
        }
        if (entryDate.isAfter(start)) {
            return unavailable("Maintien maladie : date d'entrée postérieure au début de l'arrêt. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected, referenceBasis = selected.referenceBasis)
        }

        val seniorityMonths = ChronoUnit.MONTHS.between(entryDate, start).toInt().coerceAtLeast(0)
        if (seniorityMonths < selected.minimumSeniorityMonths) {
            return Result(
                applicable = true,
                eligibilityConfirmed = true,
                reliable = true,
                selectedRule = selected,
                referenceBasis = selected.referenceBasis,
                employerWaitingDays = null,
                firstRecordedStopOfYear = null,
                annualLimitDays = 0,
                alreadyConsumedIndemnifiedDays = 0,
                currentIndemnifiableDays = 0,
                bands = emptyList(),
                socialSecurityCoverageRequired = false,
                exactEmployerAmountAvailable = false,
                warnings = listOf("Maintien maladie IDCC $normalized : ancienneté inférieure au minimum conventionnel de ${selected.minimumSeniorityMonths} mois. Source : ${selected.source}.")
            )
        }

        val tier = selected.tiers.filter { seniorityMonths >= it.minimumSeniorityMonths }.maxByOrNull { it.minimumSeniorityMonths }
            ?: return unavailable("Maintien maladie IDCC $normalized : aucun palier d'ancienneté confirmé ne correspond. Source : ${selected.source}.")
                .copy(applicable = true, selectedRule = selected, referenceBasis = selected.referenceBasis)
        val totalBandDays = tier.bands.sumOf { it.calendarDays }
        val annualLimit = tier.annualLimitDays ?: totalBandDays
        val perStopLimit = tier.perStopLimitDays ?: totalBandDays
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
            WaitingPolicy.FIXED_EACH_STOP -> selected.waitingDays
            WaitingPolicy.FIRST_STOP_FREE_THEN_FIXED_SHORT_FIRST_CARRY -> when {
                index == 0 -> 0
                index == 1 && recordedStops.firstOrNull()?.let(::durationDays)?.let { it < selected.waitingDays } == true -> durationDays(recordedStops.first())
                else -> selected.waitingDays
            }
        }

        val firstRecordedStop = recordedStops.isEmpty()
        val waiting = waitingForStop(recordedStops.size)
        var consumed = 0
        recordedStops.forEachIndexed { index, absence ->
            val priorPayable = (durationDays(absence) - waitingForStop(index)).coerceAtLeast(0).coerceAtMost(perStopLimit)
            consumed += priorPayable
        }
        consumed = consumed.coerceAtMost(annualLimit)

        val currentCalendarDays = ChronoUnit.DAYS.between(start, endExclusive).toInt().coerceAtLeast(0)
        val afterWaiting = (currentCalendarDays - waiting).coerceAtLeast(0)
        val indemnifiable = minOf(afterWaiting, (annualLimit - consumed).coerceAtLeast(0), perStopLimit)
        var remaining = indemnifiable
        var already = if (tier.bandConsumptionScope == BandConsumptionScope.ANNUAL_CUMULATIVE) consumed else 0
        val bands = buildList {
            tier.bands.forEach { band ->
                val consumedInBand = minOf(already, band.calendarDays)
                already = (already - consumedInBand).coerceAtLeast(0)
                val available = (band.calendarDays - consumedInBand).coerceAtLeast(0)
                val days = minOf(remaining, available)
                if (days > 0) add(Band(days, band.targetRate, band.label))
                remaining -= days
            }
        }
        val ssRequired = selected.socialSecurityCoverageRequiredAfterDays?.let { currentCalendarDays > it } == true

        return Result(
            applicable = true,
            eligibilityConfirmed = true,
            reliable = true,
            selectedRule = selected,
            referenceBasis = selected.referenceBasis,
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
                if (selected.referenceBasis != ReferenceBasis.NET) add("Base du maintien : ${selected.referenceBasis.name}. Le calcul net automatique n'est pas utilisé pour une règle exprimée sur une autre base.")
                add("Portée des tranches : ${tier.bandConsumptionScope.name}.")
                add("Le montant exact du complément employeur exige la rémunération de référence prévue par la convention, puis les déductions légalement ou conventionnellement applicables.")
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
        referenceBasis = ReferenceBasis.UNKNOWN,
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
