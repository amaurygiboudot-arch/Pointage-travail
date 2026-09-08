package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.VerifiedProvidentBenefitProviderV2
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CompanyProvidentGuaranteeEquivalenceV2Test {
    private val date = LocalDate.of(2026, 9, 30)
    private val classification = ConventionClassificationV2(coefficient = 700)

    private fun profile() = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun branchDeath(coefficient: Double = 1.0) = ConventionProvidentBenefitV2.Guarantee(
        family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
        label = "Capital décès",
        formula = ConventionProvidentBenefitV2.Formula(
            basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
            coefficient = coefficient
        ),
        socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE,
        evidenceArticleIds = setOf("KALIARTI000000000001")
    )

    private fun companyDeath(
        agreementId: String = "ACCOTEXT000000000001",
        coefficient: Double = 1.0,
        packageComplete: Boolean = true,
        observed: Set<ConventionProvidentBenefitV2.Family> = setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL)
    ) = CompanyProvidentBenefitV2.Rule(
        agreementId = agreementId,
        siret = "12345678901234",
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        minimumSeniorityMonths = 0,
        guarantee = CompanyProvidentBenefitV2.Guarantee(
            family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            label = "Capital décès",
            formula = ConventionProvidentBenefitV2.Formula(
                basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                coefficient = coefficient
            ),
            socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
        ),
        observedFamilies = observed,
        packageComplete = packageComplete,
        evidenceExcerpt = "Capital décès 100 % du salaire annuel de référence."
    )

    private fun spouseRule(agreementId: String = "ACCOTEXT000000000001") = CompanyProvidentBenefitV2.Rule(
        agreementId = agreementId,
        siret = "12345678901234",
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        minimumSeniorityMonths = 0,
        guarantee = CompanyProvidentBenefitV2.Guarantee(
            family = ConventionProvidentBenefitV2.Family.SPOUSE_PENSION,
            label = "Rente conjoint",
            formula = ConventionProvidentBenefitV2.Formula(
                basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                coefficient = 0.20
            ),
            socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
        ),
        observedFamilies = setOf(
            ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            ConventionProvidentBenefitV2.Family.SPOUSE_PENSION
        ),
        packageComplete = true,
        evidenceExcerpt = "Rente conjoint 20 % du salaire annuel de référence."
    )

    private fun branch(reliable: Boolean = true, coefficient: Double = 1.0) = VerifiedProvidentBenefitProviderV2.Snapshot(
        guarantees = if (reliable) listOf(branchDeath(coefficient)) else emptyList(),
        reliable = reliable,
        warnings = if (reliable) emptyList() else listOf("KALI incomplet")
    )

    @Test
    fun `paquet identique du meme ACCOTEXT confirme equivalence`() {
        val result = CompanyProvidentGuaranteeEquivalenceV2.resolve(
            profile = profile(),
            referenceDate = date,
            seniorityMonths = 80,
            branch = branch(),
            companyRules = listOf(companyDeath()),
            contributionAgreementIds = setOf("ACCOTEXT000000000001")
        )

        assertTrue(result.reliable)
        assertTrue(result.equivalent == true)
    }

    @Test
    fun `garantie entreprise supplementaire n annule pas equivalence du paquet branche`() {
        val death = companyDeath(
            observed = setOf(
                ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
                ConventionProvidentBenefitV2.Family.SPOUSE_PENSION
            )
        )
        val result = CompanyProvidentGuaranteeEquivalenceV2.resolve(
            profile(), date, 80, branch(), listOf(death, spouseRule()), setOf("ACCOTEXT000000000001")
        )

        assertTrue(result.equivalent == true)
    }

    @Test
    fun `formule differente reste a confirmer et n est pas jugee moins favorable automatiquement`() {
        val result = CompanyProvidentGuaranteeEquivalenceV2.resolve(
            profile(), date, 80, branch(), listOf(companyDeath(coefficient = 1.20)), setOf("ACCOTEXT000000000001")
        )

        assertNull(result.equivalent)
        assertTrue(result.warnings.any { it.contains("plus favorable", ignoreCase = true) })
    }

    @Test
    fun `paquet ACCO incomplet ne prouve jamais equivalence`() {
        val result = CompanyProvidentGuaranteeEquivalenceV2.resolve(
            profile(), date, 80, branch(), listOf(companyDeath(packageComplete = false)), setOf("ACCOTEXT000000000001")
        )

        assertNull(result.equivalent)
        assertTrue(result.warnings.any { it.contains("incomplet", ignoreCase = true) })
    }

    @Test
    fun `garanties d un autre ACCOTEXT ne peuvent pas valider la cotisation candidate`() {
        val result = CompanyProvidentGuaranteeEquivalenceV2.resolve(
            profile(),
            date,
            80,
            branch(),
            listOf(companyDeath(agreementId = "ACCOTEXT000000000099")),
            setOf("ACCOTEXT000000000001")
        )

        assertNull(result.equivalent)
        assertTrue(result.warnings.any { it.contains("aucune garantie ACCO", ignoreCase = true) })
    }

    @Test
    fun `KALI incomplet interdit toute equivalence automatique`() {
        val result = CompanyProvidentGuaranteeEquivalenceV2.resolve(
            profile(), date, 80, branch(reliable = false), listOf(companyDeath()), setOf("ACCOTEXT000000000001")
        )

        assertNull(result.equivalent)
        assertTrue(!result.reliable)
    }
}
