package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2

/**
 * Applique uniquement une prestation de prévoyance financée par l'employeur qui
 * chevauche réellement la période de maintien de salaire.
 *
 * Le relais de branche Plasturgie après épuisement du maintien n'entre jamais dans
 * cette déduction : il est décrit séparément par PlasturgieProvidentIncapacityV2.
 */
object SicknessProvidentOffsetV2 {
    data class Result(
        val overlapConfirmed: Boolean,
        val providentNetDeducted: Double?,
        val finalEmployerComplementNet: Double?,
        val warnings: List<String>
    )

    fun apply(
        employerComplementBeforeProvidentNet: Double?,
        treatment: AbsenceProvidentTreatmentV2,
        confirmedOverlapNetAmount: Double?
    ): Result {
        val before = employerComplementBeforeProvidentNet?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return Result(false,null,null,listOf("Prévoyance : complément employeur avant prévoyance indisponible."))

        return when (treatment) {
            AbsenceProvidentTreatmentV2.TO_CONFIRM -> Result(
                false,null,null,
                listOf("Prévoyance employeur pendant le maintien : à confirmer. HoraTrack conserve le complément avant prévoyance sans inventer de montant final.")
            )
            AbsenceProvidentTreatmentV2.NONE_CONFIRMED -> Result(
                true,0.0,before,
                listOf("Aucune prestation de prévoyance financée par l'employeur ne chevauche le maintien pour cet arrêt : aucune déduction supplémentaire.")
            )
            AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED -> {
                val amount = confirmedOverlapNetAmount?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: return Result(false,null,null,listOf("Prévoyance : montant net chevauchant le maintien manquant ou invalide."))
                if (amount > before + 0.01) {
                    return Result(
                        overlapConfirmed = false,
                        providentNetDeducted = null,
                        finalEmployerComplementNet = null,
                        warnings = listOf("Prévoyance déclarée supérieure au complément employeur avant prévoyance : donnée incohérente, complément final laissé à confirmer.")
                    )
                }
                Result(
                    overlapConfirmed = true,
                    providentNetDeducted = amount,
                    finalEmployerComplementNet = (before - amount).coerceAtLeast(0.0),
                    warnings = listOf("Prestation de prévoyance employeur déduite une seule fois du complément de maintien.")
                )
            }
        }
    }
}
