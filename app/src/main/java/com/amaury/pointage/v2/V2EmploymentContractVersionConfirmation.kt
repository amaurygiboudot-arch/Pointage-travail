package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.EmploymentContractVersionInputV2
import com.amaury.pointage.v2.engine.EmploymentContractVersionInputValidatorV2

/**
 * Point d'entrée unique pour publier une version contractuelle datée dans l'historique autoritatif.
 *
 * Une entrée invalide ne touche jamais au stockage. La date d'effet doit être explicitement fournie
 * par l'appelant : ce composant ne la déduit ni de la date d'embauche, ni de la date du jour.
 */
object V2EmploymentContractVersionConfirmation {
    data class Result(
        val saved: Boolean,
        val warnings: List<String>
    )

    fun confirm(context: Context, input: EmploymentContractVersionInputV2): Result {
        val validation = EmploymentContractVersionInputValidatorV2.validate(input)
        if (!validation.ready) return Result(false, validation.warnings)

        val saved = V2EmploymentContractHistoryStore.saveEffectiveVersion(
            context = context,
            contract = input.contract,
            effectiveFromEpochDay = input.effectiveFromEpochDay,
            sourceId = input.sourceId.trim(),
            checkedAtMs = input.checkedAtMs,
            note = input.note
        )
        return if (saved) {
            Result(true, emptyList())
        } else {
            Result(
                saved = false,
                warnings = listOf(
                    "Contrat : la version datée n'a pas pu être enregistrée dans l'historique autoritatif."
                )
            )
        }
    }
}
