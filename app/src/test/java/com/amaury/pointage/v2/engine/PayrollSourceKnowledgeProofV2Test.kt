package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class PayrollSourceKnowledgeProofV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    private fun proof(
        source: PayrollLegalArbitratorV2.Source,
        companyId: String? = "company-oceplast",
        idcc: String? = "0292",
        coverageThrough: LocalDate = date,
        exhaustive: Boolean = true,
        scopeConfirmed: Boolean = true,
        outcome: PayrollSourceKnowledgeProofV2.Outcome = PayrollSourceKnowledgeProofV2.Outcome.NO_APPLICABLE_RULE
    ) = PayrollSourceKnowledgeProofV2.Proof(
        source = source,
        matter = PayrollSourceKnowledgeProofV2.Matter.OVERTIME_RATE,
        companyId = companyId,
        idcc = idcc,
        referenceFrom = date,
        referenceTo = date,
        officialCoverageThrough = coverageThrough,
        checkedAtMs = 1L,
        officialScopeId = "official-query-fingerprint",
        exhaustive = exhaustive,
        scopeConfirmed = scopeConfirmed,
        outcome = outcome
    )

    @Test
    fun `absence ACCO confirmee seulement pour la bonne entreprise`() {
        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeFor(
            proofs = listOf(proof(PayrollLegalArbitratorV2.Source.ACCO)),
            source = PayrollLegalArbitratorV2.Source.ACCO,
            matter = PayrollSourceKnowledgeProofV2.Matter.OVERTIME_RATE,
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE, knowledge)
    }

    @Test
    fun `preuve ACCO d une autre entreprise reste inconnue`() {
        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeFor(
            proofs = listOf(proof(PayrollLegalArbitratorV2.Source.ACCO, companyId = "other-company")),
            source = PayrollLegalArbitratorV2.Source.ACCO,
            matter = PayrollSourceKnowledgeProofV2.Matter.OVERTIME_RATE,
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.Knowledge.UNKNOWN, knowledge)
    }

    @Test
    fun `absence KALI exige le bon IDCC`() {
        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeFor(
            proofs = listOf(proof(PayrollLegalArbitratorV2.Source.KALI, idcc = "0001")),
            source = PayrollLegalArbitratorV2.Source.KALI,
            matter = PayrollSourceKnowledgeProofV2.Matter.OVERTIME_RATE,
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.Knowledge.UNKNOWN, knowledge)
    }

    @Test
    fun `controle non exhaustif ne prouve jamais une absence`() {
        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeFor(
            proofs = listOf(proof(PayrollLegalArbitratorV2.Source.ACCO, exhaustive = false)),
            source = PayrollLegalArbitratorV2.Source.ACCO,
            matter = PayrollSourceKnowledgeProofV2.Matter.OVERTIME_RATE,
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.Knowledge.UNKNOWN, knowledge)
    }

    @Test
    fun `couverture officielle anterieure a la date de paie ne deverrouille pas le repli`() {
        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeFor(
            proofs = listOf(
                proof(
                    PayrollLegalArbitratorV2.Source.ACCO,
                    coverageThrough = LocalDate.of(2026, 9, 6)
                )
            ),
            source = PayrollLegalArbitratorV2.Source.ACCO,
            matter = PayrollSourceKnowledgeProofV2.Matter.OVERTIME_RATE,
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.Knowledge.UNKNOWN, knowledge)
    }

    @Test
    fun `une regle trouvee n est jamais transformee en absence`() {
        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeMapForOvertime(
            proofs = listOf(
                proof(
                    PayrollLegalArbitratorV2.Source.ACCO,
                    outcome = PayrollSourceKnowledgeProofV2.Outcome.RULE_FOUND
                )
            ),
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertFalse(knowledge.containsKey(PayrollLegalArbitratorV2.Source.ACCO))
    }
}
