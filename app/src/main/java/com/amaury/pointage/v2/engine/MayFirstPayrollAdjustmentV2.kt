package com.amaury.pointage.v2.engine

/**
 * Valorisation prudente de l'indemnité légale du 1er mai travaillé.
 *
 * La règle LEGI vérifiée apporte uniquement le multiplicateur additionnel.
 * Dès qu'une autre majoration peut entrer dans la base de l'indemnité, HoraTrack
 * refuse de reconstruire cette base sans règle de cumul suffisamment structurée.
 */
object MayFirstPayrollAdjustmentV2 {
    data class Input(
        val workedMinutes: Int,
        val grossHourlyRate: Double?,
        val verifiedExtraMultiplier: Double?,
        val overtimeOrComplementaryOverlap: Boolean = false,
        val nightPremiumOverlap: Boolean = false,
        val weekendPremiumOverlap: Boolean = false
    )

    data class Result(
        val extraGross: Double,
        val reliable: Boolean,
        val warning: String? = null
    )

    fun calculate(input: Input): Result {
        val minutes = input.workedMinutes.coerceAtLeast(0)
        if (minutes == 0) return Result(extraGross = 0.0, reliable = true)

        val rate = input.grossHourlyRate?.takeIf { it.isFinite() && it > 0.0 }
        if (input.verifiedExtraMultiplier != 1.0 || rate == null) {
            return Result(
                extraGross = 0.0,
                reliable = false,
                warning = "1er mai travaillé : règle LEGI L3133-6 ou base horaire non vérifiée ; aucune indemnité n'est inventée."
            )
        }

        if (input.overtimeOrComplementaryOverlap || input.nightPremiumOverlap || input.weekendPremiumOverlap) {
            return Result(
                extraGross = 0.0,
                reliable = false,
                warning = "1er mai travaillé : une autre majoration chevauche la période ; la base de l'indemnité L3133-6 doit être contrôlée avant valorisation."
            )
        }

        return Result(
            extraGross = minutes / 60.0 * rate * input.verifiedExtraMultiplier,
            reliable = true
        )
    }
}
