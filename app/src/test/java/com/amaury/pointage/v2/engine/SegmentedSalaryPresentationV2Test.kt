package com.amaury.pointage.v2.engine

import org.junit.Assert.*
import org.junit.Test

class SegmentedSalaryPresentationV2Test {
    @Test fun unreliableUpstreamExposesNoAmounts() {
        val result = SegmentedSalaryPresentationV2.from(production(false, true, true, false))
        assertEquals(SegmentedSalaryPresentationV2.State.UNRELIABLE, result.state)
        assertNull(result.workedGross); assertNull(result.cashGross); assertNull(result.netBeforeIncomeTax)
        assertNull(result.contributingSessionCount)
    }

    @Test fun reliableGrossWithIncompleteNetKeepsGrossAndHidesNet() {
        val result = SegmentedSalaryPresentationV2.from(production(true, true, true, false))
        assertEquals(SegmentedSalaryPresentationV2.State.GROSS_AVAILABLE_NET_INCOMPLETE, result.state)
        assertEquals(1000.0, result.workedGross!!, 0.0)
        assertEquals(50.0, result.additionalCashGross!!, 0.0)
        assertEquals(1050.0, result.cashGross!!, 0.0)
        assertNull(result.netBeforeIncomeTax); assertNull(result.netTaxable)
        assertEquals(2, result.contributingSessionCount)
        assertEquals(listOf("worked-warning","cash-warning","net-warning"), result.warnings)
    }

    private fun production(workedReliable:Boolean,cashReliable:Boolean,netReliable:Boolean,netComplete:Boolean):SegmentedSalaryProductionResultV2 {
        val worked=SegmentedWorkedGrossProductionResultV2(
            SegmentedPayrollSessionEvidenceResultV2(emptyList(),workedReliable,listOf("worked-warning"),"test-source",if(workedReliable) listOf("s1","s2") else emptyList()),
            SegmentedWorkedVariableGrossSourceResultV2(emptyList(),workedReliable,listOf("worked-warning")),
            SegmentedMonthlyBaseResultV2(emptyList(),if(workedReliable)1000.0 else null,workedReliable,listOf("worked-warning")),
            SegmentedWorkedGrossAssemblyResultV2(if(workedReliable)1000.0 else null,if(workedReliable)0.0 else null,if(workedReliable)1000.0 else null,workedReliable,listOf("worked-warning"))
        )
        val cash=SegmentedCashGrossAssemblyResultV2(if(cashReliable)1000.0 else null,if(cashReliable)50.0 else null,if(cashReliable)1050.0 else null,cashReliable,listOf("cash-warning"))
        val net=SegmentedCashGrossNetProjectionResultV2(cash,null,netReliable,netComplete,listOf("net-warning"))
        return SegmentedSalaryProductionResultV2(worked,cash,net)
    }
}
