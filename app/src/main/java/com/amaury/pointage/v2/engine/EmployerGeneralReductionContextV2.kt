package com.amaury.pointage.v2.engine

import java.time.YearMonth

/**
 * Faits mensuels nécessaires avant d'autoriser la RGDU automatique à devenir
 * un ajustement patronal exploitable dans le bulletin/coût employeur.
 *
 * Ces faits ne sont jamais déduits d'une absence de donnée : un mois sans
 * enregistrement reste inconnu. Les valeurs false sont au contraire des faits
 * confirmés et doivent être conservées telles quelles.
 */
object EmployerGeneralReductionContextV2 {
    data class Record(
        val id: String,
        val month: YearMonth,
        /** true uniquement si la présence du salarié couvre le mois selon les règles RGDU. */
        val fullMonthPresent: Boolean,
        /** true uniquement si le cas de droit commun attendu par le noyau RGDU a été vérifié. */
        val standardCommonLawCaseConfirmed: Boolean,
        /**
         * true uniquement si aucune autre réduction/exonération patronale ne doit être agrégée
         * au total mensuel utilisé par HoraTrack. Ce fait évite d'assimiler la seule RGDU au
         * total des réductions lorsqu'un autre dispositif pourrait s'ajouter.
         */
        val noOtherEmployerReductionConfirmed: Boolean,
        /** Source humaine vérifiable : bulletin, DSN, attestation employeur, contrôle qualifié, etc. */
        val source: String
    )

    data class Snapshot(
        val fullMonthPresent: Boolean?,
        val standardCommonLawCaseConfirmed: Boolean?,
        val noOtherEmployerReductionConfirmed: Boolean?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, month: YearMonth): Snapshot {
        val malformed = records.filter { it.id.isBlank() || it.source.isBlank() }
        if (malformed.isNotEmpty()) {
            return blocked(
                "RGDU : un contexte mensuel enregistré est incomplet ou sans source ; calcul automatique bloqué."
            )
        }

        val active = records.filter { it.month == month }
        if (active.isEmpty()) {
            return blocked(
                "RGDU : contexte factuel à confirmer pour ${month.monthValue.toString().padStart(2, '0')}/${month.year} (présence, droit commun et autres réductions)."
            )
        }
        if (active.size > 1) {
            return blocked(
                "RGDU : plusieurs contextes factuels existent pour le même mois ; calcul automatique bloqué."
            )
        }

        val selected = active.single()
        return Snapshot(
            fullMonthPresent = selected.fullMonthPresent,
            standardCommonLawCaseConfirmed = selected.standardCommonLawCaseConfirmed,
            noOtherEmployerReductionConfirmed = selected.noOtherEmployerReductionConfirmed,
            source = selected.source,
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun blocked(message: String) = Snapshot(
        fullMonthPresent = null,
        standardCommonLawCaseConfirmed = null,
        noOtherEmployerReductionConfirmed = null,
        source = null,
        reliable = false,
        warnings = listOf(message)
    )
}
