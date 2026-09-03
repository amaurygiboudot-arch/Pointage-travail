package com.amaury.pointage

/** Règles de nuit intégrées uniquement lorsqu'elles sont suffisamment connues. */
object ConventionNightRules {
    data class Rule(
        val startMinute: Int,
        val endMinute: Int,
        val premiumMultiplier: Double,
        val note: String
    )

    fun forIdcc(idcc: String?): Rule? = when (idcc?.trim()?.padStart(4, '0')) {
        "0292" -> Rule(
            startMinute = 21 * 60,
            endMinute = 6 * 60,
            premiumMultiplier = 1.12,
            note = "Plasturgie : référence 21h–6h ; l'horaire de nuit pratiqué dans l'entreprise peut s'appliquer."
        )
        else -> null
    }
}
