package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionRulePeriodResolutionV2
import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolutionV2
import com.amaury.pointage.v2.model.ContractV2

/** Routing is decided from confirmed timelines before calculation, never from a failed result. */
object V2SalaryCalculationRoute {
    enum class Route { MONTHLY, SEGMENTED, BLOCKED }
    data class Resolution(val route: Route, val contract: ContractV2?, val warnings: List<String>)
    const val UNAVAILABLE_WARNING = "Calcul salaire indisponible : lecture des données impossible."
    const val BLOCKED_WARNING = "Calcul salaire bloqué : contrat ou règles datées à confirmer pour ce mois."

    fun resolve(context: Context, company: SalaryCompanyStore.Company, year: Int, month: Int): Route =
        resolveDetails(context, company, year, month).route

    fun resolveDetails(context: Context, company: SalaryCompanyStore.Company, year: Int, month: Int): Resolution =
        runCatching {
            val contracts = V2EmploymentContractPayrollBridge.resolve(context, company.id, year, month)
            val rules = V2ConventionRulePayrollBridge.resolve(context, company.idcc, year, month)
            describe(
                contracts.resolution, rules.resolution, contracts.warnings + rules.warnings
            )
        }.getOrElse { Resolution(Route.BLOCKED, null, listOf(UNAVAILABLE_WARNING)) }

    internal fun describe(contracts: EmploymentContractPeriodResolutionV2,
                          rules: ConventionRulePeriodResolutionV2,
                          warnings: List<String>): Resolution {
        val route = choose(contracts, rules)
        return Resolution(route, contracts.contract,
            (warnings + if (route == Route.BLOCKED) listOf(BLOCKED_WARNING) else emptyList()).distinct())
    }

    internal fun choose(contracts: EmploymentContractPeriodResolutionV2,
                        rules: ConventionRulePeriodResolutionV2): Route {
        if (!contracts.readyForSegmentedCalculation || !rules.readyForCalculation ||
            contracts.periodStartEpochDay != rules.periodStartEpochDay ||
            contracts.periodEndEpochDay != rules.periodEndEpochDay) return Route.BLOCKED
        return if (contracts.readyForSingleContractCalculation && !rules.requiresMultipleRuleVersions)
            Route.MONTHLY else Route.SEGMENTED
    }
}
