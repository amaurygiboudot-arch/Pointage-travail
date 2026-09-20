package com.amaury.pointage.v2

import kotlin.math.roundToInt

/**
 * Frontière numérique commune des saisies Salaire V2.
 *
 * Cette couche n'impose aucun plafond métier arbitraire. Elle garantit seulement qu'une valeur
 * utilisée par les moteurs est représentable, finie et respecte le signe attendu. Une valeur
 * absente ou invalide reste `null` afin que les couches de fiabilité puissent bloquer le calcul
 * plutôt que d'inventer un zéro ou de laisser passer NaN/+∞.
 */
object SalaryNumericInputV2 {
    fun positiveDecimal(raw: String): Double? =
        finiteDecimal(raw)?.takeIf { it > 0.0 }

    fun nonNegativeDecimal(raw: String): Double? =
        finiteDecimal(raw)?.takeIf { it >= 0.0 }

    fun positiveDecimal(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it > 0.0 }

    fun nonNegativeDecimal(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }

    /** Convertit des heures décimales positives vers la minute entière la plus proche. */
    fun positiveMinutesFromHours(raw: String): Int? =
        positiveMinutesFromHours(positiveDecimal(raw))

    fun positiveMinutesFromHours(hours: Double?): Int? {
        val safeHours = positiveDecimal(hours) ?: return null
        val minutes = safeHours * 60.0
        if (!minutes.isFinite() || minutes > Int.MAX_VALUE.toDouble()) return null
        return minutes.roundToInt().takeIf { it > 0 }
    }

    private fun finiteDecimal(raw: String): Double? =
        raw.trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() }
}
