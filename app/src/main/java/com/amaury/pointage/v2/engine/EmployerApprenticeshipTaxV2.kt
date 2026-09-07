package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Taxe d'apprentissage employeur rattachée au coût mensuel de la rémunération.
 * La part principale et le solde sont séparés car leur calendrier de paiement diffère.
 * Les taux sont confirmés par entreprise/période : aucune exonération ni régime local n'est deviné.
 */
object EmployerApprenticeshipTaxV2 {
    data class Record(
        val id: String,
        /** Taux décimal, ex. 0,59 % = 0,0059 ; 0,44 % = 0,0044. */
        val principalRate: Double,
        /** Provision économique mensuelle du solde, ex. 0,09 % = 0,0009 ; 0 en Alsace-Moselle/exonération. */
        val balanceRate: Double,
        val effectiveFrom: YearMonth,
        val effectiveTo: YearMonth? = null,
        val source: String
    )

    data class Snapshot(
        val principalRate: Double?,
        val balanceRate: Double?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class Result(
        val principalAmount: Double?,
        val balanceAccrualAmount: Double?,
        val totalEmployerAmount: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val malformed = records.filter {
            it.source.isBlank() ||
                it.effectiveTo?.let { end -> end < it.effectiveFrom } == true ||
                !validRate(it.principalRate) || !validRate(it.balanceRate)
        }
        if (malformed.isNotEmpty()) {
            return Snapshot(null,null,null,false,listOf("Taxe d’apprentissage : une règle est incomplète ou incohérente ; aucun montant patronal n'est appliqué."))
        }
        val active = records.filter {
            period >= it.effectiveFrom && (it.effectiveTo == null || period <= it.effectiveTo)
        }
        if (active.isEmpty()) {
            return Snapshot(null,null,null,false,listOf("Taxe d’apprentissage : taux/applicabilité à confirmer pour ${period.monthValue.toString().padStart(2,'0')}/${period.year}."))
        }
        if (active.size > 1) {
            return Snapshot(null,null,null,false,listOf("Taxe d’apprentissage : plusieurs règles se chevauchent ; calcul patronal bloqué."))
        }
        val selected=active.single()
        return Snapshot(selected.principalRate,selected.balanceRate,selected.source,true,emptyList())
    }

    fun calculate(grossSocial: Double, principalRate: Double?, balanceRate: Double?): Result {
        if (principalRate == null || balanceRate == null) {
            return Result(null,null,null,false,listOf("Taxe d’apprentissage : taux confirmés manquants ; coût employeur incomplet."))
        }
        if (!validRate(principalRate) || !validRate(balanceRate)) {
            return Result(null,null,null,false,listOf("Taxe d’apprentissage : taux invalide ; aucun montant n'est calculé."))
        }
        val base=grossSocial.coerceAtLeast(0.0)
        val principal=base*principalRate
        val balance=base*balanceRate
        return Result(principal,balance,principal+balance,true,emptyList())
    }

    private fun validRate(rate: Double): Boolean = rate.isFinite() && rate >= 0.0 && rate <= 1.0
}
