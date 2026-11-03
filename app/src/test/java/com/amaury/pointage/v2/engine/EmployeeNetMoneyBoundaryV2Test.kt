package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses only pre-existing projection APIs so these regressions also run against the HT-017 base. */
class EmployeeNetMoneyBoundaryV2Test {
    private fun company() = CompanyPayrollOverridesV2.Snapshot(
        companyId = "synthetic-money-boundary", idcc = null,
        referenceDate = LocalDate.of(2026, 4, 30), entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 75, contractType = ContractTypeV2.FULL_TIME, contractualWeeklyMinutes = 2100,
        forfaitAnnualDays = null, unpaidAbsenceDays = 0, hasUnpaidAbsence = false,
        mutualEmployeeAmount = 42.0, providentEmployeeAmount = 18.0, transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = 8.0, employeeProvidentNonDeductibleAmount = 4.0,
        incomeTaxRate = 0.032, professionalStatus = "NON_CADRE",
        protectionCategory = PlasturgieProtectionCategoryV2.classify(null, LocalDate.of(2026, 4, 30), null),
        warnings = emptyList(), alsaceMoselleLocalRegime = false, employerProtectionCsgCrdsBaseAmount = 12.0,
        verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride(),
        benefitsInKindGross = 0.0, benefitsInKindReliable = true
    )

    private fun calculate(snapshot: CompanyPayrollOverridesV2.Snapshot = company(), gross: Double = 3000.0) =
        EmployeeNetProjectionV2.calculate(gross, 2026, snapshot, complementaryMinutes = 0)

    private fun assertBlocked(actual: EmployeeNetProjectionV2.Result) {
        assertFalse(actual.netBeforeIncomeTaxComplete)
        assertNull(actual.netBeforeIncomeTax)
        assertNull(actual.netTaxable)
        assertNull(actual.incomeTax)
        assertNull(actual.netAfterIncomeTax)
    }

    @Test
    fun confirmedContributionSubtotalsReconcileWithThePublishedNet() {
        val actual = calculate()
        assertEquals(506.08, actual.payroll.statutory, 1e-9)
        assertEquals(120.30, actual.payroll.complementaryRetirement, 1e-9)
        assertEquals(60.0, actual.payroll.companyEmployeeDeductions, 0.0)
        assertEquals(2313.62, actual.netBeforeIncomeTax!!, 1e-9)
        assertEquals(2411.45, actual.netTaxable!!, 1e-9)
        assertEquals(77.17, actual.incomeTax!!, 1e-9)
        assertEquals(2236.45, actual.netAfterIncomeTax!!, 1e-9)
    }

    @Test
    fun benefitsWithNoCashKeepDeductionsInTheFiscalBaseAndLeaveCollectionUnknown() {
        val actual = calculate(company().copy(benefitsInKindGross = 1000.0), gross = 0.0)
        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertEquals(0.0, actual.netBeforeIncomeTax!!, 0.0)
        assertEquals(771.27, actual.netTaxable!!, 1e-9)
        assertNull(actual.incomeTax)
        assertNull(actual.netAfterIncomeTax)
        assertTrue(actual.warnings.any { it.contains("Solde en espèces négatif") })
        assertTrue(actual.warnings.any { it.contains("traitement employeur") })
    }

    @Test
    fun negativeFiscalBalanceIsNotPublishedAsZero() {
        val actual = calculate(company().copy(mutualEmployeeAmount = 500.0), gross = 100.0)
        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertEquals(0.0, actual.netBeforeIncomeTax!!, 0.0)
        assertNull(actual.netTaxable)
        assertNull(actual.incomeTax)
        assertNull(actual.netAfterIncomeTax)
        assertTrue(actual.warnings.any { it.contains("régularisation non couverte") })
    }

    @Test
    fun transportFiscalTreatmentIsNotInferredFromItsCashDeduction() {
        for ((transport, expectedBefore) in listOf(10.0 to 2303.62, 0.004 to 2313.62)) {
            val actual = calculate(company().copy(transportEmployeeAmount = transport))
            assertTrue(actual.netBeforeIncomeTaxComplete)
            assertEquals(expectedBefore, actual.netBeforeIncomeTax!!, 1e-9)
            assertNull(actual.netTaxable)
            assertNull(actual.incomeTax)
            assertNull(actual.netAfterIncomeTax)
            assertTrue(actual.warnings.any { it.contains("régime fiscal de la retenue transport") })
        }
    }

