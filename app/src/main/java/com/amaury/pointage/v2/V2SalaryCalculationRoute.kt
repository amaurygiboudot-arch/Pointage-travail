package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionRulePeriodResolutionV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2

/** Routing is decided from confirmed timelines before calculation, never from a failed result. */
object V2SalaryCalculationRoute {
    enum class Route { MONTHLY, SEGMENTED, BLOCKED }

    fun resolve(context: Context, company: SalaryCompanyStore.Company, year: Int, month: Int): Route =
        runCatching {
            choose(
                V2EmploymentContractPayrollBridge.resolve(context, company.id, year, month).resolution,
                V2ConventionRulePayrollBridge.resolve(context, company.idcc, year, month).resolution
            )
        }.getOrDefault(Route.BLOCKED)

    internal fun choose(contracts: EmploymentContractPeriodResolutionV2,
                        rules: ConventionRulePeriodResolutionV2): Route {
        if (!contracts.readyForSegmentedCalculation || !rules.readyForCalculation ||
            contracts.periodStartEpochDay != rules.periodStartEpochDay ||
            contracts.periodEndEpochDay != rules.periodEndEpochDay) return Route.BLOCKED
        return if (contracts.readyForSingleContractCalculation && !rules.requiresMultipleRuleVersions)
            Route.MONTHLY else Route.SEGMENTED
    }
}
