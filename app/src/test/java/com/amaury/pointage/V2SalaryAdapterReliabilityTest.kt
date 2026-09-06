package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SalaryAdapterReliabilityTest {
    @Test
    fun `un taux provisoire rend le brut non fiable`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = true,
                arbitrationRequired = false,
                arbitrationResolved = false
            )
        )
    }

    @Test
    fun `un arbitrage requis mais non resolu rend le brut non fiable`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = true,
                arbitrationResolved = false
            )
        )
    }

    @Test
    fun `un arbitrage resolu autorise le brut fiable si les autres controles passent`() {
        assertTrue(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = true,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = true,
                arbitrationResolved = true
            )
        )
    }

    @Test
    fun `une autre cause de non fiabilite reste bloquante`() {
        assertFalse(
            V2SalaryAdapter.monthlyGrossReliability(
                baseReliable = false,
                provisionalOvertimeRateUsed = false,
                arbitrationRequired = true,
                arbitrationResolved = true
            )
        )
    }
}
