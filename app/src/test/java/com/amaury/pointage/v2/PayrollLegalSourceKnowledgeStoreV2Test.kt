package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import com.amaury.pointage.v2.engine.PayrollSourceKnowledgeProofV2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PayrollLegalSourceKnowledgeStoreV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    private fun proof(
        scope: String = "official-scope",
        outcome: PayrollSourceKnowledgeProofV2.Outcome = PayrollSourceKnowledgeProofV2.Outcome.NO_APPLICABLE_RULE,
        checkedAtMs: Long = 1L
    ) = PayrollSourceKnowledgeProofV2.Proof(
        source = PayrollLegalArbitratorV2.Source.ACCO,
        matter = PayrollSourceKnowledgeProofV2.Matter.PROVIDENT_CONTRIBUTION,
        companyId = "company",
        idcc = "0292",
        subjectKey = null,
        referenceFrom = date,
        referenceTo = date,
        officialCoverageThrough = date,
        checkedAtMs = checkedAtMs,
        officialScopeId = scope,
        exhaustive = true,
        scopeConfirmed = true,
        outcome = outcome
    )

    private fun json(value: PayrollSourceKnowledgeProofV2.Proof): JSONObject = JSONObject()
        .put("source", value.source.name)
        .put("matter", value.matter.name)
        .put("companyId", value.companyId)
        .put("idcc", value.idcc)
        .put("subjectKey", JSONObject.NULL)
        .put("referenceFrom", value.referenceFrom.toString())
        .put("referenceTo", value.referenceTo.toString())
        .put("officialCoverageThrough", value.officialCoverageThrough.toString())
        .put("checkedAtMs", value.checkedAtMs)
        .put("officialScopeId", value.officialScopeId)
        .put("exhaustive", value.exhaustive)
        .put("scopeConfirmed", value.scopeConfirmed)
        .put("outcome", value.outcome.name)

    @Test
    fun `historique vide explicite est fiable`() {
        val result = PayrollLegalSourceKnowledgeStoreV2.decodeProofs("[]")

        assertTrue(result.reliable)
        assertTrue(result.proofs.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible rend historique non fiable`() {
        val result = PayrollLegalSourceKnowledgeStoreV2.decodeProofs("not-json")

        assertFalse(result.reliable)
        assertTrue(result.proofs.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide rend historique non fiable`() {
        val result = PayrollLegalSourceKnowledgeStoreV2.decodeProofs("[{}]")

        assertFalse(result.reliable)
        assertTrue(result.proofs.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `deux preuves concurrentes de meme identite rendent historique ambigu`() {
        val first = proof(outcome = PayrollSourceKnowledgeProofV2.Outcome.NO_APPLICABLE_RULE, checkedAtMs = 1L)
        val revised = proof(outcome = PayrollSourceKnowledgeProofV2.Outcome.RULE_FOUND, checkedAtMs = 2L)
        val raw = JSONArray().put(json(first)).put(json(revised)).toString()

        val result = PayrollLegalSourceKnowledgeStoreV2.decodeProofs(raw)

        assertFalse(result.reliable)
        assertTrue(result.proofs.size == 2)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `historique au dela de la capacite est refuse au lieu detre tronque`() {
        val maximum = (0 until PayrollLegalSourceKnowledgeStoreV2.MAX_PROOFS).map { index ->
            proof(scope = "scope-$index", checkedAtMs = index.toLong() + 1L)
        }
        val overflow = maximum + proof(scope = "scope-overflow", checkedAtMs = 999L)

        assertTrue(PayrollLegalSourceKnowledgeStoreV2.acceptsPackage(maximum))
        assertFalse(PayrollLegalSourceKnowledgeStoreV2.acceptsPackage(overflow))
    }

    @Test
    fun `stockage corrompu ne peut jamais produire une absence confirmee`() {
        val stored = PayrollLegalSourceKnowledgeStoreV2.ReadResult(
            proofs = listOf(proof()),
            reliable = false,
            warnings = listOf("stockage corrompu")
        )

        val result = PayrollLegalSourceKnowledgeStoreV2.knowledgeFrom(stored) { proofs ->
            PayrollSourceKnowledgeProofV2.knowledgeMapForProvidentContribution(
                proofs = proofs,
                companyId = "company",
                idcc = "0292",
                referenceDate = date
            )
        }

        assertFalse(result.reliable)
        assertTrue(result.knowledge.isEmpty())
        assertTrue(result.warnings.any { it.contains("stockage", ignoreCase = true) })
    }
}
