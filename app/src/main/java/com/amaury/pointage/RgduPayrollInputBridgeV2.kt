package com.amaury.pointage

import com.amaury.pointage.v2.model.ContractTypeV2
import kotlin.math.abs

/**
 * Prépare uniquement les entrées factuelles du noyau RGDU.
 *
 * Cette passerelle ne décide jamais de l'éligibilité juridique RGDU et n'applique aucune réduction.
 * Elle refuse notamment de transformer une liste de paliers vide en « 0 heure supplémentaire » tant
 * que l'exhaustivité des heures rémunérées du mois n'est pas explicitement confirmée.
 */
object RgduPayrollInputBridgeV2 {
    data class Snapshot(
        /** Assiette L.242-1 connue dans HoraTrack + avantages en nature ; PPV incluse si enregistrée dans le brut. */
        val reductionRemunerationMonthly: Double?,
        /** Heures supplémentaires ou complémentaires rémunérées, sans leur majoration, en minutes décimales. */
        val additionalPaidMinutes: Double?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        salary: V2SalaryAdapter.Result,
        contractType: ContractTypeV2?,
        benefitsInKindGross: Double,
        /** true seulement si toutes les heures rémunérées du mois sont couvertes par les faits HoraTrack. */
        paidHoursComplete: Boolean?
    ): Snapshot {
        val warnings = mutableListOf<String>()

        val remuneration = when {
            !salary.monthlyGrossReliable -> {
                warnings += "RGDU : brut mensuel HoraTrack non fiable ; rémunération de référence bloquée."
                null
            }
            !salary.monthlyEstimatedGross.isFinite() || salary.monthlyEstimatedGross < 0.0 -> {
                warnings += "RGDU : brut mensuel invalide."
                null
            }
            !benefitsInKindGross.isFinite() || benefitsInKindGross < 0.0 -> {
                warnings += "RGDU : avantages en nature invalides ou non fiabilisés."
                null
            }
            else -> salary.monthlyEstimatedGross + benefitsInKindGross
        }

        val additionalMinutes = when {
            contractType != ContractTypeV2.FULL_TIME && contractType != ContractTypeV2.PART_TIME -> {
                warnings += "RGDU : heures supplémentaires/complémentaires automatiques disponibles uniquement pour temps plein ou temps partiel."
                null
            }
            paidHoursComplete != true -> {
                warnings += "RGDU : exhaustivité des heures rémunérées du mois à confirmer ; 0 heure supplémentaire n'est jamais déduit d'une liste vide."
                null
            }
            salary.overtimeTiers.any { it.durationMs < 0L } -> {
                warnings += "RGDU : durée négative détectée dans les paliers d'heures supplémentaires/complémentaires."
                null
            }
            else -> {
                val minutes = salary.overtimeTiers.sumOf { it.durationMs.toDouble() / 60_000.0 }
                if (!minutes.isFinite() || minutes < 0.0) {
                    warnings += "RGDU : total d'heures supplémentaires/complémentaires invalide."
                    null
                } else if (
                    contractType == ContractTypeV2.PART_TIME &&
                    abs(minutes - salary.complementaryMinutes.toDouble()) > 0.001
                ) {
                    warnings += "RGDU : incohérence entre paliers d'heures complémentaires et compteur temps partiel ; calcul bloqué."
                    null
                } else {
                    minutes
                }
            }
        }

        return Snapshot(
            reductionRemunerationMonthly = remuneration,
            additionalPaidMinutes = additionalMinutes,
            reliable = remuneration != null && additionalMinutes != null && warnings.isEmpty(),
            warnings = warnings.distinct()
        )
    }
}
