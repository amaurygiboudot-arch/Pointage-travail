package com.amaury.pointage.v2.engine

import java.time.YearMonth

/** Résout les avantages en nature explicitement valorisés pour le mois de paie. */
object CompanyBenefitInKindResolverV2 {
    enum class Kind { MONTHLY, ONE_OFF }

    data class Record(
        val id: String,
        val label: String,
        val grossValue: Double,
        val kind: Kind,
        val effectiveFrom: YearMonth? = null,
        val effectiveTo: YearMonth? = null,
        val paymentMonth: YearMonth? = null
    )

    data class Applied(
        val id: String,
        val label: String,
        val grossValue: Double
    )

    data class Snapshot(
        val applied: List<Applied>,
        val totalGross: Double,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val applied = mutableListOf<Applied>()
        val warnings = mutableListOf<String>()
        var reliable = true

        records.forEach { record ->
            val value = record.grossValue.takeIf { it.isFinite() && it > 0.0 }
            if (record.label.isBlank() || value == null) {
                reliable = false
                warnings += "Avantage en nature invalide : libellé ou valeur brute à vérifier."
                return@forEach
            }
            when (record.kind) {
                Kind.MONTHLY -> {
                    val start = record.effectiveFrom
                    if (start == null) {
                        reliable = false
                        warnings += "Avantage en nature mensuel « ${record.label} » : mois de début manquant."
                        return@forEach
                    }
                    val end = record.effectiveTo
                    if (end != null && end < start) {
                        reliable = false
                        warnings += "Avantage en nature mensuel « ${record.label} » : période invalide."
                        return@forEach
                    }
                    if (period >= start && (end == null || period <= end)) {
                        applied += Applied(record.id, record.label, value)
                    }
                }
                Kind.ONE_OFF -> {
                    val payment = record.paymentMonth
                    if (payment == null) {
                        reliable = false
                        warnings += "Avantage en nature ponctuel « ${record.label} » : mois d'application manquant."
                        return@forEach
                    }
                    if (payment == period) applied += Applied(record.id, record.label, value)
                }
            }
        }

        return Snapshot(
            applied = applied,
            totalGross = applied.sumOf { it.grossValue },
            reliable = reliable,
            warnings = warnings.distinct()
        )
    }
}
