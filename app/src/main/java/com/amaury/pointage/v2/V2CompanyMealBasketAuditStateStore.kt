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
    private const val STORAGE_WARNING =
        "ACCO repas : état local d'audit illisible ou incohérent ; aucun marqueur COMPLETE/UNRESOLVED n'est exploitable avant nouvel audit."

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
            subjects.all { subject ->
                subject.isNotBlank() && MealBasketLegalArbitrationBridgeV2.subject(subject) == subject
            } &&
            checkedAtMs > 0L
    }

    data class ReadResult(
        val records: List<Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    private data class OptionalString(val valid: Boolean, val value: String?)
    private data class OptionalInt(val valid: Boolean, val value: Int?)

    internal fun canPersistCompleteRecordSet(recordCount: Int): Boolean = recordCount in 0..MAX_RECORDS

    internal fun read(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return corruptResult()
        return decodeRecords(raw)
    }

    internal fun decodeRecords(raw: String): ReadResult {
        if (raw.isBlank()) return corruptResult()
        return runCatching {
            val array = JSONArray(raw)
            var malformed = false
            val records = buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index)
                    if (obj == null) {
                        malformed = true
                        continue
                    }
                    val record = decode(obj)
                    if (record == null || !record.structurallyValid()) {
                        malformed = true
                    } else {
                        add(record)
                    }
                }
            }
            if (!canPersistCompleteRecordSet(records.size)) malformed = true
            if (hasDuplicateIdentity(records)) malformed = true
            ReadResult(
                records = records,
                reliable = !malformed,
                warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
            )
        }.getOrElse { corruptResult() }
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

        val stored = read(context)
        if (!stored.reliable) return false
        val current = stored.records.toMutableList()
        current.removeAll { sameIdentity(it, record) }
        current += record
        // Ne jamais faire disparaître silencieusement un ancien UNRESOLVED (ou tout autre état)
        // pour respecter une limite de cache. Si le paquet complet ne peut plus être conservé,
        // l'écriture échoue : l'audit appelant doit rester INCOMPLETE/fail-closed.
        if (!canPersistCompleteRecordSet(current.size)) return false
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

        val stored = read(context)
        if (!stored.reliable) return false
        val current = stored.records.toMutableList()
        val changed = current.removeAll { record ->
            record.companyId == companyId &&
                record.agreementId == acco &&
                record.siret == siret &&
                record.classification.normalized() == profile.classification.normalized() &&
                record.professionalStatus == status
        }
        return if (!changed) true else persist(context, current)
    }

    fun matchingResult(
        context: Context,
        companyId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): ReadResult = matchingFrom(
        read(context), companyId, expectedSiret, classification, professionalStatus
    )

    internal fun matchingFrom(
        stored: ReadResult,
        companyId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): ReadResult {
        if (!stored.reliable) return stored.copy(records = emptyList())
        val siret = expectedSiret.filter(Char::isDigit)
        val status = professionalStatus.trim().uppercase()
        if (companyId.isBlank() || siret.length != 14 || !siret.all(Char::isDigit) ||
            classification.isEmpty() || status !in setOf("CADRE", "NON_CADRE")) {
            return corruptResult()
        }
        val normalizedClassification = classification.normalized()
        return stored.copy(records = stored.records.filter {
            it.companyId == companyId &&
                it.siret == siret &&
                it.classification.normalized() == normalizedClassification &&
                it.professionalStatus == status
        })
    }

    fun unresolvedFor(
        context: Context,
        companyId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): List<Record> {
        val result = matchingResult(context, companyId, expectedSiret, classification, professionalStatus)
        return if (result.reliable) result.records.filter { it.state == State.UNRESOLVED } else emptyList()
    }

    fun completeAgreementIdsFor(
        context: Context,
        companyId: String,
        expectedSiret: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): Set<String> {
        val result = matchingResult(context, companyId, expectedSiret, classification, professionalStatus)
        return if (result.reliable) {
            result.records.filter { it.state == State.COMPLETE }.mapTo(linkedSetOf()) { it.agreementId }
        } else emptySet()
    }

    private fun sameIdentity(left: Record, right: Record): Boolean =
        left.companyId == right.companyId &&
            left.agreementId == right.agreementId &&
            left.siret == right.siret &&
            left.classification.normalized() == right.classification.normalized() &&
            left.professionalStatus == right.professionalStatus

    private fun hasDuplicateIdentity(records: List<Record>): Boolean {
        for (index in records.indices) {
            for (previous in 0 until index) {
                if (sameIdentity(records[previous], records[index])) return true
            }
        }
        return false
    }

    private fun persist(context: Context, records: List<Record>): Boolean {
        if (!canPersistCompleteRecordSet(records.size) || records.any { !it.structurallyValid() } ||
            hasDuplicateIdentity(records)) return false
        val array = JSONArray()
        records.forEach { record ->
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
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).commit()
        }.getOrDefault(false)
    }

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient ?: JSONObject.NULL)
        .put("level", value.level ?: JSONObject.NULL)
        .put("echelon", value.echelon ?: JSONObject.NULL)
        .put("position", value.position ?: JSONObject.NULL)
        .put("group", value.group ?: JSONObject.NULL)
        .put("category", value.category ?: JSONObject.NULL)
        .put("employment", value.employment ?: JSONObject.NULL)

    private fun decodeClassification(obj: JSONObject): ConventionClassificationV2? {
        val coefficient = optionalPositiveInt(obj, "coefficient")
        if (!coefficient.valid) return null
        val level = optionalString(obj, "level"); if (!level.valid) return null
        val echelon = optionalString(obj, "echelon"); if (!echelon.valid) return null
        val position = optionalString(obj, "position"); if (!position.valid) return null
        val group = optionalString(obj, "group"); if (!group.valid) return null
        val category = optionalString(obj, "category"); if (!category.valid) return null
        val employment = optionalString(obj, "employment"); if (!employment.valid) return null
        return ConventionClassificationV2(
            coefficient = coefficient.value,
            level = level.value,
            echelon = echelon.value,
            position = position.value,
            group = group.value,
            category = category.value,
            employment = employment.value
        ).normalized().takeIf { !it.isEmpty() }
    }

    private fun decode(obj: JSONObject): Record? {
        val companyId = requiredString(obj, "companyId") ?: return null
        val agreementId = requiredString(obj, "agreementId") ?: return null
        val siret = requiredString(obj, "siret") ?: return null
        val classificationObj = obj.opt("classification") as? JSONObject ?: return null
        val classification = decodeClassification(classificationObj) ?: return null
        val professionalStatus = requiredString(obj, "professionalStatus") ?: return null
        val stateRaw = requiredString(obj, "state") ?: return null
        val state = runCatching { State.valueOf(stateRaw) }.getOrNull() ?: return null
        val rawSubjects = obj.opt("subjects") as? JSONArray ?: return null
        if (rawSubjects.length() == 0) return null
        val subjects = linkedSetOf<String>()
        for (index in 0 until rawSubjects.length()) {
            val subject = (rawSubjects.opt(index) as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (MealBasketLegalArbitrationBridgeV2.subject(subject) != subject || !subjects.add(subject)) return null
        }
        val checkedAtMs = requiredLong(obj, "checkedAtMs")?.takeIf { it > 0L } ?: return null
        return Record(
            companyId = companyId,
            agreementId = agreementId,
            siret = siret,
            classification = classification,
            professionalStatus = professionalStatus,
            state = state,
            subjects = subjects,
            checkedAtMs = checkedAtMs
        )
    }

    private fun requiredString(obj: JSONObject, key: String): String? =
        (obj.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }

    private fun optionalString(obj: JSONObject, key: String): OptionalString {
        if (!obj.has(key) || obj.isNull(key)) return OptionalString(true, null)
        val value = (obj.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }
        return OptionalString(value != null, value)
    }

    private fun optionalPositiveInt(obj: JSONObject, key: String): OptionalInt {
        if (!obj.has(key) || obj.isNull(key)) return OptionalInt(true, null)
        val number = obj.opt(key) as? Number ?: return OptionalInt(false, null)
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value <= 0.0 || value > Int.MAX_VALUE) {
            return OptionalInt(false, null)
        }
        return OptionalInt(true, value.toInt())
    }

    private fun requiredLong(obj: JSONObject, key: String): Long? {
        val number = obj.opt(key) as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Long.MIN_VALUE.toDouble() ||
            value > Long.MAX_VALUE.toDouble()) return null
        return number.toLong()
    }

    private fun corruptResult() = ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
}
