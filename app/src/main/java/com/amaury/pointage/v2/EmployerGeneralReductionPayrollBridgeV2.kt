package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.EmployerGeneralReduction2026V2
import com.amaury.pointage.v2.engine.EmployerReductionResolutionV2
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.YearMonth

/**
 * Passerelle Android entre les faits locaux RGDU, le noyau 2026 et l'arbitre des réductions.
 *
 * Elle ne déduit volontairement aucune donnée depuis le pointage : la rémunération RGDU et les
 * minutes supplémentaires/complémentaires rémunérées doivent être fournies explicitement par
 * l'appelant une fois leur assiette démontrée. Une valeur absente reste inconnue, jamais 0.
 */
object EmployerGeneralReductionPayrollBridgeV2 {
    fun resolve(
        context: Context,
        companyId: String,
        period: YearMonth,
        reductionRemunerationMonthly: Double?,
        workforceBand: EmployerWorkforceContributionsV2.Band?,
        contractType: ContractTypeV2?,
        contractualWeeklyMinutes: Int?,
        additionalPaidMinutes: Int?
    ): EmployerReductionResolutionV2.Result {
        if (companyId.isBlank()) {
            return EmployerReductionResolutionV2.Result(
                totalReductionAmount = null,
                automaticRgduAmount = null,
                mode = EmployerReductionResolutionV2.Mode.BLOCKED,
                source = null,
                reliable = false,
                warnings = listOf("RGDU : entreprise non identifiée ; calcul automatique bloqué.")
            )
        }

        val monthlyContext = CompanyEmployerGeneralReductionContextStoreV2.resolve(
            context = context,
            companyId = companyId,
            month = period
        )
        val automatic = if (reductionRemunerationMonthly == null) {
            EmployerGeneralReduction2026V2.Result(
                amount = null,
                coefficient = null,
                referenceMinimumMonthly = null,
                thresholdMonthly = null,
                reliable = false,
                warnings = listOf("RGDU 2026 : rémunération de référence à confirmer ; calcul automatique bloqué.")
            )
        } else {
            EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                EmployerGeneralReduction2026V2.Input(
                    year = period.year,
                    reductionRemunerationMonthly = reductionRemunerationMonthly,
                    workforceBand = workforceBand,
                    contractType = contractType,
                    contractualWeeklyMinutes = contractualWeeklyMinutes,
                    additionalPaidMinutes = additionalPaidMinutes,
                    fullMonthPresent = monthlyContext.fullMonthPresent,
                    standardCommonLawCaseConfirmed = monthlyContext.standardCommonLawCaseConfirmed
                )
            )
        }

        val manualStore = CompanyEmployerReductionStoreV2.read(context, companyId)
        if (!manualStore.reliable) {
            return EmployerReductionResolutionV2.Result(
                totalReductionAmount = null,
                automaticRgduAmount = automatic.amount?.takeIf { automatic.reliable },
                mode = EmployerReductionResolutionV2.Mode.BLOCKED,
                source = null,
                reliable = false,
                warnings = manualStore.warnings
            )
        }

        return EmployerReductionResolutionV2.resolve(
            month = period,
            manualRecords = manualStore.records,
            automaticRgdu = automatic,
            context = monthlyContext
        )
    }
}
