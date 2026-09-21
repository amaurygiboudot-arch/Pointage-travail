package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.PayrollCalculationTimelineResultV2
import com.amaury.pointage.v2.engine.PayrollCalculationTimelineV2

/**
 * Point d'entrée Android unique pour obtenir la timeline datée utilisable par Salaire V2.
 *
 * Le bridge agrège les deux historiques confirmés mais ne modifie aucune règle : contrat et
 * convention sont résolus séparément, puis intersectés uniquement s'ils couvrent le même mois.
 */
object V2PayrollCalculationTimelineBridge {
    data class Snapshot(
        val contract: V2EmploymentContractPayrollBridge.Snapshot,
        val convention: V2ConventionRulePayrollBridge.Snapshot,
        val timeline: PayrollCalculationTimelineResultV2,
        val warnings: List<String>
    )

    fun resolve(
        context: Context,
        companyId: String,
        idcc: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot = resolveStored(
        contractStored = V2EmploymentContractHistoryStore.readConfirmed(context),
        conventionStored = V2ConventionRuleStore.readConfirmed(context),
        companyId = companyId,
        idcc = idcc,
        year = year,
        monthZeroBased = monthZeroBased
    )

    internal fun resolveStored(
        contractStored: V2EmploymentContractHistoryStore.ReadResult,
        conventionStored: V2ConventionRuleStore.ReadResult,
        companyId: String,
        idcc: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot {
        val contract = V2EmploymentContractPayrollBridge.resolveStored(
            stored = contractStored,
            companyId = companyId,
            year = year,
            monthZeroBased = monthZeroBased
        )
        val convention = V2ConventionRulePayrollBridge.resolveStored(
            stored = conventionStored,
            idcc = idcc,
            year = year,
            monthZeroBased = monthZeroBased
        )
        val timeline = PayrollCalculationTimelineV2.align(
            contracts = contract.resolution,
            rules = convention.resolution
        )
        return Snapshot(
            contract = contract,
            convention = convention,
            timeline = timeline,
            warnings = (contract.warnings + convention.warnings + timeline.warnings).distinct()
        )
    }
}
