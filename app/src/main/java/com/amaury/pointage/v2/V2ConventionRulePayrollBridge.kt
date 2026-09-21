package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionRulePeriodResolutionV2
import com.amaury.pointage.v2.engine.ConventionRulePeriodResolverV2
import java.time.LocalDate

/**
 * Source conventionnelle datée autoritative pour le calcul Salaire V2 Android.
 *
 * Le bridge ne choisit jamais la version « actuelle » pour un ancien mois. Il expose la couverture
 * exacte de la période depuis l'historique confirmé et propage tout état non fiable ou incomplet.
 * Un IDCC absent est traité en fail-closed par le resolver au lieu de provoquer une exception.
 */
object V2ConventionRulePayrollBridge {
    data class Snapshot(
        val resolution: ConventionRulePeriodResolutionV2,
        val warnings: List<String>
    )

    fun resolve(
        context: Context,
        idcc: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot = resolveStored(
        stored = V2ConventionRuleStore.readConfirmed(context),
        idcc = idcc,
        year = year,
        monthZeroBased = monthZeroBased
    )

    internal fun resolveStored(
        stored: V2ConventionRuleStore.ReadResult,
        idcc: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot {
        require(monthZeroBased in 0..11) { "Mois invalide" }
        val start = LocalDate.of(year, monthZeroBased + 1, 1)
        val end = start.withDayOfMonth(start.lengthOfMonth())
        val resolution = ConventionRulePeriodResolverV2.resolve(
            idcc = idcc,
            periodStartEpochDay = start.toEpochDay(),
            periodEndEpochDay = end.toEpochDay(),
            sourceReliable = stored.reliable,
            snapshots = stored.snapshots
        )
        return Snapshot(
            resolution = resolution,
            warnings = (stored.warnings + resolution.warnings).distinct()
        )
    }
}
