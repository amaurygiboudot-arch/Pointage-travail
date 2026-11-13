package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class EmployerKnownCostV2Test {
    private fun company() = CompanyPayrollOverridesV2.Snapshot(
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
            incomeTaxRate = 0.0,
            professionalStatus = "NON_CADRE",
            protectionCategory = PlasturgieProtectionCategoryV2.classify(null, LocalDate.of(2026, 1, 31), null),
            warnings = emptyList(),
            alsaceMoselleLocalRegime = false,
            atMpEmployerRate = 0.02, atMpEmployerSource = "Fixture AT/MP",
            benefitsInKindReliable = true, benefitsInKindGross = 0.0,
            employerMobilityRate = 0.01, employerMobilitySource = "Fixture mobilité"
        )

    @Test
    fun `known employer subtotal never masquerades as complete employer cost`() {
        val company = company()

        val result = NetSalaryEngineV2.calculate(2500.0, 2026, company)
        val expected = result.statutoryEmployerContributions +
            result.complementaryRetirementEmployer +
            result.conventionProvidentEmployer +
            result.employerStatusContributions +
            (result.employerAtMpContribution ?: 0.0) +
            (result.employerMobilityContribution ?: 0.0)

        assertEquals(expected, result.knownEmployerContributions!!, 0.001)
        // ANI is still TO_CONFIRM in this fixture: only the APEC applicability is unknown.
        // The proven Agirc T1 (118.00) and CEG (32.25) must not disappear from a partial subtotal.
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, company.verifiedProtectionCategory.aniCategory)
        val retirement = result.employerProjection!!.lines.single {
            it.family == EmployerContributionProjectionV2.Family.COMPLEMENTARY_RETIREMENT
        }
        assertEquals(EmployerContributionProjectionV2.State.KNOWN, retirement.state)
        assertEquals(15025L, retirement.amountCents)
        assertTrue(result.employerProjection!!.warnings.any {
            it.contains("couverture partielle") && (it.contains("APEC") || it.contains("ANI"))
        })
        assertEquals(499.65, result.knownEmployerContributions!!, 0.001)
        assertTrue(result.knownEmployerContributions!! > 0.0)
        assertFalse(result.employerCostComplete)
        assertTrue(result.employerCostWarnings.any { it.contains("aucun total complet", ignoreCase = true) })
        assertTrue(result.employerCostWarnings.any { it.contains("part patronale de mutuelle") })
        assertTrue(result.employerCostWarnings.any { it.contains("réductions/exonérations du mois à confirmer") })
        val zeroConfirmed = NetSalaryEngineV2.calculate(2500.0, 2026, company.copy(
            employerReductionAmount = 0.0,
            employerReductionSource = "Bulletin vérifié janvier 2026"
        ))
        assertFalse(zeroConfirmed.employerCostWarnings.any { it.contains("réductions/exonérations du mois à confirmer") })
        assertFalse(zeroConfirmed.employerCostComplete)
        assertEquals(result.netBeforeIncomeTax, zeroConfirmed.netBeforeIncomeTax, 0.0)
        assertEquals(result.netTaxable, zeroConfirmed.netTaxable)
        assertEquals(result.incomeTax, zeroConfirmed.incomeTax)
        assertEquals(result.netAfterIncomeTax, zeroConfirmed.netAfterIncomeTax)
        assertEquals(result.complete, zeroConfirmed.complete)
        assertEquals(result.grossReliable, zeroConfirmed.grossReliable)
        assertEquals(result.warnings, zeroConfirmed.warnings)
        assertTrue(zeroConfirmed.employerProjection!!.warnings.any {
            it.contains("couverture partielle") && (it.contains("APEC") || it.contains("ANI"))
        })
        assertEquals(zeroConfirmed.knownEmployerContributions!!, zeroConfirmed.knownEmployerContributionsAfterReductions!!, 0.001)
    }

    @Test fun `one cent excessive reduction is rejected without a zero clamp`() {
        val before = NetSalaryEngineV2.calculate(2500.0, 2026, company())
        val cents = before.employerProjection!!.knownSubtotalBeforeReductionsCents!!
        val tooMuch = NetSalaryEngineV2.calculate(2500.0, 2026, company().copy(
            employerReductionAmount = PayrollMoneyV2.amount(cents + 1), employerReductionSource = "Fixture DSN"))
        assertEquals(before.knownEmployerContributions, tooMuch.knownEmployerContributions)
        assertNull(tooMuch.knownEmployerContributionsAfterReductions)
        assertFalse(tooMuch.employerProjection!!.monetaryReliable)
        assertEquals(before.netBeforeIncomeTax, tooMuch.netBeforeIncomeTax, 0.0)
        assertEquals(before.netTaxable, tooMuch.netTaxable)
        assertEquals(before.incomeTax, tooMuch.incomeTax)
        assertEquals(before.netAfterIncomeTax, tooMuch.netAfterIncomeTax)
        assertEquals(before.complete, tooMuch.complete)
    }

    @Test fun `unproven company period or gross exposes no employer subtotal`() {
        for (snapshot in listOf(company().copy(companyId = ""),
            company().copy(referenceDate = LocalDate.of(2027, 1, 31)),
            company().copy(benefitsInKindReliable = false))) {
            val result = NetSalaryEngineV2.calculate(2500.0, 2026, snapshot)
            assertNull(result.knownEmployerContributions)
            assertNull(result.knownEmployerContributionsAfterReductions)
            assertFalse(result.employerProjection!!.context.valid)
        }
    }

    @Test fun `unknown company provenance and invalid supplied rate cannot certify known amounts`() {
        val missing = NetSalaryEngineV2.calculate(2500.0, 2026, company().copy(atMpEmployerSource = null))
        assertEquals(EmployerContributionProjectionV2.State.INVALID,
            missing.employerProjection!!.lines.single { it.family == EmployerContributionProjectionV2.Family.AT_MP }.state)
        val invalid = NetSalaryEngineV2.calculate(2500.0, 2026, company().copy(atMpEmployerRate = Double.NaN))
        assertNull(invalid.knownEmployerContributions)
        assertEquals(EmployerContributionProjectionV2.State.INVALID,
            invalid.employerProjection!!.lines.single { it.family == EmployerContributionProjectionV2.Family.AT_MP }.state)
        assertEquals(missing.netBeforeIncomeTax, invalid.netBeforeIncomeTax, 0.0)
        assertEquals(missing.netAfterIncomeTax, invalid.netAfterIncomeTax)
    }
    @Test fun `calculated source lines reach shared screen and PDF detail`() {
        val result=NetSalaryEngineV2.calculate(2500.0,2026,company())
        assertTrue(result.contributionDetails.any{it.id.startsWith("statutory:")})
        assertTrue(result.contributionDetails.any{it.id.startsWith("retirement:")})
        val rows=CalculatedPayslipDetailsV2.contributions(result,true,result.employerProjection,"company",2026,1)
        val atMp=rows.single{it.label=="Accidents du travail / maladies professionnelles"}
        assertEquals("2500,00 €",atMp.base)
        assertEquals("2,0000 %",atMp.employerRate)
        assertEquals("50,00 €",atMp.employerAmount)
        assertEquals("Fixture AT/MP",atMp.source)
        assertTrue(rows.any{it.source.contains("Agirc-Arrco")&&it.employerAmount!="À confirmer"})
        val statutory=result.contributionDetails.filter{it.family==EmployerContributionProjectionV2.Family.STATUTORY}
        assertEquals(result.employerProjection!!.lines.single{it.family==EmployerContributionProjectionV2.Family.STATUTORY}.amountCents,
            PayrollMoneyV2.sum(statutory.map{PayrollMoneyV2.cents(it.employerAmount!!)!!}))
        val tables=SalaryExamplePdfV2.presentationSections(listOf(SalaryExamplePdfV2.PdfSection("Details",emptyList(),rows)))
        assertEquals(rows.map{it.cells()},tables.single().rows)
        assertFalse(result.employerProjection!!.coverageComplete)
    }

    @Test fun `retirement detail carries canonical cents at half cent binary boundary`() {
        val result=NetSalaryEngineV2.calculate(118.75,2026,company())
        val line=result.contributionDetails.single { it.family==EmployerContributionProjectionV2.Family.COMPLEMENTARY_RETIREMENT && it.employerRate==0.0472 }
        assertEquals(561L,PayrollMoneyV2.cents(line.employerAmount!!))
        val rows=CalculatedPayslipDetailsV2.contributions(result,true,result.employerProjection,"company",2026,1)
        assertEquals("5,61 €",rows.single{it.label==line.label}.employerAmount)
        val sum=PayrollMoneyV2.sum(result.contributionDetails.filter{it.family==EmployerContributionProjectionV2.Family.COMPLEMENTARY_RETIREMENT}.map{PayrollMoneyV2.cents(it.employerAmount!!)!!})
        assertEquals(result.employerProjection!!.lines.single{it.family==EmployerContributionProjectionV2.Family.COMPLEMENTARY_RETIREMENT}.amountCents,sum)
    }

    @Test fun `all checked rate families carry cents used by native projection`() {
        val configuration=company().copy(alsaceMoselleLocalRegime=true,
            atMpEmployerRate=.013,employerMobilityRate=.013,
            employerUnemploymentRate=.013,employerAgsRate=.013,employerUnemploymentAgsSource="Synthetic source",
            employerApprenticeshipPrincipalRate=.013,employerApprenticeshipBalanceRate=.013,employerApprenticeshipSource="Synthetic source")
        val result=NetSalaryEngineV2.calculate(115.0,2026,configuration)
        val alsace=result.contributionDetails.single{it.family==EmployerContributionProjectionV2.Family.STATUTORY&&it.employeeRate==.013}
        assertEquals(150L,PayrollMoneyV2.cents(alsace.employeeAmount!!))
        assertEquals(PayrollMoneyV2.cents(result.statutory),PayrollMoneyV2.sum(result.contributionDetails.filter{it.family==EmployerContributionProjectionV2.Family.STATUTORY}.map{PayrollMoneyV2.cents(it.employeeAmount!!)!!}))
        val rows=CalculatedPayslipDetailsV2.contributions(result,true,result.employerProjection,"company",2026,1)
        listOf("at-mp","mobility","unemployment","ags","apprenticeship-principal","apprenticeship-balance").forEach { id ->
            val detail=result.contributionDetails.single{it.id==id}
            assertEquals(id,150L,PayrollMoneyV2.cents(detail.employerAmount!!))
            assertEquals(id,"1,50 €",rows.single{it.label==detail.label}.employerAmount)
        }
    }

}
