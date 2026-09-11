package com.amaury.pointage.v2.engine

/**
 * Valorisation des heures supplémentaires pour un contrat OTHER payé à l'heure.
 *
 * Contrairement au temps plein mensualisé, le moteur générique ne dispose pas d'un filet pour les
 * minutes situées hors des paliers fournis. Cette couche garantit donc qu'aucune minute au-delà du
 * seuil hebdomadaire ne disparaît du brut : les paliers connus sont appliqués et chaque trou reste
 * valorisé provisoirement au plancher de +10 %, avec fiabilité dégradée.
 */
object OtherContractOvertimeV2 {
    data class TierAmount(
        val multiplier: Double,
        val minutes: Int,
        val gross: Double,
        val provisional: Boolean
    )

    data class Result(
        val overtimeMinutes: Int,
        val overtimeGross: Double,
        val tiers: List<TierAmount>,
        val provisionalRateUsed: Boolean,
        val warnings: List<String>
    )

    private data class Piece(
        val multiplier: Double,
        val minutes: Int,
        val gross: Double,
        val provisional: Boolean
    )

    fun calculate(
        regularWeeklyLimit: Int,
        paidWeeks: List<Int>,
        grossHourlyRate: Double,
        overtimeTiers: List<OvertimeTierV2>,
        minimumFallbackMultiplier: Double = 1.10
    ): Result {
        require(regularWeeklyLimit > 0) { "Seuil hebdomadaire invalide" }
        require(grossHourlyRate > 0.0 && grossHourlyRate.isFinite()) { "Taux horaire brut invalide" }
        require(minimumFallbackMultiplier >= 1.10 && minimumFallbackMultiplier.isFinite()) {
            "Plancher provisoire invalide"
        }
        overtimeTiers.forEach { tier ->
            require(tier.fromMinutes >= regularWeeklyLimit) { "Palier d'heures supplémentaires incohérent" }
            require(tier.toMinutes == null || tier.toMinutes > tier.fromMinutes) {
                "Fin de palier d'heures supplémentaires incohérente"
            }
            require(tier.multiplier >= 1.0 && tier.multiplier.isFinite()) {
                "Multiplicateur d'heures supplémentaires invalide"
            }
        }

        val sorted = overtimeTiers.sortedBy { it.fromMinutes }
        val pieces = mutableListOf<Piece>()

        fun add(from: Int, to: Int, multiplier: Double, provisional: Boolean) {
            if (to <= from) return
            val minutes = to - from
            pieces += Piece(
                multiplier = multiplier,
                minutes = minutes,
                gross = minutes / 60.0 * grossHourlyRate * multiplier,
                provisional = provisional
            )
        }

        paidWeeks.forEach { rawPaid ->
            val paid = rawPaid.coerceAtLeast(0)
            if (paid <= regularWeeklyLimit) return@forEach

            var cursor = regularWeeklyLimit
            sorted.forEach { tier ->
                if (cursor >= paid) return@forEach
                val tierStart = maxOf(regularWeeklyLimit, tier.fromMinutes)
                val tierEnd = minOf(paid, tier.toMinutes ?: Int.MAX_VALUE)
                if (tierEnd <= cursor || tierEnd <= tierStart) return@forEach

                if (tierStart > cursor) {
                    add(cursor, minOf(tierStart, paid), minimumFallbackMultiplier, provisional = true)
                    cursor = minOf(tierStart, paid)
                }
                if (cursor < paid && tierEnd > cursor) {
                    add(cursor, tierEnd, tier.multiplier, provisional = false)
                    cursor = tierEnd
                }
            }
            if (cursor < paid) {
                add(cursor, paid, minimumFallbackMultiplier, provisional = true)
            }
        }

        val aggregated = pieces
            .groupBy { it.multiplier to it.provisional }
            .map { (key, items) ->
                TierAmount(
                    multiplier = key.first,
                    minutes = items.sumOf { it.minutes },
                    gross = items.sumOf { it.gross },
                    provisional = key.second
                )
            }
            .sortedWith(compareBy<TierAmount> { it.provisional }.thenBy { it.multiplier })

        val provisional = pieces.any { it.provisional }
        val warnings = if (provisional) {
            listOf(
                "Palier d'heures supplémentaires incomplet : les minutes non couvertes restent valorisées provisoirement au plancher de +10 % autorisé pour un accord collectif. Ce plancher n'est pas le barème supplétif de +25 % puis +50 % ; le taux exact reste à vérifier."
            )
        } else {
            emptyList()
        }

        return Result(
            overtimeMinutes = pieces.sumOf { it.minutes },
            overtimeGross = pieces.sumOf { it.gross },
            tiers = aggregated,
            provisionalRateUsed = provisional,
            warnings = warnings
        )
    }
}
