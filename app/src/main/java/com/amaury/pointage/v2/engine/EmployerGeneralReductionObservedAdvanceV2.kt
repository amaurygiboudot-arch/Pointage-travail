package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Montants RGDU mensuels réellement constatés/appliqués, issus d'une source humaine vérifiable
 * (DSN, bulletin, attestation employeur, contrôle qualifié...).
 *
 * Ce modèle est volontairement séparé du total global des réductions patronales : un montant
 * enregistré ici représente uniquement la RGDU du mois concerné. Une absence de mois n'est
 * jamais interprétée comme 0 €.
 */
object EmployerGeneralReductionObservedAdvanceV2 {
    data class Record(
        val id: String,
        val month: YearMonth,
        val amount: Double,
        val source: String
    )

    enum class YearState {
        COMPLETE_CONFIRMED,
        INCOMPLETE,
        INVALID
    }

    data class YearSnapshot(
        val state: YearState,
        val monthlyAdvances: List<EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance>,
        val sources: List<String>,
        val warnings: List<String>
    ) {
        val completeConfirmed: Boolean
            get() = state == YearState.COMPLETE_CONFIRMED
    }

    fun resolveYear(records: List<Record>, year: Int): YearSnapshot {
        if (year <= 0) {
            return invalid("RGDU observée : année invalide.")
        }

        val malformed = records.any {
            it.id.isBlank() ||
                it.source.isBlank() ||
                !it.amount.isFinite() ||
                it.amount < 0.0
        }
        if (malformed) {
            return invalid(
                "RGDU observée : un montant enregistré est incomplet ou incohérent ; base historique bloquée."
            )
        }

        val active = records.filter { it.month.year == year }
        val duplicateMonths = active.groupingBy { it.month.monthValue }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateMonths.isNotEmpty()) {
            return invalid(
                "RGDU observée : plusieurs montants existent pour le même mois en $year ; base historique bloquée."
            )
        }

        val expectedMonths = (1..12).toSet()
        val presentMonths = active.map { it.month.monthValue }.toSet()
        if (presentMonths != expectedMonths) {
            val missing = (expectedMonths - presentMonths).sorted().joinToString(", ")
            return YearSnapshot(
                state = YearState.INCOMPLETE,
                monthlyAdvances = emptyList(),
                sources = active.map { it.source.trim() }.filter { it.isNotBlank() }.distinct(),
                warnings = listOf(
                    "RGDU observée : les 12 montants mensuels ne sont pas tous confirmés pour $year ; mois manquants : $missing."
                )
            )
        }

        val ordered = active.sortedBy { it.month.monthValue }
        return YearSnapshot(
            state = YearState.COMPLETE_CONFIRMED,
            monthlyAdvances = ordered.map {
                EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(
                    month = it.month.monthValue,
                    amount = it.amount
                )
            },
            sources = ordered.map { it.source.trim() }.distinct(),
            warnings = emptyList()
        )
    }

    private fun invalid(message: String) = YearSnapshot(
        state = YearState.INVALID,
        monthlyAdvances = emptyList(),
        sources = emptyList(),
        warnings = listOf(message)
    )
}
