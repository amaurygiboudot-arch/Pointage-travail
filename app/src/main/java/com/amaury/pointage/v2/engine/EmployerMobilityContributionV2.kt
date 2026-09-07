package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Versement mobilité employeur : aucune applicabilité ni aucun taux n'est déduit automatiquement.
 * Les règles doivent être confirmées pour une période et conserver une source vérifiable.
 */
object EmployerMobilityContributionV2 {
    enum class Status { APPLICABLE, NOT_APPLICABLE }

    data class Record(
        val id: String,
        val status: Status,
        /** Taux décimal confirmé : 2,50 % = 0,025. Null lorsque non applicable. */
        val rate: Double?,
        val effectiveFrom: YearMonth,
        val effectiveTo: YearMonth? = null,
        val source: String
    )

    data class Snapshot(
        val applicable: Boolean?,
        val rate: Double?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class Result(
        val employerAmount: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val malformed = records.filter { record ->
            record.effectiveTo?.let { it < record.effectiveFrom } == true ||
                record.source.isBlank() ||
                (record.status == Status.APPLICABLE && (record.rate == null || !record.rate.isFinite() || record.rate < 0.0 || record.rate > 1.0))
        }
        if (malformed.isNotEmpty()) {
            return Snapshot(
                applicable = null,
                rate = null,
                source = null,
                reliable = false,
                warnings = listOf("Versement mobilité employeur : une règle enregistrée est incomplète ou incohérente ; aucun taux n'est appliqué.")
            )
        }

        val active = records.filter { record ->
            period >= record.effectiveFrom && (record.effectiveTo == null || period <= record.effectiveTo)
        }
        if (active.isEmpty()) {
            return Snapshot(
                applicable = null,
                rate = null,
                source = null,
                reliable = false,
                warnings = listOf("Versement mobilité employeur : applicabilité et taux à confirmer pour ${period.monthValue.toString().padStart(2, '0')}/${period.year}.")
            )
        }
        if (active.size > 1) {
            return Snapshot(
                applicable = null,
                rate = null,
                source = null,
                reliable = false,
                warnings = listOf("Versement mobilité employeur : plusieurs règles se chevauchent sur la période ; calcul patronal bloqué.")
            )
        }

        val selected = active.single()
        return when (selected.status) {
            Status.NOT_APPLICABLE -> Snapshot(
                applicable = false,
                rate = 0.0,
                source = selected.source,
                reliable = true,
                warnings = emptyList()
            )
            Status.APPLICABLE -> Snapshot(
                applicable = true,
                rate = selected.rate,
                source = selected.source,
                reliable = true,
                warnings = emptyList()
            )
        }
    }

    fun calculate(grossSocial: Double, rate: Double?): Result {
        if (rate == null) {
            return Result(
                employerAmount = null,
                complete = false,
                warnings = listOf("Versement mobilité employeur : taux applicable à confirmer ; coût employeur incomplet.")
            )
        }
        if (!rate.isFinite() || rate < 0.0 || rate > 1.0) {
            return Result(
                employerAmount = null,
                complete = false,
                warnings = listOf("Versement mobilité employeur : taux invalide ; aucun montant patronal n'est calculé.")
            )
        }
        val base = grossSocial.coerceAtLeast(0.0)
        return Result(
            employerAmount = base * rate,
            complete = true,
            warnings = emptyList()
        )
    }
}
