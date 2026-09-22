package com.amaury.pointage

/**
 * Contrat de présentation sûr pour les montants nets de Salaire V2.
 *
 * Cette couche ne recalcule jamais la paie. Elle décide uniquement si les montants déjà produits
 * par [V2SalaryNetBridgeV2] peuvent être affichés comme un net salarié. Un booléen de complétude
 * faux gagne toujours sur les valeurs numériques éventuellement présentes afin d'éviter qu'une UI
 * affiche par erreur un sous-total comme un net final.
 */
object V2SalaryNetPresentationV2 {
    enum class State {
        AVAILABLE,
        INCOMPLETE,
        UNRELIABLE_GROSS
    }

    data class Result(
        val state: State,
        val primaryLabel: String,
        val primaryAmount: Double?,
        val taxableAmount: Double?,
        val incomeTaxAmount: Double?,
        val secondaryLabel: String?,
        val secondaryAmount: Double?,
        val detail: String
    )

    fun from(result: V2SalaryNetBridgeV2.Result): Result {
        if (!result.salary.monthlyGrossReliable || !result.salary.paidTimeReliable) {
            return Result(
                state = State.UNRELIABLE_GROSS,
                primaryLabel = "Net indisponible",
                primaryAmount = null,
                taxableAmount = null,
                incomeTaxAmount = null,
                secondaryLabel = null,
                secondaryAmount = null,
                detail = "Brut à confirmer : aucun net salarié n'est affiché."
            )
        }

        if (!result.netBeforeIncomeTaxComplete || result.netBeforeIncomeTax == null) {
            return Result(
                state = State.INCOMPLETE,
                primaryLabel = "Net incomplet",
                primaryAmount = null,
                taxableAmount = null,
                incomeTaxAmount = null,
                secondaryLabel = null,
                secondaryAmount = null,
                detail = "Cotisations ou paramètres de paie à confirmer : aucun net salarié final n'est affiché."
            )
        }

        val afterTax = result.netAfterIncomeTax
        return Result(
            state = State.AVAILABLE,
            primaryLabel = "Net avant impôt",
            primaryAmount = result.netBeforeIncomeTax,
            taxableAmount = result.netTaxable,
            incomeTaxAmount = result.incomeTax,
            secondaryLabel = afterTax?.let { "Net après impôt" },
            secondaryAmount = afterTax,
            detail = "Montants affichés uniquement à partir des données de paie confirmées."
        )
    }
}
