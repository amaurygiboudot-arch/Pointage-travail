package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyPayrollOverridesV2
import com.amaury.pointage.v2.engine.SegmentedSalaryCanonicalOutputV2
import com.amaury.pointage.v2.engine.SegmentedSalaryCanonicalProductionV2
import java.time.YearMonth
import java.time.ZoneId

data class V2SegmentedSalaryCanonicalBridgeResultV2(
    val output: SegmentedSalaryCanonicalOutputV2?,
    val warnings: List<String>
)

/**
 * Point d'entrée Android entreprise + mois vers la sortie Salaire canonique.
 * Les écrans/exports ne doivent pas reconstruire cette chaîne.
 */
object V2SegmentedSalaryCanonicalBridge {
    const val COMPANY_WARNING =
        "Sortie Salaire segmentée : entreprise ou IDCC absent."
    const val PERIOD_WARNING =
        "Sortie Salaire segmentée : période mensuelle invalide."
    const val TIME_ZONE_WARNING =
        "Sortie Salaire segmentée : fuseau horaire invalide."
    const val TIMELINE_WARNING =
        "Sortie Salaire segmentée : contrat ou règles non couverts sur toute la période."
    fun calculateForCompany(
        context: Context,
        company: SalaryCompanyStore.Company,
        year: Int,
        monthZeroBased: Int,
        timeZoneId: String
    ): V2SegmentedSalaryCanonicalBridgeResultV2 {
        if (company.id.isBlank() || company.idcc.isBlank()) {
            return blocked(COMPANY_WARNING)
        }
        val period = runCatching {
            YearMonth.of(year, monthZeroBased + 1)
        }.getOrNull() ?: return blocked(PERIOD_WARNING)
        if (runCatching { ZoneId.of(timeZoneId) }.isFailure) {
            return blocked(TIME_ZONE_WARNING)
        }

        val contracts = V2EmploymentContractPayrollBridge.resolve(
            context = context,
            companyId = company.id,
            year = year,
            monthZeroBased = monthZeroBased
        )
        val rules = V2ConventionRulePayrollBridge.resolve(
            context = context,
            idcc = company.idcc,
            year = year,
            monthZeroBased = monthZeroBased
        )
        val upstreamWarnings = (contracts.warnings + rules.warnings).distinct()
        if (!contracts.resolution.readyForSegmentedCalculation ||
            !rules.resolution.readyForCalculation
        ) {
            return blocked(upstreamWarnings + TIMELINE_WARNING)
        }

        val workedBridge = V2SegmentedWorkedGrossProductionBridge.calculateDetailedFromStores(
            context = context,
            companyId = company.id,
            companyAddress = company.address,
            year = year,
            monthZeroBased = monthZeroBased,
            timeZoneId = timeZoneId,
            contracts = contracts.resolution,
            rules = rules.resolution
        )
        val worked = workedBridge.worked
            ?: return blocked(upstreamWarnings + workedBridge.warnings)

        val fixed = ConfirmedCashGrossComponentsBridgeV2.load(
            context = context,
            companyId = company.id,
            idcc = company.idcc,
            referenceDate = period.atEndOfMonth(),
            period = period,
            actualMonthlyBaseGross = worked.base.baseGross
        )
        val companyPayroll = CompanyPayrollOverridesV2.load(
            context = context,
            companyId = company.id,
            referenceDate = period.atEndOfMonth()
        )
        return finalize(
            worked = worked,
            fixed = fixed,
            year = year,
            companyPayroll = companyPayroll,
            upstreamWarnings = (
                upstreamWarnings +
                    workedBridge.warnings +
                    fixed.warnings +
                    companyPayroll.warnings
                ).distinct()
        )
    }

    internal fun finalize(
        worked: com.amaury.pointage.v2.engine.SegmentedWorkedGrossProductionResultV2,
        fixed: com.amaury.pointage.v2.engine.ConfirmedCashGrossComponentsV2,
        year: Int,
        companyPayroll: CompanyPayrollOverridesV2.Snapshot,
        upstreamWarnings: List<String> = emptyList()
    ): V2SegmentedSalaryCanonicalBridgeResultV2 {
        val production = SegmentedSalaryCanonicalProductionV2.calculate(
            worked = worked,
            fixed = fixed,
            year = year,
            companyPayroll = companyPayroll
        )
        val output = production.output
        return V2SegmentedSalaryCanonicalBridgeResultV2(
            output = output,
            warnings = (upstreamWarnings + output.warnings).distinct()
        )
    }

    private fun blocked(warning: String) = blocked(listOf(warning))

    private fun blocked(warnings: List<String>) =
        V2SegmentedSalaryCanonicalBridgeResultV2(
            output = null,
            warnings = warnings.distinct()
        )
}
