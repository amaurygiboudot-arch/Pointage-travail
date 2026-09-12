package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SenioritySourceKnowledgeProofV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    @Test
    fun `absence ACCO anciennete est isolee des autres matieres`() {
        val proof = PayrollSourceKnowledgeProofV2.Proof(
            source = PayrollLegalArbitratorV2.Source.ACCO,
            matter = PayrollSourceKnowledgeProofV2.Matter.SENIORITY_PREMIUM,
            companyId = "company-oceplast",
            idcc = "0292",
            referenceFrom = date,
            referenceTo = date,
            officialCoverageThrough = date,
            checkedAtMs = 1L,
            officialScopeId = "acco:siret:seniority",
            exhaustive = true,
            scopeConfirmed = true,
            outcome = PayrollSourceKnowledgeProofV2.Outcome.NO_APPLICABLE_RULE
        )

        val seniority = PayrollSourceKnowledgeProofV2.knowledgeMapForSeniorityPremium(
            proofs = listOf(proof),
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )
        val overtime = PayrollSourceKnowledgeProofV2.knowledgeMapForOvertime(
            proofs = listOf(proof),
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertEquals(
            PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE,
            seniority[PayrollLegalArbitratorV2.Source.ACCO]
        )
        assertTrue(overtime.isEmpty())
    }

    @Test
    fun `regle ACCO anciennete trouvee ne devient jamais une preuve absence`() {
        val proof = PayrollSourceKnowledgeProofV2.Proof(
            source = PayrollLegalArbitratorV2.Source.ACCO,
            matter = PayrollSourceKnowledgeProofV2.Matter.SENIORITY_PREMIUM,
            companyId = "company-oceplast",
            idcc = "0292",
            referenceFrom = date,
            referenceTo = date,
            officialCoverageThrough = date,
            checkedAtMs = 1L,
            officialScopeId = "acco:siret:seniority",
            exhaustive = true,
            scopeConfirmed = true,
            outcome = PayrollSourceKnowledgeProofV2.Outcome.RULE_FOUND
        )

        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeMapForSeniorityPremium(
            proofs = listOf(proof),
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertTrue(knowledge.isEmpty())
    }

    @Test
    fun `preuve ACCO anciennete autre entreprise ne deverrouille pas KALI`() {
        val proof = PayrollSourceKnowledgeProofV2.Proof(
            source = PayrollLegalArbitratorV2.Source.ACCO,
            matter = PayrollSourceKnowledgeProofV2.Matter.SENIORITY_PREMIUM,
            companyId = "other-company",
            idcc = "0292",
            referenceFrom = date,
            referenceTo = date,
            officialCoverageThrough = date,
            checkedAtMs = 1L,
            officialScopeId = "acco:other:seniority",
            exhaustive = true,
            scopeConfirmed = true,
            outcome = PayrollSourceKnowledgeProofV2.Outcome.NO_APPLICABLE_RULE
        )

        val knowledge = PayrollSourceKnowledgeProofV2.knowledgeMapForSeniorityPremium(
            proofs = listOf(proof),
            companyId = "company-oceplast",
            idcc = "0292",
            referenceDate = date
        )

        assertTrue(knowledge.isEmpty())
    }
}
