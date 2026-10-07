package com.amaury.pointage.v2.engine

import kotlin.math.min

/**
 * Calcul supplétif des heures complémentaires d'un contrat à temps partiel.
 *
 * Le salaire mensualisé couvre la durée contractuelle. Ce moteur calcule uniquement
 * la rémunération à ajouter pour les minutes réellement travaillées au-delà du contrat.
 * À défaut de règle conventionnelle structurée plus précise :
 * - jusqu'à 1/10 de la durée contractuelle : +10 % ;
 * - entre 1/10 et 1/3 : +25 % ;
 * - au-delà du tiers : l'heure reste rémunérée au taux de base dans l'estimation,
 *   mais aucune majoration supplémentaire n'est inventée et un avertissement est émis.
 */
object PartTimeComplementaryHoursV2 {
    /** Explicitly supplied upstream proof; overtime schedules must never be reused here. */
    data class ConfirmedSchedule(
        val sourceId: String,
        val contractualMinutes: Int,
        val effectiveFromEpochDay: Long,
        val effectiveToEpochDay: Long?,
        val tiers: List<OvertimeTierV2>
    ) {
        fun applies(referenceEpochDay: Long?, minutes: Int): Boolean =
            sourceId.isNotBlank() && contractualMinutes == minutes && referenceEpochDay != null &&
                referenceEpochDay >= effectiveFromEpochDay &&
                (effectiveToEpochDay == null || referenceEpochDay <= effectiveToEpochDay)
    }

    data class Tier(
        val label: String,
        val minutes: Int,
        val multiplier: Double
    )

    data class Result(
        val complementaryMinutes: Int,
        val grossToAdd: Double,
        val tiers: List<Tier>,
        val warnings: List<String>,
        val confirmedScheduleUsed: Boolean = false
    )

    fun calculateWeek(
        contractualMinutes: Int,
        paidMinutes: Int,
        grossHourlyRate: Double,
        legalWeeklyMinutes: Int = 35 * 60,
        confirmedSchedule: ConfirmedSchedule? = null,
        referenceEpochDay: Long? = null
    ): Result {
        require(contractualMinutes > 0) { "Durée contractuelle temps partiel invalide" }
        require(paidMinutes >= 0) { "Minutes payées invalides" }
        require(grossHourlyRate > 0.0 && grossHourlyRate.isFinite()) { "Taux horaire brut invalide" }
        require(legalWeeklyMinutes > 0) { "Durée légale hebdomadaire invalide" }

        val paid = paidMinutes
        val extra = (paid - contractualMinutes).coerceAtLeast(0)
        if (extra == 0) return Result(0, 0.0, emptyList(), emptyList())

        if (confirmedSchedule?.applies(referenceEpochDay, contractualMinutes) == true &&
            OvertimeCoverageV2.isFullyCovered(contractualMinutes, paid, confirmedSchedule.tiers)) {
            val tiers = confirmedSchedule.tiers.sortedBy { it.fromMinutes }.mapNotNull { tier ->
                val minutes = (minOf(paid, tier.toMinutes ?: Int.MAX_VALUE) - tier.fromMinutes).coerceAtLeast(0)
                if (minutes == 0) null else Tier("Heures complémentaires — ${confirmedSchedule.sourceId}", minutes, tier.multiplier)
            }
            val gross = tiers.sumOf { it.minutes / 60.0 * grossHourlyRate * it.multiplier }
            require(gross.isFinite()) { "Montant d'heures complémentaires invalide" }
            val warnings = if (paid >= legalWeeklyMinutes) listOf(
                "Temps partiel : durée légale hebdomadaire atteinte ; situation à vérifier malgré le barème confirmé."
            ) else emptyList()
            return Result(extra, gross, tiers, warnings, confirmedScheduleUsed = warnings.isEmpty())
        }

        // Les seuils légaux sont des fractions de la durée prévue au contrat.
        // On travaille en minutes entières et on arrondit le seuil inférieur vers le bas
        // afin de ne jamais sous-rémunérer une minute réellement accomplie.
        val tenthLimit = (contractualMinutes / 10.0).toInt().coerceAtLeast(0)
        val thirdLimit = (contractualMinutes / 3.0).toInt().coerceAtLeast(tenthLimit)

        val firstMinutes = min(extra, tenthLimit)
        val secondMinutes = (min(extra, thirdLimit) - firstMinutes).coerceAtLeast(0)
        val beyondThirdMinutes = (extra - firstMinutes - secondMinutes).coerceAtLeast(0)

        val ratePerMinute = grossHourlyRate / 60.0
        val firstGross = firstMinutes * ratePerMinute * 1.10
        val secondGross = secondMinutes * ratePerMinute * 1.25
        // Au-delà du tiers, on ne supprime jamais le salaire de base correspondant à l'heure
        // réellement travaillée, mais on refuse d'inventer un taux de majoration juridique.
        val beyondGross = beyondThirdMinutes * ratePerMinute

        val tiers = buildList {
            if (firstMinutes > 0) add(Tier("Heures complémentaires +10 %", firstMinutes, 1.10))
            if (secondMinutes > 0) add(Tier("Heures complémentaires +25 %", secondMinutes, 1.25))
            if (beyondThirdMinutes > 0) add(Tier("Heures complémentaires au-delà du tiers — majoration à vérifier", beyondThirdMinutes, 1.0))
        }

        val warnings = buildList {
            if (extra > tenthLimit) {
                add("Temps partiel : le volume d'heures complémentaires dépasse 1/10 de la durée contractuelle ; vérifier qu'un accord autorise une limite supérieure.")
            }
            if (beyondThirdMinutes > 0) {
                add("Temps partiel : dépassement supérieur au tiers de la durée contractuelle. Les heures sont conservées au taux de base dans l'estimation, mais leur majoration et la régularité de la situation doivent être vérifiées.")
            }
            if (paid >= legalWeeklyMinutes) {
                add("Temps partiel : la durée réellement accomplie atteint ou dépasse la durée légale hebdomadaire ; situation à vérifier, aucune requalification n'est inventée par HoraTrack.")
            }
        }

        return Result(
            complementaryMinutes = extra,
            grossToAdd = firstGross + secondGross + beyondGross,
            tiers = tiers,
            warnings = warnings
        )
    }
}
