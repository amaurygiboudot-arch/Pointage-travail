package com.amaury.pointage.v2.engine

import java.time.YearMonth

/** Résout uniquement les primes explicitement enregistrées et dues sur le mois demandé. */
object CompanyPremiumResolverV2 {
    enum class Kind { MONTHLY, ONE_OFF }

    data class Record(
        val id: String,
        val label: String,
        val grossAmount: Double,
        val kind: Kind,
        val effectiveFrom: YearMonth? = null,
        val effectiveTo: YearMonth? = null,
        val paymentMonth: YearMonth? = null
    )

    data class Applied(
        val id: String,
        val label: String,
        val grossAmount: Double
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
            val amount = record.grossAmount.takeIf { it.isFinite() && it >= 0.0 }
            if (record.label.isBlank() || amount == null) {
                reliable = false
                warnings += "Prime entreprise invalide : libellé ou montant brut à vérifier."
                return@forEach
            }
            when (record.kind) {
                Kind.MONTHLY -> {
                    val start = record.effectiveFrom
                    if (start == null) {
                        reliable = false
                        warnings += "Prime mensuelle « ${record.label} » : mois de début manquant."
                        return@forEach
                    }
                    val end = record.effectiveTo
                    if (end != null && end < start) {
                        reliable = false
                        warnings += "Prime mensuelle « ${record.label} » : période invalide."
                        return@forEach
                    }
                    if (period >= start && (end == null || period <= end)) {
                        applied += Applied(record.id, record.label, amount)
                    }
                }
                Kind.ONE_OFF -> {
                    val payment = record.paymentMonth
                    if (payment == null) {
                        reliable = false
                        warnings += "Prime ponctuelle « ${record.label} » : mois de versement manquant."
                        return@forEach
                    }
                    if (payment == period) applied += Applied(record.id, record.label, amount)
                }
            }
        }

        return Snapshot(
            applied = applied,
            totalGross = applied.sumOf { it.grossAmount },
            reliable = reliable,
            warnings = buildList {
                addAll(warnings)
                if (applied.isNotEmpty()) {
                    add("Primes contractuelles/personnelles appliquées : ${applied.joinToString { "${it.label} (${String.format(java.util.Locale.FRANCE, "%.2f €", it.grossAmount)})" }}.")
                }
            }.distinct()
        )
    }
}
