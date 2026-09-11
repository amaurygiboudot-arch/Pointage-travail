package com.amaury.pointage

import com.amaury.pointage.v2.V2ConventionRuleStore
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.model.ContractTypeV2

/**
 * Préserve l'état de fiabilité du stockage KALI jusqu'au calcul Salaire V2.
 *
 * Un historique local illisible ne doit jamais être transformé en « aucun historique » :
 * sinon le catalogue statique pourrait redevenir applicable comme si l'absence avait été prouvée.
 */
internal data class SalaryConventionHistoryStateV2(
    val history: ConventionRuleHistoryV2?,
    val reliable: Boolean,
    val warnings: List<String>
) {
    /**
     * Le temps plein dispose déjà d'un mécanisme de valorisation provisoire fail-closed (+10 %)
     * lorsqu'aucun palier fiable n'est disponible. On peut donc y bloquer le catalogue statique.
     *
     * Pour OTHER, le moteur générique n'a pas ce filet : retirer les paliers ferait disparaître du
     * brut les minutes au-delà du seuil. Le catalogue reste alors une estimation, dont la fiabilité
     * est dégradée séparément si des heures supplémentaires sont effectivement valorisées.
     */
    fun conventionForCalculation(
        convention: ConventionCatalog.Convention,
        contractType: ContractTypeV2?
    ): ConventionCatalog.Convention =
        if (reliable || contractType != ContractTypeV2.FULL_TIME) {
            convention
        } else {
            convention.copy(rulesIntegrated = false)
        }
}

internal fun salaryConventionHistoryStateV2(
    provided: ConventionRuleHistoryV2?,
    stored: V2ConventionRuleStore.ReadResult?
): SalaryConventionHistoryStateV2 {
    if (provided != null) {
        return SalaryConventionHistoryStateV2(
            history = provided,
            reliable = true,
            warnings = emptyList()
        )
    }

    val source = stored ?: return SalaryConventionHistoryStateV2(
        history = ConventionRuleHistoryV2.empty(),
        reliable = false,
        warnings = listOf(
            "KALI heures supplémentaires : état de l'historique conventionnel indisponible ; " +
                "aucune règle conventionnelle stockée n'est utilisée automatiquement."
        )
    )

    if (!source.reliable) {
        return SalaryConventionHistoryStateV2(
            history = ConventionRuleHistoryV2.empty(),
            reliable = false,
            warnings = source.warnings.distinct().ifEmpty {
                listOf(
                    "KALI heures supplémentaires : historique local des règles conventionnelles incohérent ; " +
                        "aucune règle conventionnelle stockée n'est utilisée automatiquement."
                )
            }
        )
    }

    val history = runCatching { ConventionRuleHistoryV2(source.snapshots) }.getOrElse {
        return SalaryConventionHistoryStateV2(
            history = ConventionRuleHistoryV2.empty(),
            reliable = false,
            warnings = listOf(
                "KALI heures supplémentaires : historique local des règles conventionnelles incohérent ; " +
                    "aucune règle conventionnelle stockée n'est utilisée automatiquement."
            )
        )
    }

    return SalaryConventionHistoryStateV2(
        history = history,
        reliable = true,
        warnings = emptyList()
    )
}

internal fun salaryConventionHistoryWarningsV2(
    existing: List<String>,
    state: SalaryConventionHistoryStateV2,
    contractType: ContractTypeV2?
): List<String> {
    if (state.reliable) return existing.distinct()
    val kept = if (contractType == ContractTypeV2.FULL_TIME) {
        existing.filterNot {
            it.startsWith("Barème conventionnel d'heures supplémentaires non intégré")
        }
    } else {
        existing
    }
    val estimateWarning = if (contractType == ContractTypeV2.OTHER) {
        "KALI heures supplémentaires : pour ce contrat OTHER, tout barème statique du catalogue éventuellement utilisé pour valoriser des heures supplémentaires reste une estimation ; le montant correspondant est À confirmer tant que l'historique KALI local n'est pas fiable."
    } else {
        null
    }
    return (kept + state.warnings + listOfNotNull(estimateWarning)).distinct()
}

internal fun salaryConventionHistoryGrossReliableV2(
    baseReliable: Boolean,
    state: SalaryConventionHistoryStateV2,
    contractType: ContractTypeV2?,
    overtimeGross: Double
): Boolean = baseReliable && !(
    !state.reliable &&
        contractType == ContractTypeV2.OTHER &&
        overtimeGross > 0.0
    )
