package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedCashGrossComponentsResolverV2Test {
    @Test
    fun reliableApplicableFactsBecomeExhaustiveComponents() {
        val result = ConfirmedCashGrossComponentsResolverV2.resolve(
            facts = listOf(
                ConfirmedCashGrossFixedFactV2("seniority", 50.0, true, true),
                ConfirmedCashGrossFixedFactV2("company-premium", 25.0, true, true)
            ),
            exhaustive = true,
            sourceId = "fixed-stores"
        )
        assertTrue(result.exhaustive)
        assertEquals(listOf("seniority", "company-premium"), result.components.map { it.id })
        assertEquals(75.0, result.components.sumOf { it.amount }, 0.0001)
    }

    @Test
    fun confirmedEmptyFactsCanRepresentZero() {
        val result = ConfirmedCashGrossComponentsResolverV2.resolve(
            facts = emptyList(),
            exhaustive = true,
            sourceId = "fixed-stores-empty"
        )
        assertTrue(result.exhaustive)
        assertTrue(result.components.isEmpty())
    }

    @Test
    fun unknownApplicableFactNeverBecomesZero() {
        val result = ConfirmedCashGrossComponentsResolverV2.resolve(
            facts = listOf(
                ConfirmedCashGrossFixedFactV2("seniority", null, true, false, listOf("à confirmer"))
            ),
            exhaustive = true,
            sourceId = "fixed-stores"
        )
        assertFalse(result.exhaustive)
        assertTrue(result.components.isEmpty())
        assertTrue(result.warnings.contains(ConfirmedCashGrossComponentsResolverV2.FACT_WARNING))
    }

    @Test
    fun duplicateIdsBlockCoverage() {
        val result = ConfirmedCashGrossComponentsResolverV2.resolve(
            facts = listOf(
                ConfirmedCashGrossFixedFactV2("x", 10.0, true, true),
                ConfirmedCashGrossFixedFactV2("x", 20.0, true, true)
            ),
            exhaustive = true,
            sourceId = "fixed-stores"
        )
        assertFalse(result.exhaustive)
    }
}
