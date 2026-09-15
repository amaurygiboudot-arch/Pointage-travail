package com.amaury.pointage.v2.engine

/**
 * Politique canonique V2 pour les règles de comptage horaire encore validées.
 *
 * Cette couche ne connaît ni métier, ni entreprise, ni nom de poste, ni équipe. Les pauses
 * payées/non payées sont résolues séparément à partir de faits explicites. Les anciennes
 * classifications Matin/Journée/Après-midi/Nuit ne font plus partie du moteur canonique.
 */
object WorkTimePolicyV2 {
    private const val ENTRY_SLOT_MS = 30L * 60L * 1000L
    private const val ENTRY_GRACE_MS = 10L * 60L * 1000L
    private const val LEGACY_BUG_ENTRY_SLOT_MS = 15L * 60L * 1000L
    private const val LEGACY_BUG_ENTRY_GRACE_MS = 5L * 60L * 1000L
    private const val EXIT_TOLERANCE_MS = 20L * 60L * 1000L

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

    private fun roundEntry(realArrivalMs: Long, slotMs: Long, graceMs: Long): Long {
        val remainder = Math.floorMod(realArrivalMs, slotMs)
        val currentSlot = realArrivalMs - remainder
        return if (remainder == 0L || remainder <= graceMs) currentSlot else currentSlot + slotMs
    }
}
