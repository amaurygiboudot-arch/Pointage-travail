package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import com.amaury.pointage.v2.engine.PayrollSourceKnowledgeProofV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Journal local non destructif des preuves de contrôle des sources juridiques prioritaires. */
object PayrollLegalSourceKnowledgeStoreV2 {
    private const val PREFS = "horatrack_v2_payroll_source_knowledge"
    private const val KEY_PROOFS = "proofs"
    internal const val MAX_PROOFS = 250
    private const val STORAGE_WARNING =
        "Preuves de contrôle des sources juridiques : stockage local incohérent ; aucune absence de règle ne peut être déduite de cet historique."

    data class ReadResult(
        val proofs: List<PayrollSourceKnowledgeProofV2.Proof>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class KnowledgeResult(
        val knowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PROOFS)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_PROOFS, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeProofs(raw)
    }

    fun recordAuditProof(context: Context, proof: PayrollSourceKnowledgeProofV2.Proof) {
        val stored = read(context)
        check(stored.reliable) { STORAGE_WARNING }

        val current = stored.proofs.toMutableList()
        current.removeAll { sameIdentity(it, proof) }
        current += proof
        check(acceptsPackage(current)) {
            "Preuves de contrôle des sources juridiques : capacité atteinte ou historique ambigu ; aucune preuve existante n'est supprimée silencieusement."
        }
        persist(context, current)
    }

    fun knowledgeForOvertimeResult(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): KnowledgeResult = knowledgeResult(context, companyId) { proofs ->
        PayrollSourceKnowledgeProofV2.knowledgeMapForOvertime(
            proofs, companyId, idcc, referenceDate
        )
    }

    fun knowledgeForOvertime(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeForOvertimeResult(context, companyId, idcc, referenceDate).knowledge

    fun knowledgeForProvidentContributionResult(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): KnowledgeResult = knowledgeResult(context, companyId) { proofs ->
        PayrollSourceKnowledgeProofV2.knowledgeMapForProvidentContribution(
            proofs, companyId, idcc, referenceDate
        )
    }

    fun knowledgeForProvidentContribution(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeForProvidentContributionResult(context, companyId, idcc, referenceDate).knowledge

    fun knowledgeForMealBasketSubjectResult(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        subjectKey: String
    ): KnowledgeResult = knowledgeResult(context, companyId) { proofs ->
        PayrollSourceKnowledgeProofV2.knowledgeMapForMealBasketSubject(
            proofs = proofs,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate,
            subjectKey = subjectKey
        )
    }

    fun knowledgeForMealBasketSubject(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        subjectKey: String
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeForMealBasketSubjectResult(context, companyId, idcc, referenceDate, subjectKey).knowledge

    fun knowledgeForSeniorityPremiumResult(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): KnowledgeResult = knowledgeResult(context, companyId) { proofs ->
        PayrollSourceKnowledgeProofV2.knowledgeMapForSeniorityPremium(
            proofs = proofs,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate
        )
    }

    fun knowledgeForSeniorityPremium(
        context: Context,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeForSeniorityPremiumResult(context, companyId, idcc, referenceDate).knowledge

    fun auditTrail(context: Context): List<PayrollSourceKnowledgeProofV2.Proof> {
        val stored = read(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.proofs
    }

    internal fun acceptsPackage(proofs: List<PayrollSourceKnowledgeProofV2.Proof>): Boolean =
        proofs.size <= MAX_PROOFS && !hasDuplicateIdentity(proofs)

    internal fun decodeProofs(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val proofs = mutableListOf<PayrollSourceKnowledgeProofV2.Proof>()
        var malformed = array.length() > MAX_PROOFS
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val proof = obj?.let(::decode)
            if (proof == null) {
                malformed = true
            } else {
                proofs += proof
            }
        }
        if (hasDuplicateIdentity(proofs)) malformed = true
        return ReadResult(
            proofs = proofs,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    internal fun knowledgeFrom(
        stored: ReadResult,
        resolver: (List<PayrollSourceKnowledgeProofV2.Proof>) -> Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge>
    ): KnowledgeResult {
        if (!stored.reliable) {
            return KnowledgeResult(
                knowledge = emptyMap(),
                reliable = false,
                warnings = (listOf(STORAGE_WARNING) + stored.warnings).distinct()
            )
        }
        return KnowledgeResult(
            knowledge = resolver(stored.proofs),
            reliable = true,
            warnings = emptyList()
        )
    }

    /**
     * Une preuve ACCO est liée au SIRET officiel contrôlé, pas seulement au companyId local.
     * Les anciennes preuves dont l'empreinte ne correspond pas au SIRET courant sont conservées
     * dans le journal mais ne peuvent plus déverrouiller un repli KALI.
     */
    internal fun scopeAccoProofs(
        proofs: List<PayrollSourceKnowledgeProofV2.Proof>,
        currentSiret: String?
    ): List<PayrollSourceKnowledgeProofV2.Proof> {
        val expectedScope = PayrollSourceKnowledgeProofV2.accoOfficialScopeId(currentSiret)
        return proofs.filter { proof ->
            proof.source != PayrollLegalArbitratorV2.Source.ACCO ||
                (expectedScope != null && proof.officialScopeId.trim() == expectedScope)
        }
    }

    private fun knowledgeResult(
        context: Context,
        companyId: String,
        resolver: (List<PayrollSourceKnowledgeProofV2.Proof>) -> Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge>
    ): KnowledgeResult {
        val stored = read(context)
        if (!stored.reliable) return knowledgeFrom(stored, resolver)
        val currentSiret = SalaryCompanyStore.withConfirmedCompany(context, companyId) { it.siret }
        val scoped = stored.copy(proofs = scopeAccoProofs(stored.proofs, currentSiret))
        return knowledgeFrom(scoped, resolver)
    }

    private fun persist(context: Context, proofs: List<PayrollSourceKnowledgeProofV2.Proof>) {
        check(acceptsPackage(proofs)) {
            "Preuves de contrôle des sources juridiques : historique invalide, ambigu ou trop volumineux."
        }
        val array = JSONArray()
        proofs.forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROOFS, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "Preuves de contrôle des sources juridiques : sauvegarde locale impossible." }
    }

    private fun sameIdentity(
        left: PayrollSourceKnowledgeProofV2.Proof,
        right: PayrollSourceKnowledgeProofV2.Proof
    ): Boolean =
        left.source == right.source &&
            left.matter == right.matter &&
            left.companyId.orEmpty().trim() == right.companyId.orEmpty().trim() &&
            normalizeIdcc(left.idcc) == normalizeIdcc(right.idcc) &&
            normalizeSubjectIdentity(left.subjectKey) == normalizeSubjectIdentity(right.subjectKey) &&
            left.referenceFrom == right.referenceFrom &&
            left.referenceTo == right.referenceTo &&
            left.officialScopeId.trim() == right.officialScopeId.trim()

    private fun hasDuplicateIdentity(proofs: List<PayrollSourceKnowledgeProofV2.Proof>): Boolean =
        proofs.indices.any { leftIndex ->
            ((leftIndex + 1) until proofs.size).any { rightIndex ->
                sameIdentity(proofs[leftIndex], proofs[rightIndex])
            }
        }

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

    private fun normalizeSubjectIdentity(value: String?): String = value.orEmpty().trim().uppercase()

    private fun normalizeIdcc(value: String?): String {
        val raw = value.orEmpty().trim()
        return if (raw.isBlank()) "" else raw.padStart(4, '0')
    }
}
