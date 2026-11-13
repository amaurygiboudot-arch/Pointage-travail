package com.amaury.pointage.v2.engine

import org.junit.Assert.*
import org.junit.Test

class CalculatedPayslipDetailsV2Test {
    private val family = EmployerContributionProjectionV2.Family.STATUTORY
    private fun projection(amount: Long = 24000L) = EmployerContributionProjectionV2.assemble(
        EmployerContributionProjectionV2.Context("fixture",2026,4,300000L,true),
        listOf(EmployerContributionProjectionV2.Line(family,EmployerContributionProjectionV2.State.KNOWN,amount,"Synthetic source")),
        EmployerContributionProjectionV2.Reduction(EmployerContributionProjectionV2.State.MISSING))
    private fun payroll() = NetSalaryEngineV2.Result(
        gross=3000.0,socialSecurityCeiling=4000.0,socialSecurityCeilingComplete=true,
        statutory=120.0,complementaryRetirement=0.0,conventionProvidentEmployee=0.0,
        conventionProvidentEmployer=0.0,companyEmployeeDeductions=0.0,employerStatusContributions=0.0,
        employerAtMpContribution=null,netBeforeIncomeTax=2880.0,netTaxable=null,incomeTax=null,
        netAfterIncomeTax=null,complete=true,warnings=emptyList(),grossReliable=true,employeeAmountsReliable=true,
        employerProjection=projection(),contributionDetails=listOf(
            CalculatedContributionLineV2("a","Synthetic A",family,3000.0,.03,90.0,.06,180.0,"Source A"),
            CalculatedContributionLineV2("b","Synthetic B",family,3000.0,.01,30.0,.02,60.0,"Source B")))
    private fun rows(p: NetSalaryEngineV2.Result, company:String="fixture",year:Int=2026,month:Int=4) =
        CalculatedPayslipDetailsV2.contributions(p,true,p.employerProjection,company,year,month)

    @Test fun preservesSourceBaseRatesAndAmountsWithoutRecalculation() {
        val rows=rows(payroll())
        assertEquals(2,rows.size)
        assertEquals(listOf("Synthetic A","3000,00 €","3,0000 %","90,00 €","6,0000 %","180,00 €"),rows[0].cells())
        assertEquals("Source A",rows[0].source)
        val table=SalaryExamplePdfV2.presentationSections(listOf(SalaryExamplePdfV2.PdfSection("Detail",emptyList(),rows))).single()
        assertEquals(rows.map{it.cells()},table.rows)
    }
    @Test fun wrongCompanyMonthOrGrossNeverLeaksDetails() {
        val p=payroll()
        assertTrue(rows(p,company="other").isEmpty())
        assertTrue(rows(p,month=5).isEmpty())
        assertTrue(rows(p,year=2025).isEmpty())
        assertTrue(rows(p.copy(gross=3000.01)).isEmpty())
        assertTrue(CalculatedPayslipDetailsV2.contributions(p,false,p.employerProjection,"fixture",2026,4).isEmpty())
    }
    @Test fun totalsMustReconcileAndUnknownIsNotZero() {
        val p=payroll()
        val wrong=rows(p.copy(statutory=121.0,employerProjection=projection(24001)))
        assertTrue(wrong.all{it.employeeAmount==CalculatedPayslipDetailsV2.UNKNOWN&&it.employerAmount==CalculatedPayslipDetailsV2.UNKNOWN})
        val zero=p.copy(statutory=0.0,employerProjection=projection(0),contributionDetails=p.contributionDetails.map{it.copy(employeeAmount=0.0,employerAmount=0.0)})
        assertTrue(rows(zero).all{it.employeeAmount=="0,00 €"&&it.employerAmount=="0,00 €"})
        assertTrue(rows(p.copy(complete=false)).all{it.employeeAmount==CalculatedPayslipDetailsV2.UNKNOWN})
    }
    @Test fun duplicatesAndInvalidDetailAmountsDoNotBecomeKnownTotals() {
        val p=payroll()
        assertTrue(rows(p.copy(contributionDetails=listOf(p.contributionDetails[0],p.contributionDetails[0]))).isEmpty())
        val invalid=rows(p.copy(contributionDetails=p.contributionDetails.map{it.copy(employeeAmount=Double.NaN,employerAmount=Double.POSITIVE_INFINITY)}))
        assertTrue(invalid.all{it.employeeAmount==CalculatedPayslipDetailsV2.UNKNOWN&&it.employerAmount==CalculatedPayslipDetailsV2.UNKNOWN})
        assertTrue(rows(p.copy(contributionDetails=p.contributionDetails.map{it.copy(source="")})).isEmpty())
    }
    @Test fun settlementDoesNotRelabelAfterTaxAsPaidOrSocialNet() {
        val rows=CalculatedPayslipDetailsV2.settlementLines().toMap()
        assertEquals(4,rows.size)
        assertTrue(rows.values.all{it.startsWith("À confirmer")})
        assertTrue(rows.getValue("Net à payer après frais et retenues").contains("distinct du net estimé après PAS"))
        assertTrue(rows.getValue("Prime / remboursement de transport").contains("distinct des primes brutes"))
    }
    @Test fun fixedComponentDetailMustMatchConfirmedAggregate() {
        val components=listOf(ConfirmedCashGrossComponentV2("seniority-premium",45.0,true,label="Ancienneté"),
            ConfirmedCashGrossComponentV2("company-premium:fixture",80.0,true,label="Prime fixture"))
        val cash=SegmentedCashGrossAssemblyResultV2(2000.0,125.0,2125.0,true,emptyList(),components)
        assertEquals(listOf("Ancienneté" to "45,00 €","Prime fixture" to "80,00 €"),CalculatedPayslipDetailsV2.fixedLines(cash,true))
        assertTrue(CalculatedPayslipDetailsV2.fixedLines(cash.copy(additionalCashGross=126.0),true).all{it.second=="À confirmer"})
        assertTrue(CalculatedPayslipDetailsV2.fixedLines(cash.copy(confirmedComponents=listOf(components[0],components[0])),true).all{it.second=="À confirmer"})
        assertTrue(CalculatedPayslipDetailsV2.fixedLines(cash,false).all{it.second=="À confirmer"})
    }

