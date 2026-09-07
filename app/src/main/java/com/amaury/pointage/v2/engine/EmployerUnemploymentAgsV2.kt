package com.amaury.pointage.v2.engine

import java.time.YearMonth
import kotlin.math.min

/**
 * Assurance chômage + AGS employeur.
 * Les taux sont confirmés par entreprise/période : aucun taux de droit commun
 * n'est imposé automatiquement car bonus-malus et cas ETT peuvent s'appliquer.
 */
object EmployerUnemploymentAgsV2 {
    data class Record(
        val id: String,
        /** Taux décimal : 4,00 % = 0,04. */
        val unemploymentRate: Double,
        /** Taux décimal : 0,25 % = 0,0025. */
        val agsRate: Double,
        val effectiveFrom: YearMonth,
        val effectiveTo: YearMonth? = null,
        val source: String
    )

    data class Snapshot(
        val unemploymentRate: Double?,
        val agsRate: Double?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class Result(
        val baseAmount: Double?,
        val unemploymentAmount: Double?,
        val agsAmount: Double?,
        val totalEmployerAmount: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val malformed = records.filter { record ->
            record.source.isBlank() ||
                record.effectiveTo?.let { it < record.effectiveFrom } == true ||
                !validRate(record.unemploymentRate) ||
                !validRate(record.agsRate)
        }
        if (malformed.isNotEmpty()) {
            return Snapshot(
                unemploymentRate = null,
                agsRate = null,
                source = null,
                reliable = false,
                warnings = listOf("Chômage/AGS employeur : une règle enregistrée est incomplète ou incohérente ; aucun taux n'est appliqué.")
            )
        }

        val active = records.filter { record ->
            period >= record.effectiveFrom && (record.effectiveTo == null || period <= record.effectiveTo)
        }
        if (active.isEmpty()) {
            return Snapshot(
                unemploymentRate = null,
                agsRate = null,
                source = null,
                reliable = false,
                warnings = listOf("Chômage/AGS employeur : taux confirmés à renseigner pour ${period.monthValue.toString().padStart(2, '0')}/${period.year}.")
            )
        }
        if (active.size > 1) {
            return Snapshot(
                unemploymentRate = null,
                agsRate = null,
                source = null,
                reliable = false,
                warnings = listOf("Chômage/AGS employeur : plusieurs règles se chevauchent sur la période ; calcul patronal bloqué.")
            )
        }

        val selected = active.single()
        return Snapshot(
            unemploymentRate = selected.unemploymentRate,
            agsRate = selected.agsRate,
            source = selected.source,
            reliable = true,
            warnings = emptyList()
        )
    }

    fun calculate(
        grossSocial: Double,
        fourTimesApplicableCeiling: Double?,
        unemploymentRate: Double?,
        agsRate: Double?
    ): Result {
        if (fourTimesApplicableCeiling == null || !fourTimesApplicableCeiling.isFinite() || fourTimesApplicableCeiling < 0.0) {
            return Result(null, null, null, null, false, listOf("Chômage/AGS employeur : plafond social applicable indisponible ; coût employeur incomplet."))
        }
        if (unemploymentRate == null || agsRate == null) {
            return Result(null, null, null, null, false, listOf("Chômage/AGS employeur : taux confirmés manquants ; coût employeur incomplet."))
        }
        if (!validRate(unemploymentRate) || !validRate(agsRate)) {
            return Result(null, null, null, null, false, listOf("Chômage/AGS employeur : taux invalide ; aucun montant patronal n'est calculé."))
        }

        val base = min(grossSocial.coerceAtLeast(0.0), fourTimesApplicableCeiling)
        val unemployment = base * unemploymentRate
        val ags = base * agsRate
        return Result(
            baseAmount = base,
            unemploymentAmount = unemployment,
            agsAmount = ags,
            totalEmployerAmount = unemployment + ags,
            complete = true,
            warnings = emptyList()
        )
    }

    private fun validRate(rate: Double): Boolean = rate.isFinite() && rate >= 0.0 && rate <= 1.0
}
