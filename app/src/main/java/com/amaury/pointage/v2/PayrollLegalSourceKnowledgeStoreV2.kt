package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import com.amaury.pointage.v2.engine.PayrollSourceKnowledgeProofV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Journal local non destructif des preuves de contrôle des sources juridiques prioritaires. */
object PayrollLegalSourceKnowledgeStoreV2 {
    private const val PREFS = "horatrack_v2_payroll_source_knowledge"
    private const val KEY_PROOFS = "proofs"
    private const val MAX_PROOFS = 250

    fun recordAuditProof(context: Context, proof: PayrollSourceKnowledgeProofV2.Proof) {
        val current = load(context).toMutableList()
        current.removeAll { sameIdentity(it, proof) }
        current += proof
        val array = JSONArray()
        current.takeLast(MAX_PROOFS).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROOFS, array.toString())
            .apply()
    }

    fun knowledgeForOvertime(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        PayrollSourceKnowledgeProofV2.knowledgeMapForOvertime(
            load(context), companyId, idcc, referenceDate
        )

    fun knowledgeForProvidentContribution(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        PayrollSourceKnowledgeProofV2.knowledgeMapForProvidentContribution(
            load(context), companyId, idcc, referenceDate
        )

    fun knowledgeForMealBasketSubject(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        subjectKey: String
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        PayrollSourceKnowledgeProofV2.knowledgeMapForMealBasketSubject(
            proofs = load(context),
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate,
            subjectKey = subjectKey
        )

    fun auditTrail(context: Context): List<PayrollSourceKnowledgeProofV2.Proof> = load(context)

    private fun load(context: Context): List<PayrollSourceKnowledgeProofV2.Proof> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROOFS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    private fun sameIdentity(
        left: PayrollSourceKnowledgeProofV2.Proof,
        right: PayrollSourceKnowledgeProofV2.Proof
    ): Boolean =
        left.source == right.source &&
            left.matter == right.matter &&
            left.companyId.orEmpty().trim() == right.companyId.orEmpty().trim() &&
            normalizeIdcc(left.idcc) == normalizeIdcc(right.idcc) &&
            left.subjectKey.orEmpty().trim().uppercase() == right.subjectKey.orEmpty().trim().uppercase() &&
            left.referenceFrom == right.referenceFrom &&
            left.referenceTo == right.referenceTo &&
            left.officialScopeId == right.officialScopeId

    private fun encode(proof: PayrollSourceKnowledgeProofV2.Proof): JSONObject = JSONObject()
        .put("source", proof.source.name)
        .put("matter", proof.matter.name)
        .put("companyId", proof.companyId ?: JSONObject.NULL)
        .put("idcc", proof.idcc ?: JSONObject.NULL)
        .put("subjectKey", proof.subjectKey ?: JSONObject.NULL)
        .put("referenceFrom", proof.referenceFrom.toString())
        .put("referenceTo", proof.referenceTo.toString())
        .put("officialCoverageThrough", proof.officialCoverageThrough.toString())
        .put("checkedAtMs", proof.checkedAtMs)
        .put("officialScopeId", proof.officialScopeId)
        .put("exhaustive", proof.exhaustive)
        .put("scopeConfirmed", proof.scopeConfirmed)
        .put("outcome", proof.outcome.name)

    private fun decode(obj: JSONObject): PayrollSourceKnowledgeProofV2.Proof? = runCatching {
        PayrollSourceKnowledgeProofV2.Proof(
            source = PayrollLegalArbitratorV2.Source.valueOf(obj.getString("source")),
            matter = PayrollSourceKnowledgeProofV2.Matter.valueOf(obj.getString("matter")),
            companyId = nullableString(obj, "companyId"),
            idcc = nullableString(obj, "idcc"),
            subjectKey = nullableString(obj, "subjectKey"),
            referenceFrom = LocalDate.parse(obj.getString("referenceFrom")),
            referenceTo = LocalDate.parse(obj.getString("referenceTo")),
            officialCoverageThrough = LocalDate.parse(obj.getString("officialCoverageThrough")),
            checkedAtMs = obj.getLong("checkedAtMs"),
            officialScopeId = obj.getString("officialScopeId"),
            exhaustive = obj.optBoolean("exhaustive", false),
            scopeConfirmed = obj.optBoolean("scopeConfirmed", false),
            outcome = PayrollSourceKnowledgeProofV2.Outcome.valueOf(obj.getString("outcome"))
        )
    }.getOrNull()

    private fun nullableString(obj: JSONObject, key: String): String? =
        obj.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun normalizeIdcc(value: String?): String {
        val raw = value.orEmpty().trim()
        return if (raw.isBlank()) "" else raw.padStart(4, '0')
    }
}
