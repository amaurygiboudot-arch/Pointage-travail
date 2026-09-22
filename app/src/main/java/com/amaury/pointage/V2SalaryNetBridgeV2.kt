package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.engine.CompanyPayrollOverridesV2
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.engine.EmployeeNetProjectionV2
import com.amaury.pointage.v2.engine.NetSalaryEngineV2
import com.amaury.pointage.v2.engine.PayrollPeriodV2

/**
 * Raccord unique entre le brut final produit par [V2SalaryAdapter] et le net salarié fail-closed.
 *
 * Important : ce bridge ne relit ni ne soustrait directement les retenues. [CompanyPayrollOverridesV2]
 * résout déjà les valeurs datées/sourcées du mois et [EmployeeNetProjectionV2] les consomme une seule
 * fois. Cela évite tout double retrait entre l'adapter brut et le moteur net.
 */
object V2SalaryNetBridgeV2 {
    data class Result(
        val salary: V2SalaryAdapter.Result,
        val payroll: NetSalaryEngineV2.Result,
        val netBeforeIncomeTax: Double?,
        val netTaxable: Double?,
        val incomeTax: Double?,
        val netAfterIncomeTax: Double?,
        val netBeforeIncomeTaxComplete: Boolean,
        val warnings: List<String>,
        /** Retenues réelles confirmées ayant alimenté ce calcul, sans seconde lecture du store. */
        val mutualEmployeeAmount: Double? = null,
        val providentEmployeeAmount: Double? = null
    )

    fun calculateForCompany(
        context: Context,
        company: SalaryCompanyStore.Company,
        year: Int,
        month: Int,
        convention: ConventionCatalog.Convention,
        ruleHistory: ConventionRuleHistoryV2? = null
    ): Result {
        val salary = V2SalaryAdapter.calculateForCompany(
            context = context,
            company = company,
            year = year,
            month = month,
            convention = convention,
            ruleHistory = ruleHistory
        )
        val period = PayrollPeriodV2.month(year, month)
        val companyPayroll = CompanyPayrollOverridesV2.load(
            context = context,
            companyId = company.id,
            referenceDate = period.referenceDate
        )
        return project(
            salary = salary,
            year = year,
            companyPayroll = companyPayroll
        )
    }

    /**
     * Fonction pure testable : le moteur net reçoit toujours le brut FINAL de l'adapter, donc après
     * les ajustements déjà intégrés à `monthlyEstimatedGross` (primes, ancienneté, règles applicables).
     */
    internal fun project(
        salary: V2SalaryAdapter.Result,
        year: Int,
        companyPayroll: CompanyPayrollOverridesV2.Snapshot
    ): Result {
        val net = EmployeeNetProjectionV2.calculate(
            gross = salary.monthlyEstimatedGross,
            year = year,
            company = companyPayroll,
            complementaryMinutes = salary.complementaryMinutes,
            upstreamGrossReliable = salary.monthlyGrossReliable && salary.paidTimeReliable
        )
        return Result(
            salary = salary,
            payroll = net.payroll,
            netBeforeIncomeTax = net.netBeforeIncomeTax,
            netTaxable = net.netTaxable,
            incomeTax = net.incomeTax,
            netAfterIncomeTax = net.netAfterIncomeTax,
            netBeforeIncomeTaxComplete = net.netBeforeIncomeTaxComplete,
            warnings = (salary.warnings + companyPayroll.warnings + net.warnings).distinct(),
            mutualEmployeeAmount = companyPayroll.mutualEmployeeAmount,
            providentEmployeeAmount = companyPayroll.providentEmployeeAmount
        )
    }
}
