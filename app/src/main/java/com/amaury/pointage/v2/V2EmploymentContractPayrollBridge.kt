package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolverV2
import java.time.LocalDate

/**
 * Source contractuelle autoritative du calcul de paie Android.
 *
 * Le bridge résout exclusivement l'historique daté confirmé. Il ne consulte jamais les anciennes
 * préférences de « contrat courant » et ne les utilise jamais comme fallback pour un mois.
 */
object V2EmploymentContractPayrollBridge {
    data class Snapshot(
        val resolution: EmploymentContractPeriodResolutionV2,
        val warnings: List<String>
    )

    fun resolve(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot = resolveStored(
        stored = V2EmploymentContractHistoryStore.readConfirmed(context),
        companyId = companyId,
        year = year,
        monthZeroBased = monthZeroBased
    )

    internal fun resolveStored(
        stored: V2EmploymentContractHistoryStore.ReadResult,
        companyId: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot {
        require(monthZeroBased in 0..11) { "Mois invalide" }
        val start = LocalDate.of(year, monthZeroBased + 1, 1)
        val end = start.withDayOfMonth(start.lengthOfMonth())
        val resolution = EmploymentContractPeriodResolverV2.resolve(
            employerId = companyId,
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
