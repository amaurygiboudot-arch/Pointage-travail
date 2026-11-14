package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConfirmedMonthlyPayrollSettlementV2 as Settlement
import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MonthlyPayrollSettlementProjectionV2Test {
    private val period = "2026-04"
    private val record = Settlement.Record("fixture", period, 31.25, 24.0, 12.5, 3.0, 1600.0, 1717.0, "Synthetic monthly source", 100L, true)
    private fun source(value: Settlement.Record = record) = Settlement.Result(value, true)
    private val basis = MonthlyPayrollSettlementProjectionV2.NetBasis("fixture", period, 170000L)
    private fun project(value: Settlement.Record = record, base: MonthlyPayrollSettlementProjectionV2.NetBasis? = basis) =
        MonthlyPayrollSettlementProjectionV2.project(source(value), "fixture", period, base)

    @Test fun appliesOnlyFourExplicitAdjustmentsAndKeepsObservedValuesIndependent() {
        val result = project()
        assertEquals(171675L, result.estimatedPaidCents)
        assertEquals(25L, result.observedMinusEstimateCents)
        assertEquals(1600.0, result.record!!.netSocialObserved!!, 0.0)
        assertEquals(1717.0, result.record.netPaidObserved!!, 0.0)
        assertEquals(171675L, project(record.copy(netSocialObserved = 1400.0, netPaidObserved = 1700.0)).estimatedPaidCents)
        assertEquals(-1675L, project(record.copy(netPaidObserved = 1700.0)).observedMinusEstimateCents)
    }

    @Test fun absentAndUnconfirmedAdjustmentsNeverBecomeZero() {
        val partial = record.copy(otherAddition = null, adjustmentsComplete = false)
        assertNull(project(partial).estimatedPaidCents)
        assertEquals(1717.0, project(partial).record!!.netPaidObserved!!, 0.0)
        assertFalse(Settlement.valid(partial.copy(adjustmentsComplete = true)))
        assertNull(project(record.copy(adjustmentsComplete = false)).estimatedPaidCents)
        val zeros = record.copy(transportReimbursement=0.0,garnishment=0.0,otherAddition=0.0,otherDeduction=0.0)
        assertEquals(170000L, project(zeros).estimatedPaidCents)
    }

    @Test fun missingPayrollPreservesObservedFiguresAndTheirSourceForBothPresenters() {
        val result = project(base = null)
        assertNull(result.estimatedPaidCents)
        assertEquals(record, result.record)
        val rows = CalculatedPayslipDetailsV2.settlementLines(source(), "fixture", period, null)
        assertTrue(rows.toMap().getValue("Montant net social constaté").startsWith("1600,00 € — constaté"))
        assertTrue(rows.toMap().getValue("Net réellement payé constaté").startsWith("1717,00 € — constaté"))
        assertEquals("Synthetic monthly source", rows.toMap().getValue("Source mensuelle — $period"))
        val table = SalaryExamplePdfV2.presentationSections(listOf(SalaryExamplePdfV2.PdfSection("Settlement", rows))).single()
        assertTrue(table.rows.any { it.any { cell -> cell.contains("1717,00") } })
    }

    @Test fun mismatchedContextAndCorruptSourceNeverExposeObservationsOrProjection() {
        for (bad in listOf(record.copy(companyId="other"),record.copy(period="2026-05"),record.copy(transportReimbursement=Double.NaN))) {
            assertNull(MonthlyPayrollSettlementProjectionV2.project(source(bad),"fixture",period,basis).record)
        }
        assertNull(MonthlyPayrollSettlementProjectionV2.project(Settlement.Result(record,false),"fixture",period,basis).record)
        assertNull(project(base=basis.copy(companyId="other")).estimatedPaidCents)
        assertNull(project(base=basis.copy(period="2026-05")).estimatedPaidCents)
    }

    @Test fun negativePaidBalanceAndArithmeticOverflowStayUnknownInsteadOfClamping() {
        assertNull(project(record.copy(garnishment=2000.0)).estimatedPaidCents)
        assertTrue(project(record.copy(garnishment=2000.0)).warning!!.contains("négatif"))
        val huge = 60_000_000_000_000.0
        assertNull(project(record.copy(transportReimbursement=huge,otherAddition=huge)).estimatedPaidCents)
        assertNull(project(base=basis.copy(netAfterTaxCents=Long.MAX_VALUE)).estimatedPaidCents)
    }

    private fun company() = CompanyPayrollOverridesV2.Snapshot(
        companyId="fixture",idcc=null,referenceDate=LocalDate.of(2026,4,30),entryDate=LocalDate.of(2020,1,1),seniorityMonths=75,
        contractType=ContractTypeV2.FULL_TIME,contractualWeeklyMinutes=2100,forfaitAnnualDays=null,unpaidAbsenceDays=0,hasUnpaidAbsence=false,
        mutualEmployeeAmount=42.0,providentEmployeeAmount=18.0,transportEmployeeAmount=0.0,
        employerProtectionTaxableAmount=8.0,employeeProvidentNonDeductibleAmount=4.0,incomeTaxRate=0.032,professionalStatus="NON_CADRE",
        protectionCategory=PlasturgieProtectionCategoryV2.classify(null,LocalDate.of(2026,4,30),null),warnings=emptyList(),
        alsaceMoselleLocalRegime=false,employerProtectionCsgCrdsBaseAmount=12.0,verifiedProtectionCategory=ProtectionCategoryV2.noConventionOverride(),
        benefitsInKindGross=0.0,benefitsInKindReliable=true)

    @Test fun reliableNetUsesExactExistingCentsAndNeverRedeductsContributionsOrBenefits() {
        val net = EmployeeNetProjectionV2.calculate(3000.0,2026,company().copy(benefitsInKindGross=150.0),0)
        val actual = MonthlyPayrollSettlementProjectionV2.netBasis(net.payroll,net.netAfterIncomeTax,"fixture",period)
        assertNotNull(actual)
        val result=project(base=actual)
        assertEquals(requireNotNull(actual).netAfterTaxCents+1675L,result.estimatedPaidCents)
        assertNull(MonthlyPayrollSettlementProjectionV2.netBasis(net.payroll,null,"fixture",period))
        assertNull(MonthlyPayrollSettlementProjectionV2.netBasis(net.payroll,net.netAfterIncomeTax!!+0.01,"fixture",period))
        assertNull(MonthlyPayrollSettlementProjectionV2.netBasis(net.payroll,net.netAfterIncomeTax,"other",period))
        assertNull(MonthlyPayrollSettlementProjectionV2.netBasis(net.payroll,net.netAfterIncomeTax,"fixture","2026-05"))
    }

    @Test fun zeroPasDoesNotMakeClampedNegativeCashBalanceAReliableSettlementBase() {
        val net = EmployeeNetProjectionV2.calculate(0.0,2026,company().copy(benefitsInKindGross=1000.0,incomeTaxRate=0.0),0)
        assertTrue(net.netBeforeIncomeTaxComplete)
        assertEquals(0.0,net.netAfterIncomeTax!!,0.0)
        assertFalse(net.payroll.settlementBaseReliable)
        assertNull(MonthlyPayrollSettlementProjectionV2.netBasis(net.payroll,net.netAfterIncomeTax,"fixture",period))
        assertNull(project(base=MonthlyPayrollSettlementProjectionV2.netBasis(net.payroll,net.netAfterIncomeTax,"fixture",period)).estimatedPaidCents)
    }
}
