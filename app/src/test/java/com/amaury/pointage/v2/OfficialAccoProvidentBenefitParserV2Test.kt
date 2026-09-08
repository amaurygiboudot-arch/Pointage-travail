package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialAccoProvidentBenefitParserV2Test {
    private fun profile(coefficient: Int = 700) = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = coefficient),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private val header = """
        L'accord entre en vigueur le 1 janvier 2026.
        Il est conclu pour une duree indeterminee.
        Salariés non cadre coefficient 700.
    """.trimIndent()

    @Test
    fun `capital deces complet est structure et rend le paquet complet`() {
        val diagnostic = OfficialAccoProvidentBenefitParserV2.parse(
            profile = profile(),
            agreementId = "ACCOTEXT000000000001",
            officialText = "$header\nCapital décès : sans condition d'ancienneté, 100 % du salaire annuel de référence."
        )

        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), diagnostic.observedFamilies)
        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), diagnostic.structuredFamilies)
        assertTrue(diagnostic.unresolvedOccurrenceFamilies.isEmpty())
        assertEquals(1, diagnostic.rules.size)
        assertTrue(diagnostic.rules.single().packageComplete)
        assertEquals(1.0, diagnostic.rules.single().guarantee.formula.coefficient!!, 0.0001)
    }

    @Test
    fun `deux franchises incapacité incompatibles bloquent l occurrence`() {
        val diagnostic = OfficialAccoProvidentBenefitParserV2.parse(
            profile = profile(),
            agreementId = "ACCOTEXT000000000002",
            officialText = "$header\nIncapacité temporaire : sans condition d'ancienneté, indemnités journalières à 80 % du salaire mensuel de référence en complément des IJSS, franchise de 3 jours et franchise de 7 jours."
        )

        assertTrue(ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT in diagnostic.observedFamilies)
        assertTrue(ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT in diagnostic.unresolvedOccurrenceFamilies)
        assertFalse(diagnostic.rules.any { it.packageComplete })
    }

    @Test
    fun `coefficient voisin ne contamine jamais le profil`() {
        val diagnostic = OfficialAccoProvidentBenefitParserV2.parse(
            profile = profile(700),
            agreementId = "ACCOTEXT000000000003",
            officialText = """
                L'accord prend effet le 1 janvier 2026. Durée indéterminée.
                Salariés non cadre coefficient 800.
                Capital décès : sans condition d'ancienneté, 100 % du salaire annuel de référence.
            """.trimIndent()
        )

        assertTrue(diagnostic.rules.isEmpty())
        assertTrue(diagnostic.structuredFamilies.isEmpty())
    }

    @Test
    fun `une garantie structuree ne masque pas une seconde occurrence incomplete`() {
        val diagnostic = OfficialAccoProvidentBenefitParserV2.parse(
            profile = profile(),
            agreementId = "ACCOTEXT000000000004",
            officialText = """
                $header
                Capital décès : sans condition d'ancienneté, 100 % du salaire annuel de référence.
                Garantie décès supplémentaire : sans condition d'ancienneté, montant défini par l'assureur.
            """.trimIndent()
        )

        assertTrue(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL in diagnostic.observedFamilies)
        assertTrue(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL in diagnostic.structuredFamilies)
        assertTrue(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL in diagnostic.unresolvedOccurrenceFamilies)
        assertTrue(diagnostic.rules.isNotEmpty())
        assertFalse(diagnostic.rules.all { it.packageComplete })
    }

    @Test
    fun `incapacite sans articulation securite sociale reste incomplete`() {
        val diagnostic = OfficialAccoProvidentBenefitParserV2.parse(
            profile = profile(),
            agreementId = "ACCOTEXT000000000005",
            officialText = "$header\nIncapacité temporaire : sans condition d'ancienneté, indemnités journalières à 80 % du salaire mensuel de référence, franchise de 3 jours."
        )

        assertTrue(ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT in diagnostic.unresolvedOccurrenceFamilies)
        assertTrue(diagnostic.rules.isEmpty())
    }
}
