package com.amaury.pointage

import com.amaury.pointage.v2.engine.CompanyPayrollOverridesV2
import com.amaury.pointage.v2.engine.NetSalaryEngineV2
import com.amaury.pointage.v2.engine.PlasturgieProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AnnualPdfReportsSalaryV2Test {
    private val month = YearMonth.of(2026, 9)

    private fun payroll(benefitsInKindReliable: Boolean): NetSalaryEngineV2.Result {
        val company = CompanyPayrollOverridesV2.Snapshot(
            companyId = "company-a",
            idcc = null,
            referenceDate = month.atEndOfMonth(),
            entryDate = LocalDate.of(2020, 1, 1),
            seniorityMonths = 80,
            contractType = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 35 * 60,
            forfaitAnnualDays = null,
            unpaidAbsenceDays = 0,
            hasUnpaidAbsence = false,
            mutualEmployeeAmount = 0.0,
            providentEmployeeAmount = 0.0,
            transportEmployeeAmount = 0.0,
            employerProtectionTaxableAmount = 0.0,
            employeeProvidentNonDeductibleAmount = 0.0,
            incomeTaxRate = 0.05,
            professionalStatus = "NON_CADRE",
            protectionCategory = PlasturgieProtectionCategoryV2.classify(null, month.atEndOfMonth(), null),
            warnings = emptyList(),
            alsaceMoselleLocalRegime = false,
            employerProtectionCsgCrdsBaseAmount = 0.0,
            verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride(),
            benefitsInKindReliable = benefitsInKindReliable
        )
        return NetSalaryEngineV2.calculate(2_500.0, 2026, company, complementaryMinutes = 0)
    }

    @Test
    fun `brut social non fiable reste exclu du pdf annuel`() {
        val resolution = resolveAnnualSalaryGrossV2(
            cashGross = 2_500.0,
            cashGrossReliable = true,
            paidTimeReliable = true,
            salaryWarnings = emptyList(),
            payroll = payroll(benefitsInKindReliable = false),
            socialGrossRequired = true,
            upstreamTimeReliable = true
        )

        assertNull(resolution.amount)
        assertEquals("Brut social à confirmer", resolution.state)
    }

    @Test
    fun `brut social fiable peut alimenter le cumul annuel`() {
        val resolution = resolveAnnualSalaryGrossV2(
            cashGross = 2_500.0,
            cashGrossReliable = true,
            paidTimeReliable = true,
            salaryWarnings = emptyList(),
            payroll = payroll(benefitsInKindReliable = true),
            socialGrossRequired = true,
            upstreamTimeReliable = true
        )

        assertEquals(2_500.0, resolution.amount!!, 0.001)
        assertEquals("OK", resolution.state)
    }

    @Test
    fun `echec du moteur net ne retombe jamais sur le brut en especes`() {
        val resolution = resolveAnnualSalaryGrossV2(
            cashGross = 2_500.0,
            cashGrossReliable = true,
            paidTimeReliable = true,
            salaryWarnings = emptyList(),
            payroll = null,
            socialGrossRequired = true,
            upstreamTimeReliable = true
        )

        assertNull(resolution.amount)
        assertEquals("Brut social à confirmer", resolution.state)
    }

    @Test
    fun `parcours sans entreprise conserve le brut mensuel fiable`() {
        val resolution = resolveAnnualSalaryGrossV2(
            cashGross = 2_500.0,
            cashGrossReliable = true,
            paidTimeReliable = true,
            salaryWarnings = emptyList(),
            payroll = null,
            socialGrossRequired = false,
            upstreamTimeReliable = true
        )

        assertEquals(2_500.0, resolution.amount!!, 0.001)
        assertEquals("OK", resolution.state)
    }

    @Test
    fun `temps adapte non fiable masque aussi un brut social calculable`() {
        val resolution = resolveAnnualSalaryGrossV2(
            cashGross = 2_500.0,
            cashGrossReliable = true,
            paidTimeReliable = false,
            salaryWarnings = emptyList(),
            payroll = payroll(benefitsInKindReliable = true),
            socialGrossRequired = true,
            upstreamTimeReliable = true
        )

        assertNull(resolution.amount)
        assertEquals("Temps payé à confirmer", resolution.state)
    }
}
