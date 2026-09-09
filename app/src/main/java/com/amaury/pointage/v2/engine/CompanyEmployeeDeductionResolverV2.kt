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
                    warnings = listOf("${kind.label} : période ou montant daté invalide, calcul bloqué.")
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
                        source = record.source?.trim()?.takeIf { it.isNotBlank() },
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
     * Compatibilité progressive avec les anciennes valeurs sans période.
     * Elles restent utilisables comme estimation tant qu'aucun enregistrement daté du
     * type n'existe, mais ne deviennent jamais une référence exacte.
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
                    legacyUsed = true,
                    warnings = listOf("${kind.label} : ancienne valeur non datée invalide.")
                )
                else -> Value(
                    amount = legacy,
                    source = "Ancienne fiche Salaire sans période",
                    hasDatedRecords = false,
                    reliable = false,
                    legacyUsed = true,
                    warnings = listOf("${kind.label} : ancienne valeur non datée utilisée ; période à confirmer.")
                )
            }
        }
        return Snapshot(values, values.values.flatMap { it.warnings }.distinct())
    }

    private fun valid(record: Record): Boolean {
        if (record.id.isBlank() || !record.amount.isFinite() || record.amount < 0.0) return false
        val start = record.effectiveFrom ?: return false
        val end = record.effectiveTo
        return end == null || end >= start
    }
}
