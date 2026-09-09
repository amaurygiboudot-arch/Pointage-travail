package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import kotlin.math.pow
import kotlin.math.round

/**
 * Noyau de calcul de la réduction générale dégressive unique (RGDU) 2026.
 *
 * Ce composant ne s'applique qu'au cas mensuel standard explicitement confirmé.
 * Les cas particuliers (Mayotte, heures d'équivalence, caisses de congés,
 * intérim avec correctif, DFS, autre exonération/taux spécifique, salaire minimum
 * professionnel dérogatoire, mois incomplet, etc.) restent volontairement bloqués.
 *
 * Sources : CSS L.241-13, D.241-7 et D.241-8 ; décret n° 2026-509 ;
 * SMIC au 1er janvier 2026 : 12,02 €/h (décret n° 2025-1228).
 */
object EmployerGeneralReduction2026V2 {
    private const val SMIC_HOURLY_2026 = 12.02
    private const val LEGAL_WEEKLY_MINUTES = 35 * 60
    private const val T_MIN = 0.0200
    private const val T_DELTA_UNDER_50 = 0.3781
    private const val T_DELTA_AT_LEAST_50 = 0.3821
    private const val EXPONENT = 1.75

    data class Input(
        val year: Int,
        /** Rémunération mensuelle entrant dans la formule RGDU (assiette L.242-1 + PPV le cas échéant). */
        val reductionRemunerationMonthly: Double,
        val workforceBand: EmployerWorkforceContributionsV2.Band?,
        val contractType: ContractTypeV2?,
        val contractualWeeklyMinutes: Int?,
        /** Heures supplémentaires ou complémentaires rémunérées, sans leur majoration. */
        val additionalPaidMinutes: Double?,
        /** true uniquement si la présence du salarié couvre le mois selon les règles RGDU. */
        val fullMonthPresent: Boolean?,
        /**
         * Confirmation que le cas relève du droit commun utilisé ici : métropole/DROM hors Mayotte,
         * pas de correctif D.241-10, pas de DFS, pas d'autre exonération/taux spécifique incompatible,
         * pas de salaire minimum professionnel dérogatoire L.241-13 III bis.
         */
        val standardCommonLawCaseConfirmed: Boolean?
    )

    data class Result(
        val amount: Double?,
        val coefficient: Double?,
        val referenceMinimumMonthly: Double?,
        val thresholdMonthly: Double?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun calculateMonthlyAdvance(input: Input): Result {
        if (input.year != 2026) {
            return blocked("RGDU : barème non intégré pour ${input.year}.")
        }
        if (!input.reductionRemunerationMonthly.isFinite() || input.reductionRemunerationMonthly < 0.0) {
            return blocked("RGDU 2026 : rémunération de référence invalide.")
        }
        val band = input.workforceBand
            ?: return blocked("RGDU 2026 : effectif <50 / ≥50 à confirmer.")
        val type = input.contractType
            ?: return blocked("RGDU 2026 : type de contrat à confirmer.")
        if (type != ContractTypeV2.FULL_TIME && type != ContractTypeV2.PART_TIME) {
            return blocked("RGDU 2026 : ce type de contrat nécessite une règle dédiée avant calcul automatique.")
        }
        val weekly = input.contractualWeeklyMinutes
            ?.takeIf { it > 0 }
            ?: return blocked("RGDU 2026 : durée contractuelle hebdomadaire à confirmer.")
        val additionalMinutes = input.additionalPaidMinutes
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return blocked("RGDU 2026 : heures supplémentaires/complémentaires rémunérées à confirmer, même si elles sont nulles.")
        if (input.fullMonthPresent != true) {
            return blocked("RGDU 2026 : mois incomplet ou présence non confirmée ; proratisation légale à établir.")
        }
        if (input.standardCommonLawCaseConfirmed != true) {
            return blocked("RGDU 2026 : cas de droit commun non confirmé ; aucun coefficient standard n'est supposé.")
        }

        val baseRatio = (weekly.toDouble() / LEGAL_WEEKLY_MINUTES.toDouble()).coerceAtMost(1.0)
        val contractualMinimum = SMIC_HOURLY_2026 * 35.0 * 52.0 / 12.0 * baseRatio
        val additionalMinimum = SMIC_HOURLY_2026 * (additionalMinutes / 60.0)
        val referenceMinimum = contractualMinimum + additionalMinimum
        val threshold = 3.0 * referenceMinimum
        val remuneration = input.reductionRemunerationMonthly

        if (remuneration <= 0.0) {
            return Result(
                amount = 0.0,
                coefficient = maximumCoefficient(band),
                referenceMinimumMonthly = referenceMinimum,
                thresholdMonthly = threshold,
                reliable = true,
                warnings = emptyList()
            )
        }

        // L.241-13 : le coefficient devient nul lorsque la rémunération atteint le seuil réglementaire.
        if (threshold <= 0.0 || remuneration >= threshold) {
            return Result(
                amount = 0.0,
                coefficient = 0.0,
                referenceMinimumMonthly = referenceMinimum,
                thresholdMonthly = threshold,
                reliable = true,
                warnings = emptyList()
            )
        }

        val tDelta = when (band) {
            EmployerWorkforceContributionsV2.Band.UNDER_11,
            EmployerWorkforceContributionsV2.Band.FROM_11_TO_49 -> T_DELTA_UNDER_50
            EmployerWorkforceContributionsV2.Band.AT_LEAST_50 -> T_DELTA_AT_LEAST_50
        }
        val formulaBase = 0.5 * (3.0 * referenceMinimum / remuneration - 1.0)
        val rawCoefficient = T_MIN + tDelta * formulaBase.coerceAtLeast(0.0).pow(EXPONENT)
        val coefficient = round4(rawCoefficient).coerceIn(0.0, maximumCoefficient(band))
        val amount = roundCents(remuneration * coefficient)

        return Result(
            amount = amount,
            coefficient = coefficient,
            referenceMinimumMonthly = referenceMinimum,
            thresholdMonthly = threshold,
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun maximumCoefficient(band: EmployerWorkforceContributionsV2.Band): Double = when (band) {
        EmployerWorkforceContributionsV2.Band.UNDER_11,
        EmployerWorkforceContributionsV2.Band.FROM_11_TO_49 -> T_MIN + T_DELTA_UNDER_50
        EmployerWorkforceContributionsV2.Band.AT_LEAST_50 -> T_MIN + T_DELTA_AT_LEAST_50
    }

    private fun round4(value: Double): Double = round(value * 10_000.0) / 10_000.0
    private fun roundCents(value: Double): Double = round(value * 100.0) / 100.0

    private fun blocked(message: String) = Result(
        amount = null,
        coefficient = null,
        referenceMinimumMonthly = null,
        thresholdMonthly = null,
        reliable = false,
        warnings = listOf(message)
    )
}
