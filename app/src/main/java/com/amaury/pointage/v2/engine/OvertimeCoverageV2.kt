package com.amaury.pointage.v2.engine

/**
 * Vérifie que chaque minute au-delà du seuil hebdomadaire est couverte par un palier explicite.
 *
 * Cette couche ne choisit aucun taux et ne connaît ni métier, ni convention, ni entreprise.
 * Elle répond uniquement à la question factuelle : les paliers fournis couvrent-ils sans trou
 * et sans chevauchement toute la tranche (seuil régulier, temps payé] ?
 */
object OvertimeCoverageV2 {
    fun isFullyCovered(
        regularLimitMinutes: Int,
        paidMinutes: Int,
        tiers: List<OvertimeTierV2>
    ): Boolean {
        require(regularLimitMinutes > 0) { "Seuil hebdomadaire invalide" }
        val paid = paidMinutes.coerceAtLeast(0)
        if (paid <= regularLimitMinutes) return true

        var cursor = regularLimitMinutes
        for (tier in tiers.sortedBy { it.fromMinutes }) {
            if (tier.fromMinutes < regularLimitMinutes) return false
            val rawEnd = tier.toMinutes ?: Int.MAX_VALUE
            if (rawEnd <= tier.fromMinutes) return false

            val tierStart = tier.fromMinutes
            val tierEnd = minOf(paid, rawEnd)
            if (tierEnd <= regularLimitMinutes) continue
            if (tierStart >= paid) break

            if (tierStart != cursor) return false
            cursor = tierEnd
            if (cursor >= paid) return true
        }
        return false
    }

    fun areWeeksFullyCovered(
        regularLimitMinutes: Int,
        paidWeeks: List<Int>,
        tiers: List<OvertimeTierV2>
    ): Boolean = paidWeeks.all { isFullyCovered(regularLimitMinutes, it, tiers) }
}
