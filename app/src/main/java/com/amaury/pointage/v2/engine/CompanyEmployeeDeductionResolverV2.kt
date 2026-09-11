package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Résout les retenues salarié/entreprise explicitement confirmées pour un mois.
 *
 * Dès qu'un type possède au moins un enregistrement daté, l'ancien montant sans date
 * ne doit plus combler silencieusement les trous de période. Un chevauchement ou un
 * enregistrement invalide bloque uniquement le type concerné.
 */
object CompanyEmployeeDeductionResolverV2 {
    enum class Kind(val label: String) {
        MUTUAL_EMPLOYEE("Mutuelle salariale"),
        PROVIDENT_EMPLOYEE("Prévoyance salariale entreprise"),
        TRANSPORT_EMPLOYEE("Retenue transport"),
        EMPLOYER_PROTECTION_TAXABLE("Part employeur mutuelle/prévoyance réintégrable au net imposable"),
        EMPLOYER_PROTECTION_CSG_CRDS_BASE("Part employeur protection sociale complémentaire soumise à CSG/CRDS"),
        EMPLOYEE_PROVIDENT_NON_DEDUCTIBLE("Part salariale de prévoyance non déductible")
    }

    data class Record(
        val id: String,
        val kind: Kind,
        val amount: Double,
        val effectiveFrom: YearMonth?,
        val effectiveTo: YearMonth? = null,
        val source: String? = null
    )

    data class Value(
        val amount: Double?,
        val source: String?,
        val hasDatedRecords: Boolean,
        val reliable: Boolean,
        val legacyUsed: Boolean = false,
        val warnings: List<String> = emptyList()
    )

    data class Snapshot(
        val values: Map<Kind, Value>,
        val warnings: List<String>
    ) {
        operator fun get(kind: Kind): Value = values.getValue(kind)
    }

    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        val values = linkedMapOf<Kind, Value>()

        Kind.entries.forEach { kind ->
            val own = records.filter { it.kind == kind }
            if (own.isEmpty()) {
                values[kind] = Value(
                    amount = null,
                    source = null,
                    hasDatedRecords = false,
                    reliable = true
                )
                return@forEach
            }

            val invalid = own.filterNot(::valid)
            if (invalid.isNotEmpty()) {
                values[kind] = Value(
                    amount = null,
                    source = null,
                    hasDatedRecords = true,
                    reliable = false,
                    warnings = listOf("${kind.label} : période, montant ou source datée invalide, calcul bloqué.")
                )
                return@forEach
            }

            val applicable = own.filter { record ->
                val start = record.effectiveFrom!!
                period >= start && (record.effectiveTo == null || period <= record.effectiveTo)
            }

            values[kind] = when (applicable.size) {
                0 -> Value(
                    amount = null,
                    source = null,
                    hasDatedRecords = true,
                    reliable = false,
                    warnings = listOf("${kind.label} : aucune période confirmée pour $period.")
                )
                1 -> applicable.single().let { record ->
                    Value(
                        amount = record.amount,
                        source = record.source!!.trim(),
                        hasDatedRecords = true,
                        reliable = true
                    )
                }
                else -> Value(
                    amount = null,
                    source = null,
                    hasDatedRecords = true,
                    reliable = false,
                    warnings = listOf("${kind.label} : plusieurs périodes se chevauchent pour $period, calcul bloqué.")
                )
            }
        }

        return Snapshot(values, values.values.flatMap { it.warnings }.distinct())
    }

    /**
     * Compatibilité de migration avec les anciennes valeurs sans période.
     *
     * Une valeur historique reste détectée pour expliquer ce qui doit être confirmé, mais elle
     * ne fournit plus jamais de montant au runtime Salaire V2. Seul un enregistrement daté et
     * sourcé peut participer au calcul.
     */
    fun withLegacyFallback(
        snapshot: Snapshot,
        legacyAmounts: Map<Kind, Double?>
    ): Snapshot {
        val values = linkedMapOf<Kind, Value>()
        Kind.entries.forEach { kind ->
            val dated = snapshot[kind]
            if (dated.hasDatedRecords) {
                values[kind] = dated
                return@forEach
            }

            val legacy = legacyAmounts[kind]
            values[kind] = when {
                legacy == null -> dated
                !legacy.isFinite() || legacy < 0.0 -> Value(
                    amount = null,
                    source = null,
                    hasDatedRecords = false,
                    reliable = false,
                    legacyUsed = false,
                    warnings = listOf("${kind.label} : ancienne valeur non datée invalide ; confirmer une valeur datée et sourcée.")
                )
                else -> Value(
                    amount = null,
                    source = null,
                    hasDatedRecords = false,
                    reliable = false,
                    legacyUsed = false,
                    warnings = listOf("${kind.label} : ancienne valeur non datée détectée mais non utilisée par Salaire V2 ; confirmer sa période et sa source.")
                )
            }
        }
        return Snapshot(values, values.values.flatMap { it.warnings }.distinct())
    }

    private fun valid(record: Record): Boolean {
        if (record.id.isBlank() || !record.amount.isFinite() || record.amount < 0.0) return false
        if (record.source?.trim().isNullOrEmpty()) return false
        val start = record.effectiveFrom ?: return false
        val end = record.effectiveTo
        return end == null || end >= start
    }
}