    @Test
    fun invalidCashGrossAndBenefitsNeverPublishACompleteNet() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, Double.MAX_VALUE)) {
            assertBlocked(calculate(gross = invalid))
            assertBlocked(calculate(company().copy(benefitsInKindGross = invalid)))
        }
        assertBlocked(calculate(company().copy(benefitsInKindGross = 60_000_000_000_000.0), gross = 60_000_000_000_000.0))
    }

    @Test
    fun invalidDirectDeductionsAndProtectionCsgBaseNeverBecomeConfirmedZero() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, Double.MAX_VALUE)) {
            assertBlocked(calculate(company().copy(mutualEmployeeAmount = invalid)))
            assertBlocked(calculate(company().copy(providentEmployeeAmount = invalid)))
            assertBlocked(calculate(company().copy(transportEmployeeAmount = invalid)))
            assertBlocked(calculate(company().copy(employerProtectionCsgCrdsBaseAmount = invalid)))
        }
        assertBlocked(calculate(company().copy(mutualEmployeeAmount = 60_000_000_000_000.0, providentEmployeeAmount = 60_000_000_000_000.0)))
    }

    @Test
    fun invalidFiscalReintegrationsPreserveBeforeTaxButBlockFiscalAmounts() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, Double.MAX_VALUE)) {
            for (snapshot in listOf(company().copy(employerProtectionTaxableAmount = invalid),
                company().copy(employeeProvidentNonDeductibleAmount = invalid))) {
                val actual = calculate(snapshot)
                assertTrue(actual.netBeforeIncomeTaxComplete)
                assertEquals(2313.62, actual.netBeforeIncomeTax!!, 1e-9)
                assertNull(actual.netTaxable)
                assertNull(actual.incomeTax)
                assertNull(actual.netAfterIncomeTax)
            }
        }
        val overflow = calculate(company().copy(employerProtectionTaxableAmount = 60_000_000_000_000.0,
            employeeProvidentNonDeductibleAmount = 60_000_000_000_000.0))
        assertTrue(overflow.netBeforeIncomeTaxComplete)
        assertNull(overflow.netTaxable)
        assertTrue(overflow.warnings.any { it.contains("calcul fiscal non représentable") })
    }

    @Test
    fun invalidRatesDoNotEraseOtherAmountsOrPublishAFictionalPas() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001, 1.001)) {
            val actual = calculate(company().copy(incomeTaxRate = invalid))
            assertTrue(actual.netBeforeIncomeTaxComplete)
            assertEquals(2313.62, actual.netBeforeIncomeTax!!, 1e-9)
            assertEquals(2411.45, actual.netTaxable!!, 1e-9)
            assertNull(actual.incomeTax)
            assertNull(actual.netAfterIncomeTax)
            assertTrue(actual.warnings.any { it.contains("taux personnel") })
        }
        val tinyConfirmed = calculate(company().copy(incomeTaxRate = 1e-300))
        assertEquals(0.0, tinyConfirmed.incomeTax!!, 0.0)
        assertEquals(tinyConfirmed.netBeforeIncomeTax, tinyConfirmed.netAfterIncomeTax)
    }

    @Test
    fun exactHalfCentPasAndEqualCashRemainCollectable() {
        val small = company().copy(mutualEmployeeAmount = 0.0, providentEmployeeAmount = 0.0,
            employerProtectionTaxableAmount = 0.0, employeeProvidentNonDeductibleAmount = 0.0,
            employerProtectionCsgCrdsBaseAmount = 0.0, incomeTaxRate = 0.25)
        val half = calculate(small, gross = 1.0)
        assertEquals(0.82, half.netTaxable!!, 1e-9)
        assertEquals(0.21, half.incomeTax!!, 1e-9)
        assertEquals(0.59, half.netAfterIncomeTax!!, 1e-9)
        val equal = calculate(small.copy(incomeTaxRate = 1.0), gross = 0.01)
        assertEquals(0.01, equal.netBeforeIncomeTax!!, 1e-9)
        assertEquals(0.01, equal.incomeTax!!, 1e-9)
        assertEquals(0.0, equal.netAfterIncomeTax!!, 0.0)
    }

    @Test
    fun wrongReferenceYearDoesNotBorrowTheSupportedTable() {
        assertBlocked(calculate(company().copy(referenceDate = LocalDate.of(2027, 4, 30))))
    }

    @Test
    fun employerContributionsDoNotReduceEmployeeAmounts() {
        val baseline = calculate()
        val actual = calculate(company().copy(atMpEmployerRate = 0.02, employerMobilityRate = 0.03))
        assertEquals(baseline.netBeforeIncomeTax, actual.netBeforeIncomeTax)
        assertEquals(baseline.netTaxable, actual.netTaxable)
        assertEquals(baseline.incomeTax, actual.incomeTax)
        assertEquals(baseline.netAfterIncomeTax, actual.netAfterIncomeTax)
        assertTrue(actual.payroll.knownEmployerContributions > baseline.payroll.knownEmployerContributions)
        assertFalse(actual.payroll.employerCostComplete)
        assertNotNull(actual.netAfterIncomeTax)
    }
}
