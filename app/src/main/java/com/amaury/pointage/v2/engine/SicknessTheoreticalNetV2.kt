package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2
import java.time.LocalDate
import java.time.YearMonth

/**
 * Calcule la rémunération nette théorique de la période d'arrêt maladie.
 *
 * Référence Plasturgie : l'indemnisation est basée sur la rémunération nette que
 * le salarié aurait perçue en travaillant normalement et les périodes indemnisées
 * sont décomptées en jours calendaires.
 *
 * Les IJSS sont retranchées UNE SEULE FOIS sur les journées qui chevauchent le
 * maintien. Une prestation de prévoyance employeur n'est retranchée ensuite que
 * si son chevauchement avec le maintien a été explicitement confirmé.
 * Le relais de branche après maintien n'entre jamais dans cette déduction.
 */
object SicknessTheoreticalNetV2 {
    data class MonthlyBase(
        val period: YearMonth,
        val monthlyNetBeforeIncomeTax: Double
    )

    data class Result(
        val complete: Boolean,
        /** Base nette de tous les jours calendaires de l'arrêt. */
        val theoreticalAbsenceNet: Double?,
        /** Base nette des seuls jours couverts par les bandes conventionnelles. */
        val theoreticalIndemnifiableNet: Double?,
        /** Cible après application des bandes 100 % / 75 %, avant IJSS/prévoyance. */
        val targetMaintenanceNet: Double?,
        /** IJSS nettes avant impôt retranchées une seule fois sur la même période. */
        val ijssNetDeductedOnce: Double?,
        val ijssDaysDeducted: Int?,
        /** Complément employeur après IJSS, avant éventuelle prévoyance chevauchante. */
        val employerComplementBeforeProvidentNet: Double?,
        /** Prestation employeur chevauchante effectivement retranchée. */
        val employerProvidentNetDeducted: Double?,
        /** Complément final uniquement si la prévoyance chevauchante est confirmée. */
        val employerComplementFinalNet: Double?,
        val finalComplementReliable: Boolean,
        val indemnifiableDays: Int,
        val monthlyBases: List<MonthlyBase>,
        val warnings: List<String>
    )

