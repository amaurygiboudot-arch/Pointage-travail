package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.*
import com.amaury.pointage.v2.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class V2SalaryCalculationRouteTest {
    private val start = LocalDate.of(2026,1,1).toEpochDay()
    private val end = LocalDate.of(2026,1,31).toEpochDay()
    private fun contract(id: String, from: Long, to: Long?, rate: Double) = EmploymentContractSnapshotV2(
        versionId=id, sourceId="confirmed", effectiveFromEpochDay=from, effectiveToEpochDay=to,
        contract=ContractV2(id,"company",ContractTypeV2.FULL_TIME,2100,rate,hireDateEpochDay=null),checkedAtMs=1)
    private fun rule(id: String, from: Long, to: Long?) = ConventionRuleSnapshotV2(
        "0292",id,"confirmed",from,to,PayrollRulesV2(weeklyRegularMinutes=2100),1)
    private fun contracts(values: List<EmploymentContractSnapshotV2>, reliable: Boolean = true) =
        EmploymentContractPeriodResolverV2.resolve("company",start,end,reliable,values)
    private fun rules(values: List<ConventionRuleSnapshotV2>, reliable: Boolean = true) =
        ConventionRulePeriodResolverV2.resolve("0292",start,end,reliable,values)
    private fun singleContract() = contracts(listOf(contract("c1",start,null,14.0)))
    private fun singleRule() = rules(listOf(rule("r1",start,null)))

    @Test fun confirmedSingleMonthKeepsExistingCalculationWithoutNewEvidence() {
        assertEquals(V2SalaryCalculationRoute.Route.MONTHLY,
            V2SalaryCalculationRoute.choose(singleContract(),singleRule()))
    }
    @Test fun contractTransitionNeverFallsBackToMonthly() {
        val split=contracts(listOf(contract("c1",start,start+14,14.0),contract("c2",start+15,null,15.0)))
        assertEquals(V2SalaryCalculationRoute.Route.SEGMENTED,V2SalaryCalculationRoute.choose(split,singleRule()))
    }
    @Test fun ruleTransitionNeverFallsBackToMonthly() {
        val split=rules(listOf(rule("r1",start,start+14),rule("r2",start+15,null)))
        assertEquals(V2SalaryCalculationRoute.Route.SEGMENTED,V2SalaryCalculationRoute.choose(singleContract(),split))
    }
    @Test fun missingUnreliableAndIncompleteTimelinesBlock() {
        for (source in listOf(contracts(emptyList()), singleContract().copy(sourceReliable=false),
                contracts(listOf(contract("gap",start+1,null,14.0))))) {
            assertEquals(V2SalaryCalculationRoute.Route.BLOCKED,V2SalaryCalculationRoute.choose(source,singleRule()))
        }
        for (source in listOf(rules(emptyList()),singleRule().copy(sourceReliable=false),
                singleRule().copy(periodEndEpochDay=end+1))) {
            assertEquals(V2SalaryCalculationRoute.Route.BLOCKED,V2SalaryCalculationRoute.choose(singleContract(),source))
        }
    }
}
