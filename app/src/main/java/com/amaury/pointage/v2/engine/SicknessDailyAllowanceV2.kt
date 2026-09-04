package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.min

/**
 * Estimation des IJSS maladie du salarié à partir de bulletins réels confirmés.
 *
 * Cas général uniquement : 3 salaires bruts précédant l'arrêt, SJB = somme / 91,25,
 * IJ brute = 50 % du SJB, avec plafond réglementaire et 3 jours de carence.
 * Les exceptions de carence, l'ouverture des droits et la subrogation restent à confirmer.
 */
object SicknessDailyAllowanceV2 {
    private const val CSG_RATE = 0.062
    private const val CRDS_RATE = 0.005

    data class SalaryMonth(val period: YearMonth, val gross: Double)

    data class Result(
        val complete: Boolean,
        val dailyGross: Double?,
        val payableDays: Int?,
        val estimatedGrossTotal: Double?,
        val referenceMonths: List<SalaryMonth>,
        val warnings: List<String>,
        /** IJSS après CSG/CRDS, avant prélèvement à la source. */
        val dailyNetBeforeIncomeTax: Double? = null,
        /** Total IJSS après CSG/CRDS, avant prélèvement à la source. */
        val estimatedNetBeforeIncomeTaxTotal: Double? = null
    )

    fun calculate(
        absenceStart: LocalDate,
        absenceEndExclusive: LocalDate,
        confirmedGrossByMonth: Map<YearMonth, Double>
    ): Result {
        if (!absenceEndExclusive.isAfter(absenceStart)) {
            return Result(false, null, null, null, emptyList(), listOf("Arrêt maladie : période invalide."))
        }

        val cap = regulation(absenceStart)
            ?: return Result(
                complete = false,
                dailyGross = null,
                payableDays = null,
                estimatedGrossTotal = null,
                referenceMonths = emptyList(),
                warnings = listOf("IJSS maladie : barème HoraTrack non intégré pour la date de début de cet arrêt.")
            )

        val startMonth = YearMonth.from(absenceStart)
        val required = listOf(startMonth.minusMonths(3), startMonth.minusMonths(2), startMonth.minusMonths(1))
        val months = required.mapNotNull { ym ->
            confirmedGrossByMonth[ym]?.takeIf { it >= 0.0 }?.let { SalaryMonth(ym, it) }
        }
        if (months.size != 3) {
            val missing = required.filter { ym -> months.none { it.period == ym } }
            return Result(
                complete = false,
                dailyGross = null,
                payableDays = null,
                estimatedGrossTotal = null,
                referenceMonths = months,
                warnings = listOf(
                    "IJSS maladie : bulletin brut réel confirmé manquant pour ${missing.joinToString { "%02d/%04d".format(it.monthValue, it.year) }}."
                )
            )
        }

        // Le plafond mensuel est appliqué à chaque salaire de la période de référence.
        val cappedTotal = months.sumOf { min(it.gross, cap.monthlySalaryCap) }
        val salaryDailyBase = cappedTotal / 91.25
        val daily = min(salaryDailyBase * 0.50, cap.maxDailyGross)
        val calendarDays = ChronoUnit.DAYS.between(absenceStart, absenceEndExclusive).toInt().coerceAtLeast(0)
        val payableDays = (calendarDays - 3).coerceAtLeast(0)
        val total = daily * payableDays

        // Référence Urssaf : les IJSS supportent 6,20 % de CSG + 0,50 % de CRDS,
        // sans abattement de 1,75 %. Le PAS est volontairement exclu ici afin de
        // comparer une rémunération nette avant impôt à une IJSS nette avant impôt.
        val socialFactor = 1.0 - CSG_RATE - CRDS_RATE
        val dailyNetBeforeIncomeTax = (daily * socialFactor).coerceAtLeast(0.0)
        val totalNetBeforeIncomeTax = dailyNetBeforeIncomeTax * payableDays

        return Result(
            complete = true,
            dailyGross = daily,
            payableDays = payableDays,
            estimatedGrossTotal = total,
            referenceMonths = months,
            warnings = listOf(
                "IJSS maladie : estimation du cas général si les droits Assurance Maladie sont ouverts.",
                "Carence de 3 jours appliquée ; les exceptions (ALD, reprise de moins de 48 h, etc.) restent à confirmer.",
                "IJSS nette avant impôt estimée après CSG 6,20 % et CRDS 0,50 %, sans abattement.",
                "Subrogation et complément/maintien employeur non déduits de cette estimation IJSS."
            ),
            dailyNetBeforeIncomeTax = dailyNetBeforeIncomeTax,
            estimatedNetBeforeIncomeTaxTotal = totalNetBeforeIncomeTax
        )
    }

    private data class Regulation(val monthlySalaryCap: Double, val maxDailyGross: Double)

    private fun regulation(start: LocalDate): Regulation? = when {
        !start.isBefore(LocalDate.of(2026, 7, 1)) -> Regulation(2613.83, 42.97)
        !start.isBefore(LocalDate.of(2026, 6, 1)) -> Regulation(2522.52, 41.95)
        else -> null
    }
}
