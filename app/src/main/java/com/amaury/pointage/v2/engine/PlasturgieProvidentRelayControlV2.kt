package com.amaury.pointage.v2.engine

/**
 * Contrôle un décompte réel de prévoyance Plasturgie sur une même période/base.
 *
 * HoraTrack ne transforme jamais seul un salaire annuel en prestation journalière.
 * L'utilisateur renseigne le montant correspondant à 60 % du salaire brut de
 * référence POUR LA PÉRIODE CONTRÔLÉE, les prestations SS brutes déduites sur
 * cette même période et la prestation de prévoyance brute réellement observée.
 */
object PlasturgieProvidentRelayControlV2 {
    data class Result(
        val complete: Boolean,
        val expectedMinimumProvidentGross: Double?,
        val observedProvidentGross: Double?,
        val differenceGross: Double?,
        val meetsBranchMinimum: Boolean?,
        val warnings: List<String>
    )

    fun calculate(
        relay: PlasturgieProvidentIncapacityV2.Result,
        grossTargetAtSixtyPercentAmount: Double?,
        socialSecurityGrossAmount: Double?,
        observedProvidentGrossAmount: Double?
    ): Result {
        if (!relay.applicableConvention || !relay.eligibilityConfirmed || !relay.potentiallyCovered) {
            return unavailable("Contrôle prévoyance : garantie de branche non applicable ou éligibilité non confirmée.")
        }
        if (relay.relayReached != true) {
            return unavailable("Contrôle prévoyance : le relais de branche n'est pas encore atteint pour cet arrêt.")
        }
        val target60 = valid(grossTargetAtSixtyPercentAmount)
            ?: return unavailable("Contrôle prévoyance : montant correspondant à 60 % du salaire brut de référence manquant ou invalide.")
        val ssGross = valid(socialSecurityGrossAmount)
            ?: return unavailable("Contrôle prévoyance : prestations de Sécurité sociale brutes manquantes ou invalides.")
        val observed = valid(observedProvidentGrossAmount)
            ?: return unavailable("Contrôle prévoyance : prestation brute réellement versée manquante ou invalide.")

        val expected = (target60 - ssGross).coerceAtLeast(0.0)
        val difference = observed - expected
        val meets = difference >= -0.01
        val warnings = buildList {
            add("Contrôle effectué uniquement sur les montants saisis pour une même période et une même base de décompte ; aucune conversion annuelle/journalière n'est inventée.")
            if (ssGross > target60) {
                add("Prestations SS brutes supérieures à la cible de 60 % : minimum complémentaire ramené à 0 € pour la période contrôlée.")
            }
            if (meets) {
                add("Prestation observée au moins égale au minimum de branche calculable sur les données saisies ; un régime d'entreprise peut prévoir davantage.")
            } else {
                add("Prestation observée inférieure au minimum de branche calculable : écart à vérifier avec le décompte assureur et le régime d'entreprise.")
            }
        }
        return Result(
            complete = true,
            expectedMinimumProvidentGross = expected,
            observedProvidentGross = observed,
            differenceGross = difference,
            meetsBranchMinimum = meets,
            warnings = warnings
        )
    }

    private fun valid(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0.0 }

    private fun unavailable(message: String) = Result(
        complete = false,
        expectedMinimumProvidentGross = null,
        observedProvidentGross = null,
        differenceGross = null,
        meetsBranchMinimum = null,
        warnings = listOf(message)
    )
}
