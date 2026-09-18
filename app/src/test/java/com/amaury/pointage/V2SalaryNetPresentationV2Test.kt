package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2SalaryNetPresentationV2Test {
    private fun salary(reliable: Boolean) = V2SalaryAdapter.Result(
        regularMs = 0L,
        overtimeTiers = emptyList(),
        totalWorkedMs = 0L,
        regularGross = 2500.0,
        overtimeGross = 0.0,
        premiumsGross = 0.0,
        monthlyEstimatedGross = 2500.0,
        monthlyGrossReliable = reliable,
        nightMs = 0L,
        saturdayMs = 0L,
        sundayMs = 0L,
        complementaryMinutes = 0,
        completedSessions = 0,
        warnings = emptyList()
    )

    private fun bridge(
        grossReliable: Boolean = true,
        complete: Boolean = true,
        beforeTax: Double? = 2000.0,
        afterTax: Double? = 1900.0
    ) = V2SalaryNetBridgeV2.Result(
        salary = salary(grossReliable),
        netBeforeIncomeTax = beforeTax,
        netTaxable = beforeTax,
        incomeTax = if (afterTax != null && beforeTax != null) beforeTax - afterTax else null,
        netAfterIncomeTax = afterTax,
        netBeforeIncomeTaxComplete = complete,
        warnings = emptyList()
    )

    @Test
    fun completeProjectionPublishesConfirmedNetAmounts() {
        val presentation = V2SalaryNetPresentationV2.from(bridge())

        assertEquals(V2SalaryNetPresentationV2.State.AVAILABLE, presentation.state)
        assertEquals("Net avant impôt", presentation.primaryLabel)
        assertEquals(2000.0, presentation.primaryAmount!!, 0.0)
        assertEquals("Net après impôt", presentation.secondaryLabel)
        assertEquals(1900.0, presentation.secondaryAmount!!, 0.0)
    }

    @Test
    fun incompleteProjectionNeverPublishesAmountsEvenIfNumbersArePresent() {
        val presentation = V2SalaryNetPresentationV2.from(
            bridge(complete = false, beforeTax = 2000.0, afterTax = 1900.0)
        )

        assertEquals(V2SalaryNetPresentationV2.State.INCOMPLETE, presentation.state)
        assertEquals("Net incomplet", presentation.primaryLabel)
        assertNull(presentation.primaryAmount)
        assertNull(presentation.secondaryLabel)
        assertNull(presentation.secondaryAmount)
    }

    @Test
    fun unreliableGrossAlwaysBlocksNetPresentation() {
        val presentation = V2SalaryNetPresentationV2.from(
            bridge(grossReliable = false, complete = true)
        )

        assertEquals(V2SalaryNetPresentationV2.State.UNRELIABLE_GROSS, presentation.state)
        assertEquals("Net indisponible", presentation.primaryLabel)
        assertNull(presentation.primaryAmount)
        assertNull(presentation.secondaryAmount)
    }
}
