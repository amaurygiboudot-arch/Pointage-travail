package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import kotlin.math.round

/**
 * Calcul annuel RGDU 2026 limité au cas standard déjà couvert par le noyau mensuel.
 *
 * Cette étape sert à la régularisation de fin d'année prévue à l'article D.241-9 du CSS.
 * Elle ne tente volontairement pas de traiter les années incomplètes, changements de durée
 * contractuelle, changements de tranche d'effectif ou cas spéciaux D.241-10 : ces situations
 * doivent disposer d'un moteur dédié avant tout calcul automatique.
 */
object EmployerGeneralReductionAnnual2026V2 {
    private const val MONTHS_IN_YEAR = 12.0

    data class Input(
        val year: Int,
        /** Rémunération annuelle entrant dans la formule RGDU. */
        val annualReductionRemuneration: Double,
        val workforceBand: EmployerWorkforceContributionsV2.Band?,
        val contractType: ContractTypeV2?,
        val contractualWeeklyMinutes: Int?,
        /** Heures supplémentaires/complémentaires rémunérées sur l'année, sans majoration. */
        val additionalPaidMinutesAnnual: Double?,
        /** true uniquement si le contrat couvre toute l'année civile selon le cas standard pris en charge. */
        val fullCalendarYearPresent: Boolean?,
        /** true uniquement si le cas de droit commun du noyau RGDU standard est confirmé sur toute l'année. */
        val standardCommonLawCaseConfirmed: Boolean?,
        /**
         * true uniquement si le type de contrat, la durée contractuelle et la tranche d'effectif
         * utilisés ici sont confirmés comme stables sur toute la période annuelle.
         */
        val homogeneousAnnualParametersConfirmed: Boolean?
    )

    data class Result(
        val amount: Double?,
        val coefficient: Double?,
        val referenceMinimumAnnual: Double?,
        val thresholdAnnual: Double?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun calculate(input: Input): Result {
        if (input.year != 2026) return blocked("RGDU annuelle : barème non intégré pour ${input.year}.")
        if (!input.annualReductionRemuneration.isFinite() || input.annualReductionRemuneration < 0.0) {
            return blocked("RGDU annuelle 2026 : rémunération annuelle de référence invalide.")
        }
        val additionalMinutes = input.additionalPaidMinutesAnnual
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return blocked("RGDU annuelle 2026 : heures supplémentaires/complémentaires rémunérées à confirmer, même si elles sont nulles.")
        if (input.fullCalendarYearPresent != true) {
            return blocked("RGDU annuelle 2026 : présence sur l'année civile complète non confirmée ; régularisation automatique bloquée.")
        }
        if (input.standardCommonLawCaseConfirmed != true) {
            return blocked("RGDU annuelle 2026 : cas de droit commun non confirmé sur toute l'année.")
        }
        if (input.homogeneousAnnualParametersConfirmed != true) {
            return blocked(
                "RGDU annuelle 2026 : stabilité du contrat, de la durée contractuelle et de la tranche d'effectif à confirmer sur toute l'année."
            )
        }

        // Le noyau mensuel porte déjà les paramètres réglementaires 2026 et leur arrondi du
        // coefficient à 4 décimales. Pour un cas annuel standard homogène, diviser les grandeurs
        // annuelles par 12 conserve exactement le ratio de la formule. Le montant final est ensuite
        // recalculé sur la rémunération annuelle afin d'éviter de sommer douze montants arrondis.
        val monthlyEquivalent = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
            EmployerGeneralReduction2026V2.Input(
                year = input.year,
                reductionRemunerationMonthly = input.annualReductionRemuneration / MONTHS_IN_YEAR,
                workforceBand = input.workforceBand,
                contractType = input.contractType,
                contractualWeeklyMinutes = input.contractualWeeklyMinutes,
                additionalPaidMinutes = additionalMinutes / MONTHS_IN_YEAR,
                fullMonthPresent = true,
                standardCommonLawCaseConfirmed = true
            )
        )
        if (!monthlyEquivalent.reliable || monthlyEquivalent.coefficient == null ||
            monthlyEquivalent.referenceMinimumMonthly == null || monthlyEquivalent.thresholdMonthly == null) {
            return blocked(monthlyEquivalent.warnings.ifEmpty {
                listOf("RGDU annuelle 2026 : paramètres annuels insuffisants pour établir le coefficient.")
            })
        }

        val coefficient = monthlyEquivalent.coefficient
        val annualMinimum = monthlyEquivalent.referenceMinimumMonthly * MONTHS_IN_YEAR
        val annualThreshold = monthlyEquivalent.thresholdMonthly * MONTHS_IN_YEAR
        val amount = roundCents(input.annualReductionRemuneration * coefficient)

        return Result(
            amount = amount,
            coefficient = coefficient,
            referenceMinimumAnnual = annualMinimum,
            thresholdAnnual = annualThreshold,
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun roundCents(value: Double): Double = round(value * 100.0) / 100.0

    private fun blocked(message: String) = blocked(listOf(message))

    private fun blocked(warnings: List<String>) = Result(
        amount = null,
        coefficient = null,
        referenceMinimumAnnual = null,
        thresholdAnnual = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
