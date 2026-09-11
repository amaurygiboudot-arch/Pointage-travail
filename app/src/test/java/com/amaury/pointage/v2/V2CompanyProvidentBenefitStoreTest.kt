package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.CompanyProvidentBenefitV2
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2CompanyProvidentBenefitStoreTest {
    private val classification = ConventionClassificationV2(coefficient = 700)

    private fun rule(
        siret: String = "12345678901234",
        coefficient: Double = 1.0,
        seniority: Int = 0,
        agreementId: String = "ACCOTEXT000000000001"
    ) = CompanyProvidentBenefitV2.Rule(
        agreementId = agreementId,
        siret = siret,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        minimumSeniorityMonths = seniority,
        guarantee = CompanyProvidentBenefitV2.Guarantee(
            family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            label = "Capital décès",
            formula = ConventionProvidentBenefitV2.Formula(
                basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                coefficient = coefficient
            ),
            socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
        ),
        observedFamilies = setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL),
        packageComplete = true,
        evidenceExcerpt = "Capital décès 100 % du salaire annuel de référence."
    )

    @Test
    fun `entreprise indisponible rend les garanties ACCO non fiables`() {
        val result = V2CompanyProvidentBenefitStore.companyUnavailableResult("company-a")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.any {
            it.contains("absente", ignoreCase = true) && it.contains("orpheline", ignoreCase = true)
        })
    }

    @Test
    fun `regle exacte et SIRET exact sont acceptes`() {
        assertTrue(V2CompanyProvidentBenefitStore.acceptsVerifiedRule(rule(), "12345678901234"))
    }

    @Test
    fun `mauvais SIRET est refuse`() {
        assertFalse(V2CompanyProvidentBenefitStore.acceptsVerifiedRule(rule("99999999999999"), "12345678901234"))
    }

    @Test
    fun `revision formule ou anciennete remplace la meme identite juridique`() {
        val old = rule(coefficient = 1.0, seniority = 0)
        val revised = rule(coefficient = 1.25, seniority = 12)

        assertTrue(V2CompanyProvidentBenefitStore.sameLegalIdentity(old, revised))
    }

    @Test
    fun `encodage decodage conserve le paquet complet`() {
        val initial = rule()
        val decoded = V2CompanyProvidentBenefitStore.decodeRules(
            V2CompanyProvidentBenefitStore.encodeRules(listOf(initial))
        )

        assertEquals(1, decoded.size)
        assertTrue(decoded.single().packageComplete)
        assertEquals(initial.guarantee.family, decoded.single().guarantee.family)
        assertEquals(initial.guarantee.formula.coefficient!!, decoded.single().guarantee.formula.coefficient!!, 0.0001)
    }

    @Test
    fun `historique vide explicite est fiable`() {
        val result = V2CompanyProvidentBenefitStore.decodeVerified("[]")

        assertTrue(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible rend le stockage non fiable`() {
        val result = V2CompanyProvidentBenefitStore.decodeVerified("not-json")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide rend le stockage non fiable`() {
        val result = V2CompanyProvidentBenefitStore.decodeVerified("[{}]")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `variantes concurrentes de la meme identite rendent le paquet ambigu`() {
        val first = rule(coefficient = 1.0, seniority = 0)
        val revised = rule(coefficient = 1.25, seniority = 12)
        val raw = "[${V2CompanyProvidentBenefitStore.encodeRules(listOf(first)).removePrefix("[").removeSuffix("]")},${V2CompanyProvidentBenefitStore.encodeRules(listOf(revised)).removePrefix("[").removeSuffix("]")} ]"

        val result = V2CompanyProvidentBenefitStore.decodeVerified(raw)

        assertFalse(result.reliable)
        assertEquals(2, result.rules.size)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `deux ACCOTEXT distincts restent un paquet valide`() {
        assertTrue(
            V2CompanyProvidentBenefitStore.acceptsVerifiedPackage(
                listOf(
                    rule(agreementId = "ACCOTEXT000000000001"),
                    rule(agreementId = "ACCOTEXT000000000002")
                )
            )
        )
    }
}
