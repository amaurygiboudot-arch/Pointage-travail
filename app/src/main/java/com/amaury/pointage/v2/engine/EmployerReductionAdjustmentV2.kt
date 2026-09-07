package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Réductions/exonérations patronales confirmées pour un mois (RGDU, Lodeom, etc.).
 * Aucun calcul légal complexe n'est deviné ici : la valeur vient d'une source vérifiable
 * (DSN, bulletin ou calcul employeur validé). Un montant de 0 confirme l'absence de réduction.
 */
object EmployerReductionAdjustmentV2 {
    data class Record(
        val id: String,
        val month: YearMonth,
        val totalReductionAmount: Double,
        val source: String,
        val note: String = ""
    )

    data class Snapshot(
        val amount: Double?,
        val source: String?,
        val note: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, month: YearMonth): Snapshot {
        val malformed = records.filter {
            !it.totalReductionAmount.isFinite() || it.totalReductionAmount < 0.0 || it.source.isBlank()
        }
        if (malformed.isNotEmpty()) {
            return Snapshot(null,null,null,false,listOf("Réductions/exonérations patronales : une donnée enregistrée est incohérente ; aucun ajustement n'est appliqué."))
        }
        val active = records.filter { it.month == month }
        if (active.isEmpty()) {
            return Snapshot(null,null,null,false,listOf("Réductions/exonérations patronales : montant total à confirmer pour ${month.monthValue.toString().padStart(2,'0')}/${month.year}, même s'il est nul."))
        }
        if (active.size > 1) {
            return Snapshot(null,null,null,false,listOf("Réductions/exonérations patronales : plusieurs totaux existent pour le même mois ; ajustement bloqué."))
        }
        val selected=active.single()
        return Snapshot(selected.totalReductionAmount,selected.source,selected.note,true,emptyList())
    }
}
