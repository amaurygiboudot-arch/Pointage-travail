package com.amaury.pointage

import com.amaury.pointage.v2.engine.NetSalaryEngineV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2SalaryNetPresentationV2Test {
    private fun salary(
        reliable: Boolean,
        paidTimeReliable: Boolean = reliable
    ) = V2SalaryAdapter.Result(
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
        warnings = emptyList(),
        paidTimeReliable = paidTimeReliable
    )

    private fun payroll(
        beforeTax: Double = 2000.0,
        taxable: Double? = beforeTax,
        incomeTax: Double? = 100.0,
        afterTax: Double? = 1900.0,
        complete: Boolean = true
    ) = NetSalaryEngineV2.Result(
        gross = 2500.0,
        socialSecurityCeiling = 4000.0,
        socialSecurityCeilingComplete = true,
        statutory = 300.0,
        complementaryRetirement = 100.0,
        conventionProvidentEmployee = 0.0,
        conventionProvidentEmployer = 0.0,
        companyEmployeeDeductions = 100.0,
        employerStatusContributions = 0.0,
        employerAtMpContribution = null,
        netBeforeIncomeTax = beforeTax,
        netTaxable = taxable,
        incomeTax = incomeTax,
        netAfterIncomeTax = afterTax,
        complete = complete,
        warnings = emptyList()
    )

    private fun bridge(
        grossReliable: Boolean = true,
        paidTimeReliable: Boolean = grossReliable,
        complete: Boolean = true,
        beforeTax: Double? = 2000.0,
        taxable: Double? = beforeTax,
        incomeTax: Double? = if (beforeTax != null) 100.0 else null,
        afterTax: Double? = 1900.0
    ) = V2SalaryNetBridgeV2.Result(
        salary = salary(grossReliable, paidTimeReliable),
        payroll = payroll(
            beforeTax = beforeTax ?: 0.0,
            taxable = taxable,
            incomeTax = incomeTax,
            afterTax = afterTax,
            complete = complete
        ),
        netBeforeIncomeTax = beforeTax,
        netTaxable = taxable,
        incomeTax = incomeTax,
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
        assertEquals(2000.0, presentation.taxableAmount!!, 0.0)
        assertEquals(100.0, presentation.incomeTaxAmount!!, 0.0)
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
        assertNull(presentation.taxableAmount)
        assertNull(presentation.incomeTaxAmount)
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
        assertNull(presentation.taxableAmount)
        assertNull(presentation.incomeTaxAmount)
        assertNull(presentation.secondaryAmount)
    }

    @Test
    fun missingIncomeTaxKeepsBeforeTaxNetAvailableWithoutInventingTax() {
        val presentation = V2SalaryNetPresentationV2.from(
            bridge(incomeTax = null, afterTax = null)
        )

        assertEquals(V2SalaryNetPresentationV2.State.AVAILABLE, presentation.state)
        assertEquals(2000.0, presentation.primaryAmount!!, 0.0)
        assertNull(presentation.incomeTaxAmount)
        assertNull(presentation.secondaryLabel)
        assertNull(presentation.secondaryAmount)
    }

    @Test
    fun unreliablePaidTimeAlwaysBlocksNetPresentation() {
        val presentation = V2SalaryNetPresentationV2.from(
            bridge(grossReliable = true, paidTimeReliable = false, complete = true)
        )

        assertEquals(V2SalaryNetPresentationV2.State.UNRELIABLE_GROSS, presentation.state)
        assertNull(presentation.primaryAmount)
        assertNull(presentation.taxableAmount)
        assertNull(presentation.incomeTaxAmount)
        assertNull(presentation.secondaryAmount)
    }
}
