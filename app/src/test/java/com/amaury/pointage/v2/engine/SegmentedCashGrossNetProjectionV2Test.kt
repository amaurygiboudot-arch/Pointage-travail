package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedCashGrossNetProjectionV2Test {
    @Test
    fun `cash gross fiable atteint le moteur net canonique sans changement de montant`() {
        val company = completeCompany()
        val expected = EmployeeNetProjectionV2.calculate(
            gross = 2_500.0,
            year = 2026,
            company = company,
            complementaryMinutes = 0,
            upstreamGrossReliable = true
        )
        val actual = SegmentedCashGrossNetProjectionV2.project(
            cash = cash(2_500.0),
            year = 2026,
            company = company,
            complementaryMinutes = 0,
            complementaryMinutesReliable = true
        )

        assertTrue(actual.cashGrossReliable)
        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertEquals(expected, actual.projection)
        assertEquals(2_500.0, actual.projection!!.payroll.gross - company.benefitsInKindGross, 0.0001)
    }

    @Test
    fun `minutes complementaires inconnues ne deviennent jamais zero`() {
        val actual = SegmentedCashGrossNetProjectionV2.project(
            cash = cash(2_500.0),
            year = 2026,
            company = completeCompany(),
            complementaryMinutes = null,
            complementaryMinutesReliable = false
        )

        assertFalse(actual.netBeforeIncomeTaxComplete)
        assertNull(actual.projection)
        assertTrue(actual.warnings.contains(SegmentedCashGrossNetProjectionV2.COMPLEMENTARY_WARNING))
    }

    @Test
    fun `cash gross non fiable bloque avant le moteur net`() {
        val actual = SegmentedCashGrossNetProjectionV2.project(
            cash = cash(2_500.0, reliable = false),
            year = 2026,
            company = completeCompany(),
            complementaryMinutes = 0,
            complementaryMinutesReliable = true
        )

        assertFalse(actual.cashGrossReliable)
        assertNull(actual.projection)
    }

    @Test
    fun `donnee sociale manquante conserve le brut mais bloque le net final`() {
        val actual = SegmentedCashGrossNetProjectionV2.project(
            cash = cash(2_500.0),
            year = 2026,
            company = completeCompany().copy(mutualEmployeeAmount = null),
            complementaryMinutes = 0,
            complementaryMinutesReliable = true
        )

        assertTrue(actual.cashGrossReliable)
        assertFalse(actual.netBeforeIncomeTaxComplete)
        assertNull(actual.projection!!.netBeforeIncomeTax)
    }

    private fun cash(
        amount: Double,
        reliable: Boolean = true
    ) = SegmentedCashGrossAssemblyResultV2(
        workedGross = amount,
        additionalCashGross = 0.0,
        cashGross = if (reliable) amount else null,
        reliable = reliable,
        warnings = emptyList()
    )

    private fun completeCompany() = CompanyPayrollOverridesV2.Snapshot(
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
}
