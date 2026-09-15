package com.amaury.pointage.v2.engine

/**
 * Vérifie que chaque minute au-delà du seuil hebdomadaire est couverte par un palier explicite.
 *
 * Cette couche ne choisit aucun taux et ne connaît ni métier, ni convention, ni entreprise.
 * Elle distingue :
 * - la cohérence structurelle des paliers (bornes, multiplicateurs, absence de chevauchement) ;
 * - la couverture continue de la tranche réellement travaillée.
 *
 * Un trou peut laisser subsister des tranches connues utilisables pour une estimation partielle.
 * Un chevauchement ou un palier invalide est ambigu : aucun de ces paliers ne doit alors servir
 * au calcul monétaire.
 */
object OvertimeCoverageV2 {
    fun isStructurallyValid(
        regularLimitMinutes: Int,
        tiers: List<OvertimeTierV2>
    ): Boolean {
        require(regularLimitMinutes > 0) { "Seuil hebdomadaire invalide" }
        var previousEnd: Int? = null
        for (tier in tiers.sortedBy { it.fromMinutes }) {
            if (tier.fromMinutes < regularLimitMinutes) return false
            if (!tier.multiplier.isFinite() || tier.multiplier < 1.0) return false
            val rawEnd = tier.toMinutes ?: Int.MAX_VALUE
            if (rawEnd <= tier.fromMinutes) return false
            if (previousEnd != null && tier.fromMinutes < previousEnd) return false
            previousEnd = rawEnd
        }
        return true
    }

    fun calculationSafeTiers(
        regularLimitMinutes: Int,
        tiers: List<OvertimeTierV2>
    ): List<OvertimeTierV2> =
        if (isStructurallyValid(regularLimitMinutes, tiers)) tiers.sortedBy { it.fromMinutes }
        else emptyList()

    fun isFullyCovered(
        regularLimitMinutes: Int,
        paidMinutes: Int,
        tiers: List<OvertimeTierV2>
    ): Boolean {
        require(regularLimitMinutes > 0) { "Seuil hebdomadaire invalide" }
        val paid = paidMinutes.coerceAtLeast(0)
        if (paid <= regularLimitMinutes) return true
        if (!isStructurallyValid(regularLimitMinutes, tiers)) return false

        var cursor = regularLimitMinutes
        for (tier in tiers.sortedBy { it.fromMinutes }) {
            val tierStart = tier.fromMinutes
            val tierEnd = minOf(paid, tier.toMinutes ?: Int.MAX_VALUE)
            if (tierEnd <= regularLimitMinutes) continue
            if (tierStart >= paid) break
            if (tierStart != cursor) return false
            cursor = tierEnd
        }
        return cursor >= paid
    }

    fun areWeeksFullyCovered(
        regularLimitMinutes: Int,
        paidWeeks: List<Int>,
        tiers: List<OvertimeTierV2>
    ): Boolean = paidWeeks.all { isFullyCovered(regularLimitMinutes, it, tiers) }
}
