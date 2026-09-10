package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2CompanyProvidentContributionStoreTest {
    private fun rule(
        siret: String = "12345678901234",
        agreementId: String = "ACCOTEXT000000000001",
        minimumSeniorityMonths: Int = 3,
        employeeRate: Double = 0.004,
        employerRate: Double = 0.006,
        evidence: String = "Cotisation de prévoyance : part salariale 0,40 %, part patronale 0,60 %."
    ) = OfficialAccoProvidentContributionParserV2.Rule(
        agreementId = agreementId,
        siret = siret,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        minimumSeniorityMonths = minimumSeniorityMonths,
        basis = OfficialAccoProvidentContributionParserV2.Basis.GROSS_SALARY,
        employeeRate = employeeRate,
        employerRate = employerRate,
        evidenceExcerpt = evidence
    )

    @Test
    fun `regle acco exacte est acceptée pour le meme siret`() {
        assertTrue(
            V2CompanyProvidentContributionStore.acceptsVerifiedRule(
                rule(),
                "12345678901234"
            )
        )
    }

    @Test
    fun `regle dun autre siret est refusee`() {
        assertFalse(
            V2CompanyProvidentContributionStore.acceptsVerifiedRule(
                rule(siret = "99999999999999"),
                "12345678901234"
            )
        )
    }

    @Test
    fun `identifiant autre que accotext est refuse`() {
        assertFalse(
            V2CompanyProvidentContributionStore.acceptsVerifiedRule(
                rule(agreementId = "KALITEXT000000000001"),
                "12345678901234"
            )
        )
    }

    @Test
    fun `cotisation nulle des deux cotes est refusee`() {
        assertFalse(
            V2CompanyProvidentContributionStore.acceptsVerifiedRule(
                rule(employeeRate = 0.0, employerRate = 0.0),
                "12345678901234"
            )
        )
    }

    @Test
    fun `preuve vide est refusee`() {
        assertFalse(
            V2CompanyProvidentContributionStore.acceptsVerifiedRule(
                rule(evidence = ""),
                "12345678901234"
            )
        )
    }

    @Test
    fun `encodage puis decodage conserve la preuve structuree`() {
        val source = rule()
        val encoded = V2CompanyProvidentContributionStore.encodeRules(listOf(source))
        val decoded = V2CompanyProvidentContributionStore.decodeRules(encoded)

        assertEquals(1, decoded.size)
        assertEquals(source, decoded.single())
        assertEquals(source.fingerprint, decoded.single().fingerprint)
    }

    @Test
    fun `json invalide est explicitement non fiable`() {
        val decoded = V2CompanyProvidentContributionStore.decodeVerified("not-json")

        assertFalse(decoded.reliable)
        assertTrue(decoded.rules.isEmpty())
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide au milieu du cache rend tout le stockage non fiable`() {
        val valid = V2CompanyProvidentContributionStore.encodeRules(listOf(rule()))
        val objectJson = valid.removePrefix("[").removeSuffix("]")
        val decoded = V2CompanyProvidentContributionStore.decodeVerified("[$objectJson,{}]")

        assertFalse(decoded.reliable)
        assertEquals(1, decoded.rules.size)
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `historique vide explicite reste fiable`() {
        val decoded = V2CompanyProvidentContributionStore.decodeVerified("[]")

        assertTrue(decoded.reliable)
        assertTrue(decoded.rules.isEmpty())
    }

    @Test
    fun `deux variantes concurrentes de la meme identite juridique sont refusees`() {
        assertFalse(
            V2CompanyProvidentContributionStore.acceptsVerifiedPackage(
                listOf(
                    rule(employeeRate = 0.003, employerRate = 0.007),
                    rule(employeeRate = 0.004, employerRate = 0.006)
                )
            )
        )
    }

    @Test
    fun `deux accotext juridiquement distincts restent acceptes dans le paquet`() {
        assertTrue(
            V2CompanyProvidentContributionStore.acceptsVerifiedPackage(
                listOf(
                    rule(agreementId = "ACCOTEXT000000000001"),
                    rule(agreementId = "ACCOTEXT000000000002")
                )
            )
        )
    }

    @Test
    fun `meme accotext et profil gardent une identite stable si taux ou anciennete changent`() {
        val oldRule = rule(minimumSeniorityMonths = 0, employeeRate = 0.003, employerRate = 0.007)
        val revised = rule(minimumSeniorityMonths = 6, employeeRate = 0.004, employerRate = 0.006)

        assertTrue(V2CompanyProvidentContributionStore.sameLegalIdentity(oldRule, revised))
    }

    @Test
    fun `deux accotext differents restent deux preuves juridiques distinctes`() {
        assertFalse(
            V2CompanyProvidentContributionStore.sameLegalIdentity(
                rule(agreementId = "ACCOTEXT000000000001"),
                rule(agreementId = "ACCOTEXT000000000002")
            )
        )
    }
}
