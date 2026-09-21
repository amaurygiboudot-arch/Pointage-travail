package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ContractSegmentPaidWorkAllocatorV2
import com.amaury.pointage.v2.engine.ContractSegmentPaidWorkResultV2
import com.amaury.pointage.v2.engine.ContractSegmentPayrollCompatibilityResultV2
import com.amaury.pointage.v2.engine.ContractSegmentPayrollCompatibilityV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolverV2
import java.time.LocalDate

/**
 * Source contractuelle autoritative du calcul de paie Android.
 *
 * Le bridge résout exclusivement l'historique daté confirmé. Il ne consulte jamais les anciennes
 * préférences de « contrat courant » et ne les utilise jamais comme fallback pour un mois.
 *
 * Depuis le calcul segmenté, il rattache aussi le temps payé aux versions qui couvrent la période.
 * Plusieurs versions ne sont promues vers un calcul mensuel unique que si tous leurs paramètres
 * susceptibles d'influencer la paie sont identiques. Sinon le temps reste disponible par segment,
 * mais le montant mensuel demeure bloqué plutôt que d'inventer une proratisation.
 */
object V2EmploymentContractPayrollBridge {
    data class Snapshot(
        val resolution: EmploymentContractPeriodResolutionV2,
        val warnings: List<String>,
        val segmentedPaidWork: ContractSegmentPaidWorkResultV2? = null,
        val compatibility: ContractSegmentPayrollCompatibilityResultV2? = null
    )

    fun resolve(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot {
        val base = resolveStored(
            stored = V2EmploymentContractHistoryStore.readConfirmed(context),
            companyId = companyId,
            year = year,
            monthZeroBased = monthZeroBased
        )
        val segments = base.resolution.calculationSegments
        if (segments.isEmpty()) return base

        val runtimeSource = V2RuntimeReader.allSessions(context)
        val acceptedIds = SalaryCompanyStore.acceptedEmployerIds(context, companyId)
        val paidWork = ContractSegmentPaidWorkAllocatorV2.allocate(
            sessions = runtimeSource.sessions,
            segments = segments,
            acceptedEmployerIds = acceptedIds,
            sourceReliable = runtimeSource.reliable
        )
        val compatibility = ContractSegmentPayrollCompatibilityV2.resolve(segments)
        val canPromoteEquivalentVersions =
            base.resolution.contract == null &&
                base.resolution.requiresMultipleContractVersions &&
                compatibility.compatibleForSingleMonthlyCalculation &&
                compatibility.contract != null

        val resolution = if (canPromoteEquivalentVersions) {
            base.resolution.copy(
                contract = compatibility.contract,
                warnings = base.resolution.warnings
                    .filterNot { it == EmploymentContractPeriodResolverV2.MULTIPLE_WARNING }
                    .plus(compatibility.warnings)
                    .distinct()
            )
        } else {
            base.resolution
        }
        val baseWarnings = if (canPromoteEquivalentVersions) {
            base.warnings.filterNot { it == EmploymentContractPeriodResolverV2.MULTIPLE_WARNING }
        } else {
            base.warnings
        }

        return Snapshot(
            resolution = resolution,
            warnings = (baseWarnings + compatibility.warnings + paidWork.warnings).distinct(),
            segmentedPaidWork = paidWork,
            compatibility = compatibility
        )
    }

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
