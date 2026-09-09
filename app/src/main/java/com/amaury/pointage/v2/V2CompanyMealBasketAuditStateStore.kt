package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.MealBasketLegalArbitrationBridgeV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * État local des paquets ACCO repas vus par l'audit officiel.
 *
 * Une occurrence détectée mais non structurée reste persistante et bloque la paie. Un ancien paquet
 * complet ne peut donc pas masquer silencieusement un avenant ou un autre accord encore ambigu.
 */
object V2CompanyMealBasketAuditStateStore {
    private const val PREFS = "horatrack_v2_company_meal_basket_audit_state"
    private const val KEY = "records"
    private const val MAX_RECORDS = 300

    enum class State { COMPLETE, UNRESOLVED }

    data class Record(
        val companyId: String,
        val agreementId: String,
        val siret: String,
        val classification: ConventionClassificationV2,
        val professionalStatus: String,
        val state: State,
        val subjects: Set<String>,
        val checkedAtMs: Long
    ) {
        fun structurallyValid(): Boolean = companyId.isNotBlank() &&
            agreementId.matches(Regex("^ACCOTEXT\\d+$")) &&
            siret.length == 14 && siret.all(Char::isDigit) &&
            !classification.isEmpty() &&
            professionalStatus in setOf("CADRE", "NON_CADRE") &&
            subjects.isNotEmpty() &&
            checkedAtMs > 0L
    }

    fun mark(
        context: Context,
        companyId: String,
        agreementId: String,
        profile: ConventionLegalProfileV2,
        state: State,
        subjects: Set<String>
    ): Boolean {
        val siret = profile.siret.filter(Char::isDigit)
        val status = profile.professionalStatus?.trim()?.uppercase().orEmpty()
        val normalizedSubjects = subjects.mapTo(linkedSetOf()) { MealBasketLegalArbitrationBridgeV2.subject(it) }
            .ifEmpty { setOf("MEAL_OTHER") }
        val record = Record(
            companyId = companyId,
            agreementId = agreementId.trim().uppercase(),
            siret = siret,
            classification = profile.classification.normalized(),
            professionalStatus = status,
            state = state,
            subjects = normalizedSubjects,
            checkedAtMs = System.currentTimeMillis().coerceAtLeast(1L)
        )
        if (!record.structurallyValid()) return false

        val current = load(context).toMutableList()
        current.removeAll { sameIdentity(it, record) }
        current += record
        // Ne jamais faire disparaître silencieusement un ancien UNRESOLVED (ou tout autre état)
        // pour respecter une limite de cache. Si le paquet complet ne peut plus être conservé,
        // l'écriture échoue : l'audit appelant doit rester INCOMPLETE/fail-closed.
        if (current.size > MAX_RECORDS) return false
        return persist(context, current.sortedByDescending { it.checkedAtMs })
    }

    /** Supprime l'ancien état d'un ACCOTEXT quand le nouveau contenu officiel n'a plus d'objet repas. */
    fun clearAgreement(
        context: Context,
        companyId: String,
        agreementId: String,
        profile: ConventionLegalProfileV2
    ): Boolean {
        val acco = agreementId.trim().uppercase()
        val siret = profile.siret.filter(Char::isDigit)
        val status = profile.professionalStatus?.trim()?.uppercase().orEmpty()
        if (companyId.isBlank() || !acco.matches(Regex("^ACCOTEXT\\d+$")) || siret.length != 14 ||
            profile.classification.isEmpty() || status !in setOf("CADRE", "NON_CADRE")) return false

        val current = load(context).toMutableList()
        val changed = current.removeAll { record ->
            record.companyId == companyId &&
                record.agreementId == acco &&
                record.siret == siret &&
                record.classification.normalized() == profile.classification.normalized() &&
                record.professionalStatus == status
        }
        return if (!changed) true else persist(context, current)
    }

    fun unresolvedFor(
        context: Context,
        companyId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): List<Record> = matching(
        context, companyId, expectedSiret, classification, professionalStatus
    ).filter { it.state == State.UNRESOLVED }

    fun completeAgreementIdsFor(
        context: Context,
        companyId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): Set<String> = matching(
        context, companyId, expectedSiret, classification, professionalStatus
    ).filter { it.state == State.COMPLETE }.mapTo(linkedSetOf()) { it.agreementId }

    private fun matching(
        context: Context,
        companyId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): List<Record> {
        val siret = expectedSiret.filter(Char::isDigit)
        val status = professionalStatus.trim().uppercase()
        if (companyId.isBlank() || siret.length != 14 || classification.isEmpty()) return emptyList()
        return load(context).filter {
            it.companyId == companyId &&
                it.siret == siret &&
                it.classification.normalized() == classification.normalized() &&
                it.professionalStatus == status
        }
    }

    private fun sameIdentity(left: Record, right: Record): Boolean =
        left.companyId == right.companyId &&
        left.agreementId == right.agreementId &&
        left.siret == right.siret &&
        left.classification.normalized() == right.classification.normalized() &&
        left.professionalStatus == right.professionalStatus

    private fun persist(context: Context, records: List<Record>): Boolean {
        val array = JSONArray()
        records.filter { it.structurallyValid() }.forEach { record ->
            array.put(JSONObject()
                .put("companyId", record.companyId)
                .put("agreementId", record.agreementId)
                .put("siret", record.siret)
                .put("classification", encodeClassification(record.classification))
                .put("professionalStatus", record.professionalStatus)
                .put("state", record.state.name)
                .put("subjects", JSONArray(record.subjects.toList()))
                .put("checkedAtMs", record.checkedAtMs))
        }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }

    private fun load(context: Context): List<Record> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw.ifBlank { "[]" })
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.optJSONObject(index) ?: continue)?.takeIf { it.structurallyValid() }?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient ?: JSONObject.NULL)
        .put("level", value.level ?: JSONObject.NULL)
        .put("echelon", value.echelon ?: JSONObject.NULL)
        .put("position", value.position ?: JSONObject.NULL)
        .put("group", value.group ?: JSONObject.NULL)
        .put("category", value.category ?: JSONObject.NULL)
        .put("employment", value.employment ?: JSONObject.NULL)

    private fun decodeClassification(obj: JSONObject): ConventionClassificationV2 = ConventionClassificationV2(
        coefficient = if (obj.isNull("coefficient")) null else obj.optInt("coefficient").takeIf { it > 0 },
        level = nullable(obj, "level"),
        echelon = nullable(obj, "echelon"),
        position = nullable(obj, "position"),
        group = nullable(obj, "group"),
        category = nullable(obj, "category"),
        employment = nullable(obj, "employment")
    )

    private fun decode(obj: JSONObject): Record? = runCatching {
        val rawSubjects = obj.optJSONArray("subjects") ?: JSONArray()
        val subjects = buildSet {
            for (index in 0 until rawSubjects.length()) {
                rawSubjects.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        Record(
            companyId = obj.getString("companyId"),
            agreementId = obj.getString("agreementId"),
            siret = obj.getString("siret"),
            classification = decodeClassification(obj.getJSONObject("classification")),
            professionalStatus = obj.getString("professionalStatus"),
            state = State.valueOf(obj.getString("state")),
            subjects = subjects,
            checkedAtMs = obj.getLong("checkedAtMs")
        )
    }.getOrNull()

    private fun nullable(obj: JSONObject, key: String): String? =
        obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
}
