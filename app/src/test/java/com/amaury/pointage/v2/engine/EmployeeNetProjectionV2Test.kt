package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmployeeNetProjectionV2Test {
    private fun completeSnapshot() = CompanyPayrollOverridesV2.Snapshot(
        companyId = "company",
        idcc = null,
        referenceDate = LocalDate.of(2026, 1, 31),
        entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 72,
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
        protectionCategory = PlasturgieProtectionCategoryV2.classify(
            null,
            LocalDate.of(2026, 1, 31),
            null
        ),
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        employerProtectionCsgCrdsBaseAmount = 0.0,
        verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride(),
        benefitsInKindGross = 0.0,
        benefitsInKindReliable = true
    )

    @Test
    fun completeConfirmedInputsExposeEmployeeNet() {
        val result = EmployeeNetProjectionV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = completeSnapshot()
        )

        assertTrue(result.netBeforeIncomeTaxComplete)
        assertNotNull(result.netBeforeIncomeTax)
        assertNotNull(result.netTaxable)
        assertNotNull(result.incomeTax)
        assertNotNull(result.netAfterIncomeTax)
    }

    @Test
    fun missingEmployeeDeductionNeverBecomesZero() {
        val result = EmployeeNetProjectionV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = completeSnapshot().copy(mutualEmployeeAmount = null)
        )

        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.netBeforeIncomeTax)
        assertNull(result.netTaxable)
        assertNull(result.incomeTax)
        assertNull(result.netAfterIncomeTax)
        assertTrue(result.warnings.any { it.contains("mutuelle salariale", ignoreCase = true) })
    }

    @Test
    fun unknownAlsaceMoselleAffiliationBlocksFinalEmployeeNet() {
        val result = EmployeeNetProjectionV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = completeSnapshot().copy(alsaceMoselleLocalRegime = null)
        )

        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.netBeforeIncomeTax)
        assertTrue(result.warnings.any { it.contains("Alsace-Moselle", ignoreCase = true) })
    }

    @Test
    fun unknownEmployerProtectionCsgBaseBlocksFinalEmployeeNet() {
        val result = EmployeeNetProjectionV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = completeSnapshot().copy(employerProtectionCsgCrdsBaseAmount = null)
        )

        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.netBeforeIncomeTax)
        assertTrue(result.warnings.any { it.contains("CSG/CRDS", ignoreCase = true) })
    }

    @Test
    fun unconfirmedBenefitsInKindBlockFinalEmployeeNet() {
        val result = EmployeeNetProjectionV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = completeSnapshot().copy(benefitsInKindReliable = false)
        )

        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.netBeforeIncomeTax)
        assertTrue(result.warnings.any { it.contains("brut social", ignoreCase = true) })
    }

    @Test
    fun unconfirmedAniCategoryBlocksFinalEmployeeNetWhenConventionControlsAni() {
        val result = EmployeeNetProjectionV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = completeSnapshot().copy(
                verifiedProtectionCategory = ProtectionCategoryV2.Result(
                    aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                    confirmed = false
                )
            )
        )

        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.netBeforeIncomeTax)
        assertTrue(result.warnings.any { it.contains("catégorie ANI", ignoreCase = true) })
    }
}
