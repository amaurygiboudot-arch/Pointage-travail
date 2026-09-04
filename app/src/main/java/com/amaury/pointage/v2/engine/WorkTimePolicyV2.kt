package com.amaury.pointage.v2.engine

import java.util.Calendar
import java.util.Locale

/**
 * Politique canonique V2 pour les règles de comptage liées au poste.
 *
 * Elle ne programme aucune pause : elle classe uniquement les horaires réellement
 * enregistrés afin que l'historique, le moteur Temps et la paie appliquent la même règle.
 */
object WorkTimePolicyV2 {
    private const val ENTRY_SLOT_MS = 30L * 60L * 1000L
    private const val ENTRY_GRACE_MS = 10L * 60L * 1000L
    private const val LEGACY_BUG_ENTRY_SLOT_MS = 15L * 60L * 1000L
    private const val LEGACY_BUG_ENTRY_GRACE_MS = 5L * 60L * 1000L
    private const val EXIT_TOLERANCE_MS = 20L * 60L * 1000L

    const val TEAM_PAID_PAUSE_ALLOWANCE_MS = 30L * 60L * 1000L

    enum class ShiftKind { MORNING, DAY, AFTERNOON, NIGHT }

    fun countedEntry(realArrivalMs: Long): Long {
        require(realArrivalMs > 0L) { "realArrivalMs doit être positif" }
        return roundEntry(realArrivalMs, ENTRY_SLOT_MS, ENTRY_GRACE_MS)
    }

    /**
     * Compatibilité ciblée avec la régression V2 15 min / 5 min.
     * Une valeur historique n'est corrigée que si elle correspond exactement à
     * l'ancien mauvais algorithme et diffère de la règle validée 30 min / 10 min.
     */
    fun repairKnownCountedEntry(realArrivalMs: Long?, storedCountedEntryMs: Long?): Long? {
        if (realArrivalMs == null || storedCountedEntryMs == null || realArrivalMs <= 0L || storedCountedEntryMs <= 0L) {
            return storedCountedEntryMs
        }
        val buggy = roundEntry(realArrivalMs, LEGACY_BUG_ENTRY_SLOT_MS, LEGACY_BUG_ENTRY_GRACE_MS)
        val correct = countedEntry(realArrivalMs)
        return if (storedCountedEntryMs == buggy && buggy != correct) correct else storedCountedEntryMs
    }

    fun countedExit(realExitMs: Long, expectedEndMs: Long?): Long {
        require(realExitMs > 0L) { "realExitMs doit être positif" }
        if (expectedEndMs == null || expectedEndMs <= 0L) return realExitMs
        if (realExitMs < expectedEndMs) return realExitMs
        return if (realExitMs <= expectedEndMs + EXIT_TOLERANCE_MS) expectedEndMs else realExitMs
    }

    fun shiftKind(countedEntryMs: Long): ShiftKind {
        val hour = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = countedEntryMs }
            .get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..6 -> ShiftKind.MORNING
            in 7..11 -> ShiftKind.DAY
            in 12..20 -> ShiftKind.AFTERNOON
            else -> ShiftKind.NIGHT
        }
    }

    fun isTeamShift(countedEntryMs: Long): Boolean = shiftKind(countedEntryMs) != ShiftKind.DAY

    fun hasAutomaticMorningBasket(countedEntryMs: Long): Boolean = shiftKind(countedEntryMs) == ShiftKind.MORNING

    private fun roundEntry(realArrivalMs: Long, slotMs: Long, graceMs: Long): Long {
        val remainder = Math.floorMod(realArrivalMs, slotMs)
        val currentSlot = realArrivalMs - remainder
        return if (remainder == 0L || remainder <= graceMs) currentSlot else currentSlot + slotMs
    }
}
