package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CompanyAgreementRuleStoreFailClosedV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    @Test
    fun `liste vide explicite reste un stockage lisible`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords("[]")

        assertTrue(decoded.reliable)
        assertTrue(decoded.records.isEmpty())
    }

    @Test
    fun `json illisible ne devient jamais une liste vide fiable`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords("{not-json")

        assertFalse(decoded.reliable)
        assertTrue(decoded.records.isEmpty())
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide ne disparait pas silencieusement`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords(
            """[
                {"agreementId":"ACCO-1","category":"OVERTIME","excerpt":"Majoration 25 %","confidence":0.9,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":false},
                {"agreementId":"","category":"OVERTIME","excerpt":"Règle cassée","confidence":0.8,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":false}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(1, decoded.records.size)
    }

    @Test
    fun `doublon de candidate ACCO rend le stockage incoherent`() {
        val raw = """{"agreementId":"ACCO-1","category":"OVERTIME","excerpt":"Majoration 25 %","confidence":0.9,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":false}"""
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords("[$raw,$raw]")

        assertFalse(decoded.reliable)
        assertEquals(2, decoded.records.size)
    }

    @Test
    fun `valeur de calcul ne peut pas etre verifiee si la regle ne lest pas`() {
        val decoded = CompanyAgreementRuleStoreV2.decodeRecords(
            """[{"agreementId":"ACCO-1","category":"OVERTIME","excerpt":"Majoration 25 %","confidence":0.9,"verified":false,"effectiveFrom":"","effectiveTo":"","scope":"","calculationValueVerified":true}]"""
        )

        assertFalse(decoded.reliable)
        assertTrue(decoded.records.isEmpty())
    }

    @Test
    fun `regle verifiee sans periode exploitable bloque le repli`() {
        val stored = CompanyAgreementRuleStoreV2.ReadResult(
            records = listOf(candidate(verified = true, effectiveFrom = null, scope = "Tous les salariés")),
            reliable = true,
            warnings = emptyList()
        )

        val result = ApplicableCompanyAgreementRulesV2.resolve(stored, date)

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `regle verifiee datee et scoped reste exploitable`() {
        val stored = CompanyAgreementRuleStoreV2.ReadResult(
            records = listOf(candidate(verified = true, effectiveFrom = "01/01/2026", scope = "Tous les salariés")),
            reliable = true,
            warnings = emptyList()
        )

        val result = ApplicableCompanyAgreementRulesV2.resolve(stored, date)

        assertTrue(result.reliable)
        assertEquals(1, result.rules.size)
    }

    private fun candidate(
        verified: Boolean,
        effectiveFrom: String?,
        scope: String?
    ) = CompanyAgreementRuleStoreV2.StoredCandidate(
        agreementId = "ACCO-1",
        category = CompanyAgreementRuleExtractorV2.Category.OVERTIME,
        excerpt = "Les heures supplémentaires sont majorées de 25 %.",
        confidence = 0.9,
        verified = verified,
        effectiveFrom = effectiveFrom,
        scope = scope,
        calculationValueVerified = false
    )
}
