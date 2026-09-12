package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetSalaryEngineV2Test {
    private fun snapshot(
        employerProtection: Double?,
        employeeNonDeductible: Double?,
        contractType: ContractTypeV2 = ContractTypeV2.FULL_TIME,
        weeklyMinutes: Int? = 35 * 60,
        unpaidAbsenceDays: Int = 0,
        alsaceMoselleLocalRegime: Boolean? = false,
        atMpEmployerRate: Double? = null,
        benefitsInKindGross: Double = 0.0,
        employerMobilityRate: Double? = 0.0
    ) = CompanyPayrollOverridesV2.Snapshot(
        companyId="company",
        idcc=null,
        referenceDate=LocalDate.of(2026,1,31),
        entryDate=LocalDate.of(2020,1,1),
        seniorityMonths=72,
        contractType=contractType,
        contractualWeeklyMinutes=weeklyMinutes,
        forfaitAnnualDays=null,
        unpaidAbsenceDays=unpaidAbsenceDays,
        hasUnpaidAbsence=unpaidAbsenceDays>0,
        mutualEmployeeAmount=0.0,
        providentEmployeeAmount=0.0,
        transportEmployeeAmount=0.0,
        employerProtectionTaxableAmount=employerProtection,
        employeeProvidentNonDeductibleAmount=employeeNonDeductible,
        incomeTaxRate=0.05,
        professionalStatus="NON_CADRE",
        protectionCategory=PlasturgieProtectionCategoryV2.classify(null,LocalDate.of(2026,1,31),null),
        warnings=emptyList(),
        alsaceMoselleLocalRegime=alsaceMoselleLocalRegime,
        atMpEmployerRate=atMpEmployerRate,
        benefitsInKindGross=benefitsInKindGross,
        employerMobilityRate=employerMobilityRate
    )

    @Test
    fun taxableNetStaysUnknownWhenEmployerProtectionIsUnknown() {
        val result=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(null,0.0))
        assertNull(result.netTaxable)
        assertNull(result.incomeTax)
        assertNull(result.netAfterIncomeTax)
    }

    @Test
    fun employerAndEmployeeNonDeductibleProtectionAreReintegrated() {
        val base=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0))
        val enriched=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(52.0,3.0))

        assertNotNull(base.netTaxable)
        assertNotNull(enriched.netTaxable)
        assertEquals(55.0,enriched.netTaxable!!-base.netTaxable!!,0.001)
        assertEquals(enriched.netTaxable!!*0.05,enriched.incomeTax!!,0.001)
    }

    @Test
    fun alsaceMoselleContributionReducesNetAndTaxableNetOnce() {
        val general=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,alsaceMoselleLocalRegime=false))
        val local=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,alsaceMoselleLocalRegime=true))

        assertEquals(32.50,local.statutory-general.statutory,0.001)
        assertEquals(32.50,general.netBeforeIncomeTax-local.netBeforeIncomeTax,0.001)
        assertEquals(32.50,general.netTaxable!!-local.netTaxable!!,0.001)
    }

    @Test
    fun atMpContributionIsEmployerOnlyAndDoesNotReduceEmployeeNet() {
        val without=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,atMpEmployerRate=0.0))
        val withAtMp=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,atMpEmployerRate=0.0208))

        assertEquals(52.0,withAtMp.employerAtMpContribution!!,0.001)
        assertEquals(without.netBeforeIncomeTax,withAtMp.netBeforeIncomeTax,0.001)
        assertEquals(without.netTaxable!!,withAtMp.netTaxable!!,0.001)
        assertEquals(without.netAfterIncomeTax!!,withAtMp.netAfterIncomeTax!!,0.001)
    }

    @Test
    fun mobilityContributionIsEmployerOnlyAndDoesNotReduceEmployeeNet() {
        val without=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,employerMobilityRate=0.0))
        val withMobility=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,employerMobilityRate=0.025))

        assertEquals(62.50,withMobility.employerMobilityContribution!!,0.001)
        assertEquals(without.netBeforeIncomeTax,withMobility.netBeforeIncomeTax,0.001)
        assertEquals(without.netTaxable!!,withMobility.netTaxable!!,0.001)
        assertEquals(without.netAfterIncomeTax!!,withMobility.netAfterIncomeTax!!,0.001)
    }

    @Test
    fun mobilityContributionUsesSocialGrossIncludingBenefitInKind() {
        val result=NetSalaryEngineV2.calculate(
            2500.0,
            2026,
            snapshot(0.0,0.0,benefitsInKindGross=200.0,employerMobilityRate=0.02)
        )

        assertEquals(2700.0,result.gross,0.001)
        assertEquals(54.0,result.employerMobilityContribution!!,0.001)
    }

    @Test
    fun employerComplementaryRetirementIsExposedWithoutReducingEmployeeNet() {
        val result=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0))
        val expected=ComplementaryRetirementCatalogV2.estimate(
            gross=2500.0,
            year=2026,
            professionalStatus="NON_CADRE",
            ceiling=SocialSecurityCeilingV2.calculate(
                SocialSecurityCeilingV2.Input(
                    year=2026,
                    referenceDate=LocalDate.of(2026,1,31),
                    contractType=ContractTypeV2.FULL_TIME,
                    contractualWeeklyMinutes=35*60,
                    complementaryMinutes=0,
                    entryDate=LocalDate.of(2020,1,1),
                    unpaidAbsenceDays=0,
                    forfaitAnnualDays=null
                )
            ),
            protectionCategory=PlasturgieProtectionCategoryV2.classify(null,LocalDate.of(2026,1,31),null)
        )

        assertEquals(expected.employerContributions,result.complementaryRetirementEmployer,0.001)
        assertTrue(result.complementaryRetirementEmployer>0.0)
    }

    @Test
    fun benefitInKindIncreasesContributionBaseButIsNotPaidInCash() {
        val without=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,benefitsInKindGross=0.0))
        val withBenefit=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0,benefitsInKindGross=200.0))

        assertEquals(2700.0,withBenefit.gross,0.001)
        assertEquals(200.0,withBenefit.benefitsInKindDeduction,0.001)
        assertTrue(withBenefit.statutory>without.statutory)
        assertTrue(withBenefit.complementaryRetirement>without.complementaryRetirement)
        assertTrue(withBenefit.netBeforeIncomeTax<without.netBeforeIncomeTax)
        assertTrue(withBenefit.netTaxable!!>without.netTaxable!!)
        val expectedCashNet=2500.0-withBenefit.statutory-withBenefit.complementaryRetirement-withBenefit.companyEmployeeDeductions
        assertEquals(expectedCashNet,withBenefit.netBeforeIncomeTax,0.001)
    }

    @Test
    fun fullTimeKeepsFull2026SocialSecurityCeiling() {
        val result=NetSalaryEngineV2.calculate(4500.0,2026,snapshot(0.0,0.0))
        assertEquals(4005.0,result.socialSecurityCeiling!!,0.001)
    }

    @Test
    fun unpaidFullDaysReduceSocialSecurityCeiling() {
        val result=NetSalaryEngineV2.calculate(4500.0,2026,snapshot(0.0,0.0,unpaidAbsenceDays=3))
        assertEquals(4005.0*28.0/31.0,result.socialSecurityCeiling!!,0.001)
    }

    @Test
    fun partTime28HoursUsesReducedCeiling() {
        val result=NetSalaryEngineV2.calculate(
            3500.0,
            2026,
            snapshot(0.0,0.0,ContractTypeV2.PART_TIME,28*60),
            complementaryMinutes=0
        )
        assertEquals(3204.0,result.socialSecurityCeiling!!,0.001)
    }

    @Test
    fun verifiedAniCategoryAloneControlsNationalApecAndEmployerMinimum() {
        val legacyArticle21=PlasturgieProtectionCategoryV2.classify(
            "292",
            LocalDate.of(2026,1,31),
            900
        )
        assertEquals(PlasturgieProtectionCategoryV2.Category.ARTICLE_2_1,legacyArticle21.category)

        val blockedCompany=snapshot(0.0,0.0).copy(
            idcc="292",
            professionalStatus="NON_CADRE",
            protectionCategory=legacyArticle21,
            verifiedProtectionCategory=ProtectionCategoryV2.Result(
                aniCategory=ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                confirmed=false,
                warnings=listOf("Preuve KALI/APEC incomplète")
            )
        )
        val verifiedCompany=blockedCompany.copy(
            verifiedProtectionCategory=ProtectionCategoryV2.Result(
                aniCategory=ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
                confirmed=true,
                source="test KALI + APEC"
            )
        )

        val blocked=NetSalaryEngineV2.calculate(2500.0,2026,blockedCompany)
        val verified=NetSalaryEngineV2.calculate(2500.0,2026,verifiedCompany)

        assertEquals(0.0,blocked.employerStatusContributions,0.001)
        assertEquals(37.50,verified.employerStatusContributions,0.001)
        assertEquals(0.60,verified.complementaryRetirement-blocked.complementaryRetirement,0.001)
        assertEquals(0.90,verified.complementaryRetirementEmployer-blocked.complementaryRetirementEmployer,0.001)
        assertTrue(blocked.warnings.any { it.contains("APEC non appliquée automatiquement") })
        assertTrue(blocked.warnings.none { it.contains("1,50 % non calculé") })
        assertTrue(blocked.employerCostWarnings.any { it.contains("1,50 % non calculé") })
        assertEquals(blocked.conventionProvidentEmployee,verified.conventionProvidentEmployee,0.001)
        assertEquals(blocked.conventionProvidentEmployer,verified.conventionProvidentEmployer,0.001)
    }

    @Test
    fun absenceOfVerifiedSourceNeverUsesLegacyConventionProvidentCalculation() {
        val legacyOutsideAni=PlasturgieProtectionCategoryV2.classify(
            "292",
            LocalDate.of(2026,1,31),
            700
        )
        assertEquals(PlasturgieProtectionCategoryV2.Category.OUTSIDE_2_1_2_2,legacyOutsideAni.category)

        val unverifiedCompany=snapshot(0.0,0.0).copy(
            idcc="292",
            providentEmployeeAmount=null,
            protectionCategory=legacyOutsideAni,
            verifiedProtectionCategory=ProtectionCategoryV2.Result(
                aniCategory=ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                confirmed=false
            )
        )
        val verifiedAniOnlyCompany=unverifiedCompany.copy(
            verifiedProtectionCategory=ProtectionCategoryV2.Result(
                aniCategory=ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
                confirmed=true,
                source="test KALI + APEC"
            )
        )

        val unverified=NetSalaryEngineV2.calculate(2500.0,2026,unverifiedCompany)
        val verifiedAniOnly=NetSalaryEngineV2.calculate(2500.0,2026,verifiedAniOnlyCompany)

        assertEquals(0.0,unverified.conventionProvidentEmployee,0.001)
        assertEquals(0.0,unverified.conventionProvidentEmployer,0.001)
        assertEquals(0.0,verifiedAniOnly.conventionProvidentEmployee,0.001)
        assertEquals(0.0,verifiedAniOnly.conventionProvidentEmployer,0.001)
        assertTrue(unverified.warnings.any { it.contains("ancien barème Plasturgie désactivé",ignoreCase=true) })
        assertTrue(verifiedAniOnly.warnings.any { it.contains("ancien barème Plasturgie désactivé",ignoreCase=true) })
    }

    @Test
    fun verifiedKaliProvidentOverridesLegacyWhenCoverageIsConfirmed() {
        val classification=ConventionClassificationV2(coefficient=700)
        val legacyOutsideAni=PlasturgieProtectionCategoryV2.classify("292",LocalDate.of(2026,1,31),700)
        val company=snapshot(0.0,0.0).copy(
            idcc="292",
            providentEmployeeAmount=null,
            protectionCategory=legacyOutsideAni,
            verifiedProtectionCategory=ProtectionCategoryV2.Result(
                aniCategory=ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
                confirmed=true,
                source="test KALI + APEC"
            ),
            verifiedProvidentClassification=classification,
            verifiedProvidentSeniorityMonths=72,
            verifiedProvidentRules=listOf(verifiedProvidentRule(classification,0.005,0.007)),
            verifiedProvidentCoverage=verifiedProvidentCoverage(classification),
            verifiedProvidentLegalProfile=verifiedProvidentProfile(classification),
            verifiedProvidentSourceKnowledge=confirmedAccoAbsence()
        )

        val result=NetSalaryEngineV2.calculate(2500.0,2026,company)

        assertEquals(12.50,result.conventionProvidentEmployee,0.001)
        assertEquals(17.50,result.conventionProvidentEmployer,0.001)
        assertEquals(12.50,result.companyEmployeeDeductions,0.001)
    }

    @Test
    fun confirmedKaliPathNeverFallsBackToLegacyWhenVerifiedRuleDoesNotMatch() {
        val classification=ConventionClassificationV2(coefficient=700)
        val legacyOutsideAni=PlasturgieProtectionCategoryV2.classify("292",LocalDate.of(2026,1,31),700)
        val mismatchedRule=verifiedProvidentRule(classification,0.005,0.007).copy(
            aniCategories=setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1)
        )
        val company=snapshot(0.0,0.0).copy(
            idcc="292",
            providentEmployeeAmount=null,
            protectionCategory=legacyOutsideAni,
            verifiedProtectionCategory=ProtectionCategoryV2.Result(
                aniCategory=ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
                confirmed=true,
                source="test KALI + APEC"
            ),
            verifiedProvidentClassification=classification,
            verifiedProvidentSeniorityMonths=72,
            verifiedProvidentRules=listOf(mismatchedRule),
            verifiedProvidentCoverage=verifiedProvidentCoverage(classification),
            verifiedProvidentLegalProfile=verifiedProvidentProfile(classification),
            verifiedProvidentSourceKnowledge=confirmedAccoAbsence()
        )

        val result=NetSalaryEngineV2.calculate(2500.0,2026,company)

        assertEquals(0.0,result.conventionProvidentEmployee,0.001)
        assertEquals(0.0,result.conventionProvidentEmployer,0.001)
        assertTrue(result.warnings.any { it.contains("catégorie ANI",ignoreCase=true) })
    }

    @Test
    fun explicitCompanyProvidentStillWinsOverVerifiedConventionMinimum() {
        val classification=ConventionClassificationV2(coefficient=700)
        val legacyOutsideAni=PlasturgieProtectionCategoryV2.classify("292",LocalDate.of(2026,1,31),700)
        val company=snapshot(0.0,0.0).copy(
            idcc="292",
            providentEmployeeAmount=20.0,
            protectionCategory=legacyOutsideAni,
            verifiedProtectionCategory=ProtectionCategoryV2.Result(
                aniCategory=ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
                confirmed=true,
                source="test KALI + APEC"
            ),
            verifiedProvidentClassification=classification,
            verifiedProvidentSeniorityMonths=72,
            verifiedProvidentRules=listOf(verifiedProvidentRule(classification,0.005,0.007)),
            verifiedProvidentCoverage=verifiedProvidentCoverage(classification),
            verifiedProvidentLegalProfile=verifiedProvidentProfile(classification),
            verifiedProvidentSourceKnowledge=confirmedAccoAbsence()
        )

        val result=NetSalaryEngineV2.calculate(2500.0,2026,company)

        assertEquals(0.0,result.conventionProvidentEmployee,0.001)
        assertEquals(17.50,result.conventionProvidentEmployer,0.001)
        assertEquals(20.0,result.companyEmployeeDeductions,0.001)
    }

    private fun verifiedProvidentProfile(
        classification: ConventionClassificationV2
    ) = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020,1,1),
        conventionSeniorityDate = LocalDate.of(2020,1,1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun confirmedAccoAbsence() = mapOf(
        PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
    )

    private fun verifiedProvidentRule(
        classification:ConventionClassificationV2,
        employeeRate:Double,
        employerRate:Double
    )=ConventionProvidentContributionV2.Rule(
        idcc="292",
        ruleId="KALI-PROVIDENT-CONTRIBUTION-KALITEXT000000000001-OUTSIDE_2_1_2_2",
        effectiveFrom=LocalDate.of(2025,1,1),
        classification=classification,
        professionalStatus="NON_CADRE",
        aniCategories=setOf(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2),
        tiers=listOf(
            ConventionProvidentContributionV2.SeniorityTier(
                minimumSeniorityMonths=0,
                bands=listOf(
                    ConventionProvidentContributionV2.Band(
                        label="Salaire brut total",
                        employeeRate=employeeRate,
                        employerRate=employerRate
                    )
                )
            )
        ),
        source="Légifrance KALI test",
        conventionScopeKey="KALITEXT000000000001",
        extensionStatus=ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom=LocalDate.of(2025,1,1)
    )

    private fun verifiedProvidentCoverage(
        classification:ConventionClassificationV2
    ):ConventionMatterCoverageV2.Snapshot {
        val record=ConventionMatterCoverageV2.Record(
            idcc="292",
            matter=ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            effectiveFrom=LocalDate.of(2026,1,1),
            effectiveTo=LocalDate.of(2026,1,31),
            classification=classification,
            professionalStatus="NON_CADRE",
            state=ConventionMatterCoverageV2.State.CONFIRMED_RULES,
            source="audit KALI test",
            checkedAtMs=1L,
            authorities=setOf(ConventionMatterCoverageV2.Authority.KALI)
        )
        return ConventionMatterCoverageV2.Snapshot(
            state=ConventionMatterCoverageV2.State.CONFIRMED_RULES,
            record=record,
            reliable=true,
            warnings=emptyList()
        )
    }
}
