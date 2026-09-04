package com.amaury.pointage.v2.engine

/**
 * Applique uniquement des taux d'heures supplémentaires d'accord d'entreprise déjà
 * vérifiés par la chaîne CompanyAgreementPayrollBridgeV2.
 *
 * Les tranches non couvertes conservent la règle conventionnelle/légale de base.
 * Aucun arbitrage automatique n'est fait en cas de conflit ou de changement en cours de mois.
 */
object CompanyAgreementOvertimeOverlayV2 {
    data class Override(
        val fromMinutes: Int,
        val toMinutes: Int?,
        val multiplier: Double
    )

    data class Result(
        val tiers: List<OvertimeTierV2>,
        val appliedCount: Int,
        val warnings: List<String>
    )

    fun fromSnapshot(
        baseTiers: List<OvertimeTierV2>,
        snapshot: CompanyAgreementPayrollBridgeV2.Snapshot
    ): Result {
        if (snapshot.hasOvertimeConflicts) {
            return Result(
                baseTiers,
                0,
                listOf("Accord d'entreprise : conflit entre règles d'heures supplémentaires, aucune règle n'est appliquée automatiquement.")
            )
        }
        if (snapshot.hasPeriodChanges) {
            return Result(
                baseTiers,
                0,
                listOf("Accord d'entreprise : changement de règle pendant le mois, calcul automatique conservateur maintenu sur le barème de base.")
            )
        }

        val overrides = snapshot.safeOvertimeRules.map { rule ->
            val band = rule.band
            Override(
                // « 36e heure » commence après 35 heures accomplies.
                fromMinutes = (band.fromHourInclusive - 1) * 60,
                toMinutes = band.toHourInclusive?.times(60),
                multiplier = 1.0 + rule.percent / 100.0
            )
        }
        return apply(baseTiers, overrides)
    }

    internal fun apply(baseTiers: List<OvertimeTierV2>, overrides: List<Override>): Result {
        if (overrides.isEmpty()) return Result(baseTiers, 0, emptyList())
        if (overrides.any { it.multiplier < 1.10 || it.fromMinutes < 0 || (it.toMinutes != null && it.toMinutes <= it.fromMinutes) }) {
            return Result(
                baseTiers,
                0,
                listOf("Accord d'entreprise : taux ou tranche d'heures supplémentaires invalide ; aucune règle n'est appliquée automatiquement.")
            )
        }
        if (hasOverlap(overrides)) {
            return Result(
                baseTiers,
                0,
                listOf("Accord d'entreprise : tranches d'heures supplémentaires qui se chevauchent ; aucune règle n'est appliquée automatiquement.")
            )
        }

        var current = baseTiers
        overrides.sortedBy { it.fromMinutes }.forEach { override ->
            current = overlayOne(current, override)
        }
        return Result(
            tiers = mergeAdjacent(current),
            appliedCount = overrides.size,
            warnings = listOf("Accord d'entreprise : ${overrides.size} règle(s) vérifiée(s) d'heures supplémentaires appliquée(s) au calcul.")
        )
    }

    private fun overlayOne(base: List<OvertimeTierV2>, override: Override): List<OvertimeTierV2> {
        val overrideEnd = override.toMinutes ?: Int.MAX_VALUE
        val out = mutableListOf<OvertimeTierV2>()
        base.forEach { tier ->
            val tierEnd = tier.toMinutes ?: Int.MAX_VALUE
            if (tierEnd <= override.fromMinutes || tier.fromMinutes >= overrideEnd) {
                out += tier
            } else {
                if (tier.fromMinutes < override.fromMinutes) {
                    out += OvertimeTierV2(tier.fromMinutes, override.fromMinutes, tier.multiplier)
                }
                if (tierEnd > overrideEnd && override.toMinutes != null) {
                    out += OvertimeTierV2(overrideEnd, tier.toMinutes, tier.multiplier)
                }
            }
        }
        out += OvertimeTierV2(override.fromMinutes, override.toMinutes, override.multiplier)
        return out.sortedBy { it.fromMinutes }
    }

    private fun mergeAdjacent(input: List<OvertimeTierV2>): List<OvertimeTierV2> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedBy { it.fromMinutes }
        val out = mutableListOf<OvertimeTierV2>()
        sorted.forEach { tier ->
            val last = out.lastOrNull()
            if (last != null && last.multiplier == tier.multiplier && last.toMinutes == tier.fromMinutes) {
                out[out.lastIndex] = OvertimeTierV2(last.fromMinutes, tier.toMinutes, last.multiplier)
            } else out += tier
        }
        return out
    }

    private fun hasOverlap(overrides: List<Override>): Boolean {
        val sorted = overrides.sortedBy { it.fromMinutes }
        for (i in 0 until sorted.lastIndex) {
            val end = sorted[i].toMinutes ?: return true
            if (sorted[i + 1].fromMinutes < end) return true
        }
        return false
    }
}
