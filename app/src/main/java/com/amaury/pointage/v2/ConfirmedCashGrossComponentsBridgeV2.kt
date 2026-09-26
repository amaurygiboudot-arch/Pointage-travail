package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.CompanyPremiumResolverV2
import com.amaury.pointage.v2.engine.ConfirmedCashGrossComponentV2
import com.amaury.pointage.v2.engine.ConfirmedCashGrossComponentsV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import java.time.LocalDate
import java.time.YearMonth

/**
 * Adapte les propriétaires V2 de composantes fixes vers le contrat cash segmenté.
 * Aucun montant n'est recalculé ici.
 */
object ConfirmedCashGrossComponentsBridgeV2 {
    fun load(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        period: YearMonth,
        actualMonthlyBaseGross: Double?
    ): ConfirmedCashGrossComponentsV2 {
        val minimum = V2ConventionMinimumSalaryBridge.load(
            context = context,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate
        )
        val seniority = V2ConventionSeniorityPremiumBridge.load(
            context = context,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate,
            actualMonthlyBaseGross = actualMonthlyBaseGross,
            conventionalMinimumMonthlyGross = minimum.selectedMonthlyGross
        ).result
        val companyPremiums = CompanyPremiumStoreV2.resolve(
            context = context,
            companyId = companyId,
            period = period
        )
        val coverage = CompanyPremiumStoreV2.monthCoverage(
            context = context,
            companyId = companyId,
            period = period
        )
        return assemble(
            period = period,
            seniority = seniority,
            companyPremiums = companyPremiums,
            coverage = coverage,
            upstreamWarnings = minimum.resolution.warnings
        )
    }

    internal fun assemble(
        period: YearMonth,
        seniority: ConventionSeniorityPremiumV2.Result,
        companyPremiums: CompanyPremiumResolverV2.Snapshot,
        coverage: CompanyPremiumStoreV2.MonthCoverageSnapshot,
        upstreamWarnings: List<String> = emptyList()
    ): ConfirmedCashGrossComponentsV2 {
        val warnings = (
            upstreamWarnings +
                seniority.warnings +
                companyPremiums.warnings +
                coverage.warnings
            ).distinct()

        val seniorityAmount = seniority.monthlyAmount
        val seniorityValid = seniority.reliable &&
            seniorityAmount != null &&
            seniorityAmount.isFinite() &&
            seniorityAmount >= 0.0

        val premiumsValid = companyPremiums.reliable &&
            coverage.storageReliable &&
            coverage.confirmed &&
            !coverage.source.isNullOrBlank()

        val components = buildList {
            if (seniorityValid) {
                add(
                    ConfirmedCashGrossComponentV2(
                        id = "seniority-premium",
                        amount = seniorityAmount!!,
                        reliable = true,
                        warnings = seniority.warnings
                    )
                )
            }
            if (premiumsValid) {
                companyPremiums.applied.forEach { premium ->
                    add(
                        ConfirmedCashGrossComponentV2(
                            id = "company-premium:${premium.id}",
                            amount = premium.grossAmount,
                            reliable = true
                        )
                    )
                }
            }
        }

        val exhaustive = seniorityValid && premiumsValid
        val senioritySource = seniority.selectedRule?.ruleId
            ?.takeIf { it.isNotBlank() }
            ?: if (seniorityValid) "confirmed-none" else "unconfirmed"
        val premiumSource = coverage.source?.trim().orEmpty()
        val sourceId = if (exhaustive) {
            "fixed-cash-v1|period=$period|seniority=$senioritySource|premiums=$premiumSource"
        } else ""

        return ConfirmedCashGrossComponentsV2(
            components = components,
            exhaustive = exhaustive,
            sourceId = sourceId,
            warnings = warnings
        )
    }
}
