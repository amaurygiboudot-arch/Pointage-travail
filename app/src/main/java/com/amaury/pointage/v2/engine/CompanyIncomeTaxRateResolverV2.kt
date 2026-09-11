package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Résout le taux personnel de prélèvement à la source confirmé pour un mois.
 *
 * Dès qu'un taux daté existe, l'ancien taux sans période ne comble plus silencieusement
 * les mois non couverts. Les chevauchements, périodes invalides ou sources absentes
 * restent bloquants.
 */
object CompanyIncomeTaxRateResolverV2 {
    data class Record(
        val id: String,
        val ratePercent: Double,
        val effectiveFrom: YearMonth?,
        val effectiveTo: YearMonth? = null,
        val source: String? = null
    )

    data class Snapshot(
        /** Taux décimal utilisé par le moteur, ex. 3,2 % = 0,032. */
        val rate: Double?,
        val ratePercent: Double?,
        val source: String?,
        val hasDatedRecords: Boolean,
        val reliable: Boolean,
        val legacyUsed: Boolean = false,
        val warnings: List<String> = emptyList()
    )

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        if (records.isEmpty()) {
            return Snapshot(
                rate = null,
                ratePercent = null,
                source = null,
                hasDatedRecords = false,
                reliable = true
            )
        }

        if (records.any { !valid(it) }) {
            return Snapshot(
                rate = null,
                ratePercent = null,
                source = null,
                hasDatedRecords = true,
                reliable = false,
                warnings = listOf("PAS : taux, période ou source datée invalide ; calcul après impôt bloqué.")
            )
        }

        val applicable = records.filter { record ->
            val start = record.effectiveFrom!!
            period >= start && (record.effectiveTo == null || period <= record.effectiveTo)
        }

        return when (applicable.size) {
            0 -> Snapshot(
                rate = null,
                ratePercent = null,
                source = null,
                hasDatedRecords = true,
                reliable = false,
                warnings = listOf("PAS : aucun taux personnel confirmé pour $period.")
            )
            1 -> applicable.single().let { record ->
                Snapshot(
                    rate = record.ratePercent / 100.0,
                    ratePercent = record.ratePercent,
                    source = record.source!!.trim(),
                    hasDatedRecords = true,
                    reliable = true
                )
            }
            else -> Snapshot(
                rate = null,
                ratePercent = null,
                source = null,
                hasDatedRecords = true,
                reliable = false,
                warnings = listOf("PAS : plusieurs périodes de taux se chevauchent pour $period ; calcul après impôt bloqué.")
            )
        }
    }

    /**
     * Compatibilité de migration avec le taux historique sans période.
     *
     * La valeur historique reste détectée afin d'expliquer à l'utilisateur ce qui doit être
     * confirmé, mais elle ne fournit plus jamais de taux au runtime Salaire V2. Seul un taux
     * daté et sourcé peut alimenter le calcul après impôt.
     */
    fun withLegacyFallback(snapshot: Snapshot, legacyRatePercent: Double?): Snapshot {
        if (snapshot.hasDatedRecords) return snapshot
        val legacy = legacyRatePercent ?: return snapshot
        if (!legacy.isFinite() || legacy < 0.0 || legacy > 100.0) {
            return Snapshot(
                rate = null,
                ratePercent = null,
                source = null,
                hasDatedRecords = false,
                reliable = false,
                legacyUsed = false,
                warnings = listOf("PAS : ancien taux sans période invalide ; confirmer un taux daté et sourcé.")
            )
        }
        return Snapshot(
            rate = null,
            ratePercent = null,
            source = null,
            hasDatedRecords = false,
            reliable = false,
            legacyUsed = false,
            warnings = listOf("PAS : ancien taux sans période détecté mais non utilisé par Salaire V2 ; confirmer sa période et sa source.")
        )
    }

    private fun valid(record: Record): Boolean {
        if (record.id.isBlank()) return false
        if (!record.ratePercent.isFinite() || record.ratePercent < 0.0 || record.ratePercent > 100.0) return false
        val start = record.effectiveFrom ?: return false
        val end = record.effectiveTo
        if (end != null && end < start) return false
        return !record.source.isNullOrBlank()
    }
}
