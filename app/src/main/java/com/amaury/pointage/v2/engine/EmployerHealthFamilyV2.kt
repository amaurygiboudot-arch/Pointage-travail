package com.amaury.pointage.v2.engine

import java.time.YearMonth

/** Cotisations patronales maladie et allocations familiales avec taux confirmés par période. */
object EmployerHealthFamilyV2 {
    data class Record(
        val id: String,
        /** Taux décimal : 13 % = 0,13. */
        val healthRate: Double,
        /** Taux décimal : 5,25 % = 0,0525. */
        val familyRate: Double,
        val effectiveFrom: YearMonth,
        val effectiveTo: YearMonth? = null,
        val source: String
    )

    data class Snapshot(
        val healthRate: Double?,
        val familyRate: Double?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class Result(
        val healthAmount: Double?,
        val familyAmount: Double?,
        val totalEmployerAmount: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val malformed = records.filter {
            it.source.isBlank() ||
                it.effectiveTo?.let { end -> end < it.effectiveFrom } == true ||
                !validRate(it.healthRate) || !validRate(it.familyRate)
        }
        if (malformed.isNotEmpty()) {
            return Snapshot(null, null, null, false, listOf("Maladie/allocations familiales employeur : une règle est incomplète ou incohérente ; aucun taux n'est appliqué."))
        }
        val active = records.filter {
            period >= it.effectiveFrom && (it.effectiveTo == null || period <= it.effectiveTo)
        }
        if (active.isEmpty()) {
            return Snapshot(null, null, null, false, listOf("Maladie/allocations familiales employeur : taux confirmés à renseigner pour ${period.monthValue.toString().padStart(2, '0')}/${period.year}."))
        }
        if (active.size > 1) {
            return Snapshot(null, null, null, false, listOf("Maladie/allocations familiales employeur : plusieurs règles se chevauchent ; calcul patronal bloqué."))
        }
        val selected = active.single()
        return Snapshot(selected.healthRate, selected.familyRate, selected.source, true, emptyList())
    }

    fun calculate(grossSocial: Double, healthRate: Double?, familyRate: Double?): Result {
        if (healthRate == null || familyRate == null) {
            return Result(null, null, null, false, listOf("Maladie/allocations familiales employeur : taux confirmés manquants ; coût employeur incomplet."))
        }
        if (!validRate(healthRate) || !validRate(familyRate)) {
            return Result(null, null, null, false, listOf("Maladie/allocations familiales employeur : taux invalide ; aucun montant n'est calculé."))
        }
        val base = grossSocial.coerceAtLeast(0.0)
        val health = base * healthRate
        val family = base * familyRate
        return Result(health, family, health + family, true, emptyList())
    }

    private fun validRate(rate: Double): Boolean = rate.isFinite() && rate >= 0.0 && rate <= 1.0
}