    fun calculate(
        absenceStart: LocalDate,
        absenceEndExclusive: LocalDate,
        maintenance: PlasturgieSicknessMaintenanceV2.Result,
        monthlyNetBeforeIncomeTax: Map<YearMonth, Double>,
        allowance: SicknessDailyAllowanceV2.Result?,
        providentTreatment: AbsenceProvidentTreatmentV2 = AbsenceProvidentTreatmentV2.TO_CONFIRM,
        employerProvidentOverlapNetAmount: Double? = null
    ): Result {
        if (!absenceEndExclusive.isAfter(absenceStart)) {
            return unavailable("Base nette maladie : période d'arrêt invalide.")
        }
        if (!maintenance.applicable || !maintenance.eligibilityConfirmed) {
            return unavailable("Base nette maladie : maintien conventionnel non applicable ou éligibilité non confirmée.")
        }

        val normalizedMonthly = monthlyNetBeforeIncomeTax
            .filterValues { it.isFinite() && it >= 0.0 }
            .toSortedMap()
        val requiredMonths = monthsBetween(absenceStart, absenceEndExclusive)
        val missingMonths = requiredMonths.filterNot(normalizedMonthly::containsKey)
        if (missingMonths.isNotEmpty()) {
            return Result(
                complete = false,
                theoreticalAbsenceNet = null,
                theoreticalIndemnifiableNet = null,
                targetMaintenanceNet = null,
                ijssNetDeductedOnce = null,
                ijssDaysDeducted = null,
                employerComplementBeforeProvidentNet = null,
                employerProvidentNetDeducted = null,
                employerComplementFinalNet = null,
                finalComplementReliable = false,
                indemnifiableDays = maintenance.bands.sumOf { it.calendarDays.coerceAtLeast(0) },
                monthlyBases = normalizedMonthly.map { MonthlyBase(it.key, it.value) },
                warnings = listOf(
                    "Base nette maladie : net mensuel théorique manquant pour ${missingMonths.joinToString { "%02d/%04d".format(it.monthValue, it.year) }}."
                )
            )
        }

        fun dailyNet(date: LocalDate): Double {
            val ym = YearMonth.from(date)
            return normalizedMonthly.getValue(ym) / ym.lengthOfMonth().toDouble()
        }

        var wholeAbsenceNet = 0.0
        var cursor = absenceStart
        while (cursor.isBefore(absenceEndExclusive)) {
            wholeAbsenceNet += dailyNet(cursor)
            cursor = cursor.plusDays(1)
        }

        val waitingDays = maintenance.employerWaitingDays?.coerceAtLeast(0) ?: 0
        cursor = absenceStart.plusDays(waitingDays.toLong())
        var indemnifiableNet = 0.0
        var targetNet = 0.0
        val indemnifiedDates = mutableListOf<LocalDate>()
        val warnings = mutableListOf<String>()

        maintenance.bands.forEach { band ->
            repeat(band.calendarDays.coerceAtLeast(0)) {
                if (!cursor.isBefore(absenceEndExclusive)) {
                    warnings += "Base nette maladie : le nombre de jours du barème dépasse la période réelle de l'arrêt."
                    return@repeat
                }
                val base = dailyNet(cursor)
                indemnifiableNet += base
                targetNet += base * band.targetNetRate.coerceIn(0.0, 1.0)
                indemnifiedDates += cursor
                cursor = cursor.plusDays(1)
            }
        }

        val expectedIndemnifiable = maintenance.bands.sumOf { it.calendarDays.coerceAtLeast(0) }
        if (indemnifiedDates.size != expectedIndemnifiable) {
            return Result(
                complete = false,
                theoreticalAbsenceNet = wholeAbsenceNet,
                theoreticalIndemnifiableNet = null,
                targetMaintenanceNet = null,
                ijssNetDeductedOnce = null,
                ijssDaysDeducted = null,
                employerComplementBeforeProvidentNet = null,
                employerProvidentNetDeducted = null,
                employerComplementFinalNet = null,
                finalComplementReliable = false,
                indemnifiableDays = indemnifiedDates.size,
                monthlyBases = normalizedMonthly.map { MonthlyBase(it.key, it.value) },
                warnings = warnings.distinct()
            )
        }

        val dailyIjssNet = allowance?.dailyNetBeforeIncomeTax?.takeIf { allowance.complete && it >= 0.0 }
        val payableDays = allowance?.payableDays?.takeIf { allowance.complete && it >= 0 }
        val ijssNet: Double?
        val ijssDays: Int?
        if (dailyIjssNet != null && payableDays != null) {
            // SicknessDailyAllowanceV2 applique le cas général avec 3 jours de carence.
            val ssPayableStart = absenceStart.plusDays(3)
            val overlapping = indemnifiedDates.count { !it.isBefore(ssPayableStart) }
                .coerceAtMost(payableDays)
            ijssDays = overlapping
            ijssNet = dailyIjssNet * overlapping
        } else {
            ijssDays = null
            ijssNet = null
            warnings += "Complément employeur : IJSS nette indisponible, aucun montant employeur final n'est inventé."
        }

        val complementBeforeProvident = ijssNet?.let { (targetNet - it).coerceAtLeast(0.0) }
        if (complementBeforeProvident != null) {
            warnings += "IJSS déduites une seule fois de la cible nette conventionnelle ; la subrogation change le destinataire, pas cette déduction."
        }

        val provident = SicknessProvidentOffsetV2.apply(
            employerComplementBeforeProvidentNet = complementBeforeProvident,
            treatment = providentTreatment,
            confirmedOverlapNetAmount = employerProvidentOverlapNetAmount
        )
        warnings += provident.warnings
        warnings += "Base nette théorique proratisée en jours calendaires ; les remboursements de frais doivent rester exclus de la base mensuelle."

        val preProvidentComplete = complementBeforeProvident != null &&
            warnings.none { it.contains("dépasse la période réelle") }
        val finalReliable = preProvidentComplete && provident.overlapConfirmed &&
            provident.finalEmployerComplementNet != null

        return Result(
            complete = preProvidentComplete,
            theoreticalAbsenceNet = wholeAbsenceNet,
            theoreticalIndemnifiableNet = indemnifiableNet,
            targetMaintenanceNet = targetNet,
            ijssNetDeductedOnce = ijssNet,
            ijssDaysDeducted = ijssDays,
            employerComplementBeforeProvidentNet = complementBeforeProvident,
            employerProvidentNetDeducted = provident.providentNetDeducted,
            employerComplementFinalNet = provident.finalEmployerComplementNet,
            finalComplementReliable = finalReliable,
            indemnifiableDays = indemnifiedDates.size,
            monthlyBases = normalizedMonthly.map { MonthlyBase(it.key, it.value) },
            warnings = warnings.distinct()
        )
    }

    private fun monthsBetween(start: LocalDate, endExclusive: LocalDate): List<YearMonth> {
        val out = mutableListOf<YearMonth>()
        var current = YearMonth.from(start)
        val last = YearMonth.from(endExclusive.minusDays(1))
        while (!current.isAfter(last)) {
            out += current
            current = current.plusMonths(1)
        }
        return out
    }

    private fun unavailable(message: String) = Result(
        complete = false,
        theoreticalAbsenceNet = null,
        theoreticalIndemnifiableNet = null,
        targetMaintenanceNet = null,
        ijssNetDeductedOnce = null,
        ijssDaysDeducted = null,
        employerComplementBeforeProvidentNet = null,
        employerProvidentNetDeducted = null,
        employerComplementFinalNet = null,
        finalComplementReliable = false,
        indemnifiableDays = 0,
        monthlyBases = emptyList(),
        warnings = listOf(message)
    )
}
