package com.amaury.pointage.v2.engine

import java.time.YearMonth
import kotlin.math.min

/** FNAL et formation professionnelle 2026 selon la tranche d'effectif social confirmée. */
object EmployerWorkforceContributionsV2 {
    enum class Band { UNDER_11, FROM_11_TO_49, AT_LEAST_50 }

    data class Record(
        val id: String,
        val band: Band,
        val effectiveFrom: YearMonth,
        val effectiveTo: YearMonth? = null,
        val source: String
    )

    data class Snapshot(
        val band: Band?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class Result(
        val fnalAmount: Double?,
        val trainingAmount: Double?,
        val totalEmployerAmount: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val malformed = records.filter {
            it.source.isBlank() || it.effectiveTo?.let { end -> end < it.effectiveFrom } == true
        }
        if (malformed.isNotEmpty()) {
            return Snapshot(null, null, false, listOf("Effectif employeur : une règle enregistrée est incomplète ou incohérente ; FNAL/formation non calculés."))
        }
        val active = records.filter {
            period >= it.effectiveFrom && (it.effectiveTo == null || period <= it.effectiveTo)
        }
        if (active.isEmpty()) {
            return Snapshot(null, null, false, listOf("Effectif employeur : tranche <11 / 11–49 / ≥50 à confirmer pour ${period.monthValue.toString().padStart(2, '0')}/${period.year} ; FNAL/formation incomplets."))
        }
        if (active.size > 1) {
            return Snapshot(null, null, false, listOf("Effectif employeur : plusieurs tranches se chevauchent sur la période ; FNAL/formation bloqués."))
        }
        val selected = active.single()
        return Snapshot(selected.band, selected.source, true, emptyList())
    }

    fun calculate(
        grossSocial: Double,
        applicableMonthlyCeiling: Double?,
        year: Int,
        band: Band?
    ): Result {
        if (year != 2026) {
            return Result(null, null, null, false, listOf("FNAL/formation : barème non intégré pour $year."))
        }
        if (band == null) {
            return Result(null, null, null, false, listOf("FNAL/formation : tranche d'effectif à confirmer ; coût employeur incomplet."))
        }
        if (applicableMonthlyCeiling == null || !applicableMonthlyCeiling.isFinite() || applicableMonthlyCeiling < 0.0) {
            return Result(null, null, null, false, listOf("FNAL/formation : plafond social applicable indisponible."))
        }

        val gross = grossSocial.coerceAtLeast(0.0)
        val fnalBase = when (band) {
            Band.UNDER_11, Band.FROM_11_TO_49 -> min(gross, applicableMonthlyCeiling)
            Band.AT_LEAST_50 -> gross
        }
        val fnalRate = if (band == Band.AT_LEAST_50) 0.0050 else 0.0010
        val trainingRate = if (band == Band.UNDER_11) 0.0055 else 0.0100
        val fnal = fnalBase * fnalRate
        val training = gross * trainingRate
        return Result(fnal, training, fnal + training, true, emptyList())
    }
}
