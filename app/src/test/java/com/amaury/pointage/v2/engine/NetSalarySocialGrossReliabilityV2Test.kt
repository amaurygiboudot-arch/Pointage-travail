package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CompanyBenefitInKindStoreV2
import com.amaury.pointage.v2.NetSalaryReferencePolicyV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class NetSalarySocialGrossReliabilityV2Test {
    private val month = YearMonth.of(2026, 9)

    private fun company() = CompanyPayrollOverridesV2.Snapshot(
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
        verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride()
    )

    private fun calculate(company: CompanyPayrollOverridesV2.Snapshot) =
        NetSalaryEngineV2.calculate(2500.0, 2026, company, complementaryMinutes = 0)

    @Test
    fun `zero non confirme ne devient pas un brut social fiable`() {
        val input = company()
        val result = calculate(input)

        assertFalse(input.benefitsInKindReliable)
        assertFalse(result.grossReliable)
        assertFalse(result.complete)
        assertNull(NetSalaryReferencePolicyV2.socialGross(result))
        assertNull(NetSalaryReferencePolicyV2.beforeIncomeTax(result))
        assertNull(NetSalaryReferencePolicyV2.taxable(result))
        assertTrue(result.warnings.any { it.startsWith("Brut social :") })
    }

    @Test
    fun `liste positive incomplete reste un sous total`() {
        val result = calculate(company().copy(benefitsInKindGross = 200.0))

        assertEquals(2700.0, result.gross, 0.001)
        assertNull(NetSalaryReferencePolicyV2.socialGross(result))
    }

    @Test
    fun `absence confirmee autorise le brut sans attendre les taux patronaux`() {
        val result = calculate(company().copy(benefitsInKindReliable = true))

        assertTrue(result.grossReliable)
        assertEquals(2500.0, NetSalaryReferencePolicyV2.socialGross(result)!!, 0.001)
        assertFalse(result.employerCostComplete)
        assertTrue(result.employerCostWarnings.isNotEmpty())
    }

    @Test
    fun `avantages confirmes sont inclus une seule fois`() {
        val result = calculate(company().copy(benefitsInKindGross = 200.0, benefitsInKindReliable = true))

        assertEquals(2700.0, NetSalaryReferencePolicyV2.socialGross(result)!!, 0.001)
        assertEquals(200.0, result.benefitsInKindDeduction, 0.001)
        assertEquals(
            2500.0 - result.statutory - result.complementaryRetirement - result.companyEmployeeDeductions,
            result.netBeforeIncomeTax,
            0.001
        )
    }

    @Test
    fun `confirmation dun autre mois ne debloque pas le brut`() {
        val benefits = CompanyBenefitInKindStoreV2.resolve(
            records = CompanyBenefitInKindStoreV2.ReadResult(emptyList(), true, emptyList()),
            confirmations = CompanyBenefitInKindStoreV2.ConfirmationReadResult(
                listOf(CompanyBenefitInKindStoreV2.MonthConfirmation(month.minusMonths(1), "Bulletin précédent")),
                true,
                emptyList()
            ),
            period = month
        )
        val result = calculate(company().copy(
            benefitsInKindGross = benefits.totalGross,
            benefitsInKindReliable = benefits.reliable,
            warnings = benefits.warnings
        ))

        assertFalse(benefits.reliable)
        assertNull(NetSalaryReferencePolicyV2.socialGross(result))
    }

    @Test
    fun `stockage corrompu reste bloque meme avec confirmation du mois`() {
        val benefits = CompanyBenefitInKindStoreV2.resolve(
            records = CompanyBenefitInKindStoreV2.decodeRecords("{corrompu"),
            confirmations = CompanyBenefitInKindStoreV2.ConfirmationReadResult(
                listOf(CompanyBenefitInKindStoreV2.MonthConfirmation(month, "Bulletin du mois")),
                true,
                emptyList()
            ),
            period = month
        )
        val result = calculate(company().copy(
            benefitsInKindGross = benefits.totalGross,
            benefitsInKindReliable = benefits.reliable,
            warnings = benefits.warnings
        ))

        assertNull(NetSalaryReferencePolicyV2.socialGross(result))
        assertNull(NetSalaryReferencePolicyV2.beforeIncomeTax(result))
    }

    @Test
    fun `valeur invalide ne devient pas un brut confirme`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { invalid ->
            val result = calculate(company().copy(benefitsInKindGross = invalid, benefitsInKindReliable = true))
            assertFalse(result.grossReliable)
            assertFalse(result.complete)
            assertNull(NetSalaryReferencePolicyV2.socialGross(result))
        }
    }

    @Test
    fun `net ne peut pas contourner le blocage du brut social`() {
        val result = calculate(company()).copy(complete = true)

        assertNull(NetSalaryReferencePolicyV2.beforeIncomeTax(result))
        assertNull(NetSalaryReferencePolicyV2.taxable(result))
    }

    @Test
    fun `entreprise introuvable ne fournit pas un faux brut social`() {
        val result = calculate(CompanyPayrollOverridesV2.unresolvedCompanySnapshot(
            companyId = "missing-company",
            referenceDate = month.atEndOfMonth(),
            warnings = listOf("Entreprise introuvable")
        ))

        assertFalse(result.grossReliable)
        assertNull(NetSalaryReferencePolicyV2.socialGross(result))
    }
}