    @Test fun longDetailedContributionTableSurvivesPdfPagination() {
        val details=(0..64).map { n -> CalculatedPayslipDetailsV2.Row("Line $n|continued $n","Base $n","Rate $n","Employee $n","Employer rate $n","Employer $n","Source $n") }
        val table=SalaryExamplePdfV2.presentationSections(listOf(SalaryExamplePdfV2.PdfSection("Detailed contributions",emptyList(),details))).single()
        val wrapped=SalaryExamplePdfV2.wrapTableRows(table){text,_->text.split('|')}
        val pages=SalaryExamplePdfV2.paginateSections(listOf(SalaryExamplePdfV2.PdfSection(wrapped.name,wrapped.rows.indices.map{it.toString() to ""})),sectionHeaderHeight=42f,rowHeight=14f,sectionTailHeight=14f,keepSectionsTogether=false)
        val indices=pages.flatMap{it.fragments}.flatMap{it.lines}.map{it.first.toInt()}
        assertTrue(pages.size>1)
        assertEquals(130,indices.size)
        assertEquals(wrapped.rows,indices.map{wrapped.rows[it]})
        assertTrue(indices.map{wrapped.rows[it]}.any{it.last()=="Employer 64"})
        assertTrue(pages.drop(1).any{page->page.fragments.any{it.continuation}})
    }

    @Test fun deductionDetailsReconcileAndNeverBecomeTransportReimbursement() {
        val p=payroll().copy(companyEmployeeDeductions=75.0,employeeDeductionDetails=listOf(
            CalculatedEmployeeDeductionV2("mutual","Mutuelle — part salariale",50.0),
            CalculatedEmployeeDeductionV2("provident","Prévoyance — part salariale",20.0),
            CalculatedEmployeeDeductionV2("transport","Transport — retenue salariale",5.0)))
        val rows=CalculatedPayslipDetailsV2.deductions(p,true,"fixture",2026,4).toMap()
        assertEquals("5,00 €",rows["Transport — retenue salariale"])
        assertFalse(rows.containsKey("Prime / remboursement de transport"))
        assertTrue(CalculatedPayslipDetailsV2.deductions(p.copy(companyEmployeeDeductions=76.0),true,"fixture",2026,4).all{it.second=="À confirmer"})
        assertTrue(CalculatedPayslipDetailsV2.deductions(p,true,"other",2026,4).all{it.second=="À confirmer"})
        assertTrue(CalculatedPayslipDetailsV2.deductions(p.copy(employeeDeductionDetails=p.employeeDeductionDetails.map{it.copy(amount=null)}),true,"fixture",2026,4).all{it.second=="À confirmer"})
    }

    @Test fun halfCentComponentMismatchDoesNotChangeNativePayrollOrInventReconciliation() {
        val components=listOf(ConfirmedCashGrossComponentV2("a",10.005,true,label="A"),ConfirmedCashGrossComponentV2("b",1.005,true,label="B"))
        val original=SegmentedCashGrossAssemblyResultV2(100.0,11.01,111.01,true,emptyList(),components)
        val lines=CalculatedPayslipDetailsV2.fixedLines(original,true).toMap()
        assertEquals("10,01 €",lines["A"])
        assertEquals("1,01 €",lines["B"])
        assertEquals("-0,01 € — total du calcul conservé",lines["Écart d’arrondi de présentation des primes"])
        assertEquals(11.01,original.additionalCashGross!!,0.0)
        assertEquals(111.01,original.cashGross!!,0.0)
        assertEquals(10.005,original.confirmedComponents[0].amount,0.0)
    }

}
