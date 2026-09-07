package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Prime d'ancienneté conventionnelle générique, datée et strictement structurée. */
object ConventionSeniorityPremiumV2 {
    enum class Basis {
        ACTUAL_MONTHLY_BASE,
        CONVENTIONAL_MINIMUM_MONTHLY,
        FIXED_MONTHLY
    }

    data class Step(
        val years: Int,
        /** Taux décimal pour les bases salariales : 2,4 % = 0,024. */
        val rate: Double? = null,
        val fixedMonthlyAmount: Double? = null
    )

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        val basis: Basis,
        val steps: List<Step>,
        /** Exemple Plasturgie : différentiel RTT confirmé ajouté à la base. */
        val includeConfirmedMonthlySupplement: Boolean = false,
        val source: String,
        val extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus
    ) {
        fun structurallyValid(): Boolean {
            if (ConventionMinimumSalaryV2.normalizeIdcc(idcc).isBlank() || ruleId.isBlank() || source.isBlank()) return false
            if (effectiveTo?.isBefore(effectiveFrom) == true || steps.isEmpty()) return false
            if (steps.any { it.years <= 0 } || steps.map { it.years }.distinct().size != steps.size) return false
            return steps.all { step ->
                when (basis) {
                    Basis.ACTUAL_MONTHLY_BASE, Basis.CONVENTIONAL_MINIMUM_MONTHLY ->
                        step.rate?.let { it.isFinite() && it >= 0.0 } == true && step.fixedMonthlyAmount == null
                    Basis.FIXED_MONTHLY ->
                        step.fixedMonthlyAmount?.let { it.isFinite() && it >= 0.0 } == true && step.rate == null
                }
            }
        }

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun applicableToCompany(companyApplicabilityConfirmed: Boolean): Boolean = when (extensionStatus) {
            ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED -> true
            ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED -> companyApplicabilityConfirmed
            ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN -> false
        }
    }

    data class Result(
        val applicable: Boolean,
        val reliable: Boolean,
        val selectedRule: Rule?,
        val stepYears: Int?,
        val rate: Double?,
        val monthlyAmount: Double?,
        val warnings: List<String>
    )

    fun calculate(
        rules: List<Rule>,
        idcc: String,
        classification: ConventionClassificationV2,
        referenceDate: LocalDate,
        confirmedSeniorityDate: LocalDate?,
        actualMonthlyBaseGross: Double?,
        conventionalMinimumMonthlyGross: Double?,
        confirmedMonthlySupplement: Double? = 0.0,
        companyApplicabilityConfirmed: Boolean = false
    ): Result {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val candidates = rules
            .filter { it.structurallyValid() }
            .filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
            .filter { it.activeOn(referenceDate) }
            .filter { classification.matches(it.classification) }
            .filter { it.applicableToCompany(companyApplicabilityConfirmed) }

        if (candidates.isEmpty()) {
            return Result(false, false, null, null, null, null, emptyList())
        }

        val latestDate = candidates.maxOf { it.effectiveFrom }
        val latest = candidates.filter { it.effectiveFrom == latestDate }
        val specificity = latest.maxOf { it.classification.specificity() }
        val best = latest.filter { it.classification.specificity() == specificity }
        if (best.size != 1) {
            return Result(
                applicable = true,
                reliable = false,
                selectedRule = null,
                stepYears = null,
                rate = null,
                monthlyAmount = null,
                warnings = listOf("Prime d'ancienneté IDCC $normalized : plusieurs règles applicables se chevauchent avec la même précision ; calcul automatique bloqué.")
            )
        }
        val rule = best.single()
        val seniorityDate = confirmedSeniorityDate ?: return review(
            rule,
            "Prime d'ancienneté IDCC $normalized : date d'ancienneté conventionnelle confirmée manquante."
        )
        if (seniorityDate.isAfter(referenceDate)) {
            return review(rule, "Prime d'ancienneté IDCC $normalized : date d'ancienneté postérieure à la période de paie.")
        }

        val monthStart = referenceDate.withDayOfMonth(1)
        val monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth())
        val startStep = stepAt(rule, seniorityDate, monthStart)
        val endStep = stepAt(rule, seniorityDate, monthEnd)
        if (startStep?.years != endStep?.years) {
            return Result(
                applicable = true,
                reliable = false,
                selectedRule = rule,
                stepYears = endStep?.years,
                rate = endStep?.rate,
                monthlyAmount = null,
                warnings = listOf("Prime d'ancienneté IDCC $normalized : un palier change pendant le mois ; proratisation à contrôler. Source : ${rule.source}.")
            )
        }
        val step = endStep
        if (step == null) {
            return Result(true, true, rule, null, 0.0, 0.0, emptyList())
        }

        val supplement = if (rule.includeConfirmedMonthlySupplement) {
            confirmedMonthlySupplement?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return review(rule, "Prime d'ancienneté IDCC $normalized : complément mensuel de base à confirmer (0 s'il n'existe pas).")
        } else 0.0

        val amount = when (rule.basis) {
            Basis.ACTUAL_MONTHLY_BASE -> {
                val base = actualMonthlyBaseGross?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: return review(rule, "Prime d'ancienneté IDCC $normalized : salaire mensuel de base fiable indisponible.")
                (base + supplement) * requireNotNull(step.rate)
            }
            Basis.CONVENTIONAL_MINIMUM_MONTHLY -> {
                val base = conventionalMinimumMonthlyGross?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: return review(rule, "Prime d'ancienneté IDCC $normalized : minimum conventionnel mensuel fiable indisponible.")
                (base + supplement) * requireNotNull(step.rate)
            }
            Basis.FIXED_MONTHLY -> requireNotNull(step.fixedMonthlyAmount)
        }

        return Result(
            applicable = true,
            reliable = true,
            selectedRule = rule,
            stepYears = step.years,
            rate = step.rate,
            monthlyAmount = amount,
            warnings = listOf("Prime d'ancienneté IDCC $normalized : palier ${step.years} ans appliqué. Source : ${rule.source}.")
        )
    }

    private fun stepAt(rule: Rule, seniorityDate: LocalDate, date: LocalDate): Step? {
        if (date.isBefore(seniorityDate)) return null
        val years = ChronoUnit.YEARS.between(seniorityDate, date).toInt().coerceAtLeast(0)
        return rule.steps.filter { years >= it.years }.maxByOrNull { it.years }
    }

    private fun review(rule: Rule, warning: String) = Result(
        applicable = true,
        reliable = false,
        selectedRule = rule,
        stepYears = null,
        rate = null,
        monthlyAmount = null,
        warnings = listOf("$warning Source : ${rule.source}.")
    )
}
