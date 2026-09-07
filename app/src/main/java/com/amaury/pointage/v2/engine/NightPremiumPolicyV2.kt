package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.util.Calendar
import java.util.Locale

/**
 * Règle de majoration de nuit suffisamment structurée pour être calculée :
 * plage horaire officielle + multiplicateur confirmé.
 */
data class NightPremiumRuleV2(
    val startMinute: Int,
    val endMinute: Int,
    val multiplier: Double
) {
    init {
        require(startMinute in 0 until 24 * 60) { "Début de plage nuit invalide" }
        require(endMinute in 0 until 24 * 60) { "Fin de plage nuit invalide" }
        require(startMinute != endMinute) { "Plage nuit vide" }
        require(multiplier >= 1.0 && multiplier.isFinite()) { "Multiplicateur nuit invalide" }
    }

    val percentage: Double get() = (multiplier - 1.0) * 100.0
}

/**
 * Calcule uniquement les minutes PAYÉES qui tombent dans la plage conventionnelle fournie.
 *
 * La plage de poste HoraTrack n'est jamais utilisée comme substitut : l'appelant doit fournir
 * une NightPremiumRuleV2 issue d'une règle officielle structurée.
 */
object NightPremiumPolicyV2 {
    fun paidOverlap(
        session: WorkSessionV2,
        rangeStartMs: Long,
        rangeEndMs: Long,
        rule: NightPremiumRuleV2
    ): Long {
        if (rangeEndMs <= rangeStartMs) return 0L

        var total = 0L
        val day = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = rangeStartMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val last = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = rangeEndMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }

        while (day.timeInMillis <= last.timeInMillis) {
            val start = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, rule.startMinute / 60)
                set(Calendar.MINUTE, rule.startMinute % 60)
            }
            val end = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, rule.endMinute / 60)
                set(Calendar.MINUTE, rule.endMinute % 60)
                if (rule.endMinute <= rule.startMinute) add(Calendar.DAY_OF_YEAR, 1)
            }
            val from = maxOf(rangeStartMs, start.timeInMillis)
            val to = minOf(rangeEndMs, end.timeInMillis)
            if (to > from) total += PaidWorkAllocationV2.paidOverlap(session, from, to)
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }
}
