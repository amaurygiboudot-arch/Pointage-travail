package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullTimeStructuralOvertimeV2Test {
    private val legalTiers=listOf(
        OvertimeTierV2(35*60,43*60,1.25),
        OvertimeTierV2(43*60,null,1.50)
    )

    @Test
    fun contract39hIncludesFourStructuralOvertimeHoursInMonthlyBase() {
        val result=FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes=39*60,
            regularWeeklyLimit=35*60,
            paidWeeks=emptyList(),
            grossHourlyRate=10.0,
            overtimeTiers=legalTiers
        )

        assertEquals(1516.6667,result.monthlyRegularMinutes/60.0*10.0,0.01)
        assertEquals(1040.0,result.monthlyStructuralOvertimeMinutes,0.01)
        assertEquals(216.6667,result.structuralOvertimeGross,0.01)
        assertEquals(1733.3334,result.monthlyBaseGross,0.01)
        assertEquals(0.0,result.unresolvedStructuralOvertimeMinutes,0.001)
    }

    @Test
    fun onlyHoursAbove39hAreAddedAgainAsVariableOvertime() {
        val result=FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes=39*60,
            regularWeeklyLimit=35*60,
            paidWeeks=listOf(41*60),
            grossHourlyRate=10.0,
            overtimeTiers=legalTiers
        )

        assertEquals(25.0,result.variableOvertimeGross,0.001)
        assertEquals(0.0,result.unresolvedVariableOvertimeMinutes,0.001)
    }

    @Test
    fun contract35hKeepsAllOvertimeVariable() {
        val result=FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes=35*60,
            regularWeeklyLimit=35*60,
            paidWeeks=listOf(39*60),
            grossHourlyRate=10.0,
            overtimeTiers=legalTiers
        )

        assertEquals(1516.6667,result.monthlyBaseGross,0.01)
        assertEquals(50.0,result.variableOvertimeGross,0.001)
    }

    @Test
    fun missingTiersKeepMinutesButDoNotInventAnyAmount() {
        val result=FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes=39*60,
            regularWeeklyLimit=35*60,
            paidWeeks=emptyList(),
            grossHourlyRate=10.0,
            overtimeTiers=emptyList()
        )

        assertEquals(0.0,result.structuralOvertimeGross,0.001)
        assertEquals(1516.6667,result.monthlyBaseGross,0.01)
        assertEquals(1040.0,result.unresolvedStructuralOvertimeMinutes,0.01)
        assertTrue(result.provisionalRateUsed)
        assertTrue(result.warnings.any { it.contains("aucune valorisation n'est injectée") })
        assertTrue(result.warnings.none { it.contains("+10 %") })
    }

    @Test
    fun invalidMultiplierIsNeutralizedWithoutFallbackAmount() {
        val result=FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes=39*60,
            regularWeeklyLimit=35*60,
            paidWeeks=emptyList(),
            grossHourlyRate=10.0,
            overtimeTiers=listOf(OvertimeTierV2(35*60,null,0.5))
        )

        assertEquals(0.0,result.structuralOvertimeGross,0.001)
        assertEquals(1040.0,result.unresolvedStructuralOvertimeMinutes,0.01)
        assertTrue(result.provisionalRateUsed)
        assertTrue(result.warnings.any { it.contains("ambigus ou invalides") })
    }

    @Test
    fun overlappingTiersAreNeutralizedWithoutFallbackAmount() {
        val result=FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes=39*60,
            regularWeeklyLimit=35*60,
            paidWeeks=emptyList(),
            grossHourlyRate=10.0,
            overtimeTiers=listOf(
                OvertimeTierV2(35*60,43*60,1.25),
                OvertimeTierV2(40*60,null,1.50)
            )
        )

        assertEquals(0.0,result.structuralOvertimeGross,0.001)
        assertEquals(1040.0,result.unresolvedStructuralOvertimeMinutes,0.01)
        assertTrue(result.provisionalRateUsed)
        assertTrue(result.warnings.any { it.contains("ambigus ou invalides") })
    }
}
