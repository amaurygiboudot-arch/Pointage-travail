package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2

/**
 * Faits annuels nécessaires avant d'autoriser le calcul RGDU annuel standard.
 *
 * Ce contexte est volontairement distinct des valeurs calculées : il ne déduit jamais une
 * stabilité annuelle depuis les seuls paramètres courants du salarié. L'absence d'enregistrement
 * reste inconnue et bloque le calcul annuel automatique.
 */
object EmployerGeneralReductionAnnualContextV2 {
    data class Record(
        val id: String,
        val year: Int,
        /** true uniquement si le contrat couvre l'année civile complète dans le cas traité. */
        val fullCalendarYearPresent: Boolean,
        /** true uniquement si le cas de droit commun RGDU est confirmé pour toute l'année. */
        val standardCommonLawCaseConfirmed: Boolean,
        /**
         * true uniquement si type de contrat, durée contractuelle et tranche d'effectif utilisées
         * par le moteur annuel sont confirmés comme stables sur toute l'année.
         * null est conservé pour une migration sûre d'un éventuel ancien enregistrement.
         */
        val homogeneousAnnualParametersConfirmed: Boolean? = null,
        /** Source humaine vérifiable : DSN, bulletins, attestation employeur, contrôle qualifié, etc. */
        val source: String,
        /** Valeurs exactes confirmées stables sur l'année ; null reste inconnu, jamais recopié du profil courant. */
        val confirmedWorkforceBand: EmployerWorkforceContributionsV2.Band? = null,
        val confirmedContractType: ContractTypeV2? = null,
        val confirmedContractualWeeklyMinutes: Int? = null
    )

    data class Snapshot(
        val fullCalendarYearPresent: Boolean?,
        val standardCommonLawCaseConfirmed: Boolean?,
        val homogeneousAnnualParametersConfirmed: Boolean?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>,
        /** Valeurs historiques exactes prouvées pour l'année, distinctes des paramètres courants. */
        val confirmedWorkforceBand: EmployerWorkforceContributionsV2.Band? = null,
        val confirmedContractType: ContractTypeV2? = null,
        val confirmedContractualWeeklyMinutes: Int? = null
    )

    fun resolve(records: List<Record>, year: Int): Snapshot {
        val malformed = records.filter {
            it.id.isBlank() ||
                it.source.isBlank() ||
                it.year <= 0 ||
                (it.confirmedContractualWeeklyMinutes != null && it.confirmedContractualWeeklyMinutes <= 0)
        }
        if (malformed.isNotEmpty()) {
            return blocked(
                "RGDU annuelle : un contexte enregistré est incomplet ou incohérent ; calcul automatique bloqué."
            )
        }

        val active = records.filter { it.year == year }
        if (active.isEmpty()) {
            return blocked(
                "RGDU annuelle : contexte factuel à confirmer pour $year (année complète, droit commun et stabilité des paramètres)."
            )
        }
        if (active.size > 1) {
            return blocked(
                "RGDU annuelle : plusieurs contextes factuels existent pour $year ; calcul automatique bloqué."
            )
        }

        val selected = active.single()
        val warnings = buildList {
            if (selected.homogeneousAnnualParametersConfirmed == null) {
                add("RGDU annuelle : stabilité des paramètres à confirmer pour $year.")
            }
            if (selected.homogeneousAnnualParametersConfirmed == true) {
                if (selected.confirmedWorkforceBand == null) {
                    add("RGDU annuelle : tranche d'effectif annuelle exacte à confirmer pour $year.")
                }
                if (selected.confirmedContractType == null) {
                    add("RGDU annuelle : type de contrat annuel exact à confirmer pour $year.")
                }
                if (selected.confirmedContractualWeeklyMinutes == null) {
                    add("RGDU annuelle : durée contractuelle hebdomadaire annuelle exacte à confirmer pour $year.")
                }
            }
        }

        return Snapshot(
            fullCalendarYearPresent = selected.fullCalendarYearPresent,
            standardCommonLawCaseConfirmed = selected.standardCommonLawCaseConfirmed,
            homogeneousAnnualParametersConfirmed = selected.homogeneousAnnualParametersConfirmed,
            source = selected.source,
            reliable = true,
            warnings = warnings.distinct(),
            confirmedWorkforceBand = selected.confirmedWorkforceBand,
            confirmedContractType = selected.confirmedContractType,
            confirmedContractualWeeklyMinutes = selected.confirmedContractualWeeklyMinutes
        )
    }

    private fun blocked(message: String) = Snapshot(
        fullCalendarYearPresent = null,
        standardCommonLawCaseConfirmed = null,
        homogeneousAnnualParametersConfirmed = null,
        source = null,
        reliable = false,
        warnings = listOf(message),
        confirmedWorkforceBand = null,
        confirmedContractType = null,
        confirmedContractualWeeklyMinutes = null
    )
}
