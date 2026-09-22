package com.amaury.pointage.v2.engine

import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.V2SalaryNetBridgeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SalaryExamplePdfNetBridgeV2Test {
    private fun salary(
        grossReliable: Boolean = true,
        paidTimeReliable: Boolean = true
    ) = V2SalaryAdapter.Result(
        regularMs = 0L,
        overtimeTiers = emptyList(),
        totalWorkedMs = 0L,
        regularGross = 2_400.0,
        overtimeGross = 100.0,
        premiumsGross = 0.0,
        monthlyEstimatedGross = 2_500.0,
        monthlyGrossReliable = grossReliable,
        nightMs = 0L,
        saturdayMs = 0L,
        sundayMs = 0L,
        complementaryMinutes = 0,
        completedSessions = 0,
        warnings = emptyList(),
        paidTimeReliable = paidTimeReliable
    )

    private fun payroll(
        complete: Boolean = true,
        grossReliable: Boolean = true,
        benefitsInKindDeduction: Double = 0.0
    ) = NetSalaryEngineV2.Result(
        gross = 2_500.0,
        socialSecurityCeiling = 4_000.0,
        socialSecurityCeilingComplete = true,
        statutory = 300.0,
        complementaryRetirement = 100.0,
        conventionProvidentEmployee = 0.0,
        conventionProvidentEmployer = 0.0,
        companyEmployeeDeductions = 100.0,
        employerStatusContributions = 0.0,
        employerAtMpContribution = null,
        netBeforeIncomeTax = 2_000.0,
        netTaxable = 2_050.0,
        incomeTax = 100.0,
        netAfterIncomeTax = 1_900.0,
        complete = complete,
        warnings = emptyList(),
        benefitsInKindDeduction = benefitsInKindDeduction,
        confirmedEmployerReductions = 50.0,
        knownEmployerContributionsAfterReductions = 700.0,
        grossReliable = grossReliable
    )

    private fun bridge(
        grossReliable: Boolean = true,
        paidTimeReliable: Boolean = true,
        netComplete: Boolean = true,
        payrollComplete: Boolean = true,
        payrollGrossReliable: Boolean = true,
        benefitsInKindDeduction: Double = 0.0,
        taxable: Double? = 2_050.0,
        incomeTax: Double? = 100.0,
        afterTax: Double? = 1_900.0
    ): V2SalaryNetBridgeV2.Result {
        val salary = salary(grossReliable, paidTimeReliable)
        return V2SalaryNetBridgeV2.Result(
            salary = salary,
            payroll = payroll(
                complete = payrollComplete,
                grossReliable = payrollGrossReliable,
                benefitsInKindDeduction = benefitsInKindDeduction
            ).copy(
                netTaxable = taxable,
                incomeTax = incomeTax,
                netAfterIncomeTax = afterTax
            ),
            netBeforeIncomeTax = 2_000.0.takeIf { netComplete },
            netTaxable = taxable.takeIf { netComplete },
            incomeTax = incomeTax.takeIf { netComplete },
            netAfterIncomeTax = afterTax.takeIf { netComplete },
            netBeforeIncomeTaxComplete = netComplete,
            warnings = emptyList()
        )
    }

    private fun values(result: V2SalaryNetBridgeV2.Result): Map<String, String> =
        SalaryExamplePdfV2.estimatedGrossLines(result.salary, result).toMap()

    @Test
    fun completeBridgePublishesTheCanonicalEmployeeAmounts() {
        val result = bridge()
        val lines = SalaryExamplePdfV2.estimatedGrossLines(result.salary, result)
        val values = lines.toMap()

        assertEquals("2500,00 €", values["Brut social estimé HoraTrack hors paniers"])
        assertEquals("2000,00 €", values["Net estimé avant impôt"])
        assertEquals("2050,00 €", values["Net imposable estimé"])
        assertEquals("-100,00 €", values["Prélèvement à la source"])
        assertEquals("1900,00 €", values["Net estimé après PAS"])
        assertFalse(lines.any { it.first.contains("Sous-total net") })
    }

    @Test
    fun unreliableGrossHidesEveryEmployeeAndEmployerAmount() {
        val values = values(bridge(grossReliable = false))

        assertEquals("À confirmer", values["Brut social estimé HoraTrack hors paniers"])
        assertEquals("À confirmer", values["Majoration heures supplémentaires"])
        assertEquals("À confirmer", values["Net estimé avant impôt"])
        assertEquals("À confirmer", values["Net imposable estimé"])
        assertEquals("À confirmer", values["Prélèvement à la source"])
        assertEquals("À confirmer", values["Net estimé après PAS"])
        assertEquals("À confirmer", values["Réductions / exonérations patronales"])
        assertEquals("À confirmer", values["Sous-total patronal connu après réductions"])
    }

    @Test
    fun unreliablePaidTimeAlsoBlocksAllDerivedAmounts() {
        val values = values(bridge(paidTimeReliable = false))

        assertEquals("À confirmer", values["Brut social estimé HoraTrack hors paniers"])
        assertEquals("À confirmer", values["Majoration heures supplémentaires"])
        assertEquals("À confirmer", values["Net estimé avant impôt"])
        assertEquals("À confirmer", values["Réductions / exonérations patronales"])
    }

    @Test
    fun unreliablePayrollGrossNeverLeaksBenefitOrEmployerTechnicalAmounts() {
        val result = bridge(
            netComplete = false,
            payrollGrossReliable = false,
            benefitsInKindDeduction = 200.0
        )
        val lines = SalaryExamplePdfV2.estimatedGrossLines(result.salary, result)
        val values = lines.toMap()

        assertEquals("À confirmer", values["Brut social estimé HoraTrack hors paniers"])
        assertFalse(lines.any { it.first == "Dont avantages en nature" })
        assertFalse(lines.any { it.first == "Avantages en nature non versés en espèces" })
        assertEquals("À confirmer", values["Réductions / exonérations patronales"])
        assertEquals("À confirmer", values["Sous-total patronal connu après réductions"])
    }

    @Test
    fun incompleteProjectionNeverLeaksTechnicalNetNumbers() {
        val result = bridge(netComplete = false)
        val lines = SalaryExamplePdfV2.estimatedGrossLines(
            result.salary,
            result
        )
        val values = lines.toMap()

        assertEquals("À confirmer", values["Net estimé avant impôt"])
        assertEquals("À confirmer", values["Net imposable estimé"])
        assertEquals("À confirmer", values["Prélèvement à la source"])
        assertEquals("À confirmer", values["Net estimé après PAS"])
        assertFalse(lines.any { it.second == "2000,00 €" })
    }

    @Test
    fun missingTaxKeepsReliableBeforeTaxAmountsWithoutInventingPas() {
        val values = values(bridge(incomeTax = null, afterTax = null))

        assertEquals("2000,00 €", values["Net estimé avant impôt"])
        assertEquals("2050,00 €", values["Net imposable estimé"])
        assertEquals("À confirmer", values["Prélèvement à la source"])
        assertEquals("À confirmer", values["Net estimé après PAS"])
    }

    @Test
    fun employeeNetCompletenessDoesNotDependOnEmployerOnlyCompleteness() {
        val values = values(bridge(payrollComplete = false, netComplete = true))

        assertEquals("2000,00 €", values["Net estimé avant impôt"])
        assertEquals("1900,00 €", values["Net estimé après PAS"])
    }
}
