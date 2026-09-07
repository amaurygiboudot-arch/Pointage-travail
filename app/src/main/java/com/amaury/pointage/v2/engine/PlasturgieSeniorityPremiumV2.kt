package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Prime d'ancienneté Plasturgie (IDCC 0292) — avenant collaborateurs, article 14.
 *
 * La prime est réservée aux collaborateurs (coefficients 700 à 830) et progresse
 * aux paliers 3 / 6 / 9 / 12 / 15 ans. Chaque palier vaut 0,80 % du salaire de
 * base par année du palier, avec le différentiel RTT lorsqu'il existe.
 *
 * HoraTrack exige une date d'ancienneté conventionnelle explicitement confirmée :
 * la simple date d'embauche ne couvre pas tous les cas de l'article 11 (ruptures,
 * suspensions, congé parental, etc.). Aucun montant n'est inventé lorsque la base
 * ou le différentiel RTT n'est pas confirmé.
 */
object PlasturgieSeniorityPremiumV2 {
    const val IDCC = "292"
    private const val RATE_PER_YEAR = 0.008
    private const val SOURCE = "Légifrance — IDCC 292, avenant collaborateurs, article 14 / accord du 28 juin 2011 étendu"
    private val STEPS = listOf(3, 6, 9, 12, 15)

    data class Result(
        val applicable: Boolean,
        val reliable: Boolean,
        val stepYears: Int?,
        val rate: Double?,
        val monthlyAmount: Double?,
        val warnings: List<String>
    )

    fun calculate(
        idcc: String?,
        coefficient: Int?,
        referenceDate: LocalDate,
        confirmedSeniorityDate: LocalDate?,
        monthlyBaseGross: Double?,
        monthlyRttDifferential: Double?
    ): Result {
        val normalizedIdcc = idcc.orEmpty().filter(Char::isDigit).trimStart('0')
        if (normalizedIdcc != IDCC) {
            return Result(false, true, null, null, null, emptyList())
        }

        if (coefficient == null) {
            return review("Prime d'ancienneté Plasturgie : coefficient conventionnel manquant, champ d'application impossible à confirmer.")
        }
        if (coefficient in 900..940) {
            return Result(false, true, null, null, null, emptyList())
        }
        if (coefficient !in 700..830) {
            return review("Prime d'ancienneté Plasturgie : coefficient $coefficient hors classification collaborateurs 700–830 / cadres 900–940 ; application à confirmer.")
        }

        val seniorityDate = confirmedSeniorityDate
            ?: return review("Prime d'ancienneté Plasturgie : date d'ancienneté conventionnelle confirmée manquante. La date d'embauche seule n'est pas utilisée comme preuve.")
        if (seniorityDate.isAfter(referenceDate)) {
            return review("Prime d'ancienneté Plasturgie : la date d'ancienneté conventionnelle est postérieure à la période de paie.")
        }

        val monthStart = referenceDate.withDayOfMonth(1)
        val monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth())
        val startStep = stepAt(seniorityDate, monthStart)
        val endStep = stepAt(seniorityDate, monthEnd)
        if (startStep != endStep) {
            return Result(
                applicable = true,
                reliable = false,
                stepYears = endStep,
                rate = endStep?.let { it * RATE_PER_YEAR },
                monthlyAmount = null,
                warnings = listOf(
                    "Prime d'ancienneté Plasturgie : un palier d'ancienneté change pendant ce mois (${startStep ?: 0} → ${endStep ?: 0} ans). La proratisation exacte doit être contrôlée ; aucun montant mensuel n'est inventé. Source : $SOURCE."
                )
            )
        }

        val step = endStep
        if (step == null) {
            return Result(
                applicable = true,
                reliable = true,
                stepYears = null,
                rate = 0.0,
                monthlyAmount = 0.0,
                warnings = emptyList()
            )
        }

        val base = monthlyBaseGross?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return Result(
                applicable = true,
                reliable = false,
                stepYears = step,
                rate = step * RATE_PER_YEAR,
                monthlyAmount = null,
                warnings = listOf("Prime d'ancienneté Plasturgie : salaire de base mensuel fiable indisponible pour cette période. Source : $SOURCE.")
            )
        val rtt = monthlyRttDifferential?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return Result(
                applicable = true,
                reliable = false,
                stepYears = step,
                rate = step * RATE_PER_YEAR,
                monthlyAmount = null,
                warnings = listOf("Prime d'ancienneté Plasturgie : différentiel RTT à confirmer (renseigner 0 s'il n'en existe pas). Source : $SOURCE.")
            )

        val rate = step * RATE_PER_YEAR
        val amount = (base + rtt) * rate
        return Result(
            applicable = true,
            reliable = true,
            stepYears = step,
            rate = rate,
            monthlyAmount = amount,
            warnings = listOf(
                "Prime d'ancienneté Plasturgie : palier $step ans, taux ${formatPercent(rate)} du salaire de base${if (rtt > 0.0) " incluant le différentiel RTT confirmé" else " ; différentiel RTT confirmé à 0"}. Source : $SOURCE."
            )
        )
    }

    private fun stepAt(seniorityDate: LocalDate, date: LocalDate): Int? {
        if (date.isBefore(seniorityDate)) return null
        val years = ChronoUnit.YEARS.between(seniorityDate, date).toInt().coerceAtLeast(0)
        return STEPS.lastOrNull { years >= it }
    }

    private fun review(message: String) = Result(
        applicable = true,
        reliable = false,
        stepYears = null,
        rate = null,
        monthlyAmount = null,
        warnings = listOf("$message Source : $SOURCE.")
    )

    private fun formatPercent(rate: Double): String =
        String.format(java.util.Locale.FRANCE, "%.1f %%", rate * 100.0)
}
