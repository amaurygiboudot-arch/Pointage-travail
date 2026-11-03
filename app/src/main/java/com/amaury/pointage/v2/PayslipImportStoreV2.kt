package com.amaury.pointage.v2

import android.content.Context
import android.content.SharedPreferences
import com.amaury.pointage.PayslipImportConfirmationPolicyV2
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Local, account-owned work in progress. A single preferences publication writes the bulletin,
 * confirmed values and bounded evidence, and removes its draft. A failed commit is never promoted
 * to success by reading Android's in-memory preferences: retry writes the same identity again.
 */
object PayslipImportStoreV2 {
    internal const val PREFS = "horatrack_v2_payslips"
    internal const val DRAFTS_KEY = "import_drafts_v2"
    private const val EVIDENCE_KEY = "observed_evidence_v2"
    private const val MAX_DRAFTS = 100
    private const val MAX_JSON = 2_000_000

    enum class Status { SUCCESS, INVALID_INPUT, UNAVAILABLE, STORAGE_BLOCKED, CONFLICT }
    data class CommitResult(val status: Status, val record: V2PayslipStore.Record? = null)
    data class ReadResult(val drafts: List<Draft>, val reliable: Boolean)

    data class Draft internal constructor(
        val id: String,
        val recordId: String,
        val companyId: String,
        val sourceUri: String,
        val sourceMime: String?,
        val year: Int,
        val month: Int,
        val periodConfirmed: Boolean,
        val rawFields: Map<String, String>,
        val proposedValues: Map<String, Double>,
        val proposedEvidence: Map<String, String>,
        val originalRecord: V2PayslipStore.Record?,
        val createdAtMs: Long,
        internal val originalValues: Map<String, Double>?,
        internal val originalEvidence: Map<String, String>?,
        internal val lease: SalaryAccountScope.Lease,
        internal val storageToken: String? = null
    )

    private data class Canonical(
        val records: List<V2PayslipStore.Record>,
        val values: Map<String, Map<String, Double>>,
        val evidence: Map<String, Map<String, String>>
    )
    private data class Target(val record: V2PayslipStore.Record, val values: Map<String, Double>, val evidence: Map<String, String>)

    /** Shared by existing bulletin and observed-value writers, not just the import screen. */
    internal fun <T> withStorageAccess(context: Context, unavailable: () -> T, block: () -> T): T {
        val bound = SalaryAccountScope.bindContext(context)
        val owner = SalaryAccountScope.lease(bound)
        val scope = owner.scope ?: return unavailable()
        if (!SalaryAccountScope.isCurrent(owner)) return unavailable()
        return V2RestoreTransaction.withAccess(SalaryAccountScope.rawContext(bound), scope, unavailable) {
            if (!SalaryAccountScope.isCurrent(owner)) unavailable() else {
                val result = block()
                if (SalaryAccountScope.isCurrent(owner)) result else unavailable()
            }
        }
    }

    fun create(context: Context, companyId: String, sourceUri: String, sourceMime: String?, existingRecordId: String? = null): Draft? {
        val bound = SalaryAccountScope.bindContext(context)
        return withStorageAccess(bound, { null }) {
            val drafts = readLocked(bound) ?: return@withStorageAccess null
            val stored = V2PayslipStore.readResult(bound)
            val original = if (existingRecordId == null) null else {
                if (!stored.reliable) return@withStorageAccess null
                stored.records.singleOrNull { it.id == existingRecordId && it.companyId == companyId }
                    ?: return@withStorageAccess null
            }
            if (original != null) {
                val pending = drafts.filter { it.recordId == original.id }
                if (pending.isNotEmpty()) return@withStorageAccess pending.singleOrNull()
                    ?.takeIf { it.companyId == companyId }
                    ?.copy(periodConfirmed = false)
            }
            val valuesResult = PayslipObservedValuesStoreV2.readResult(bound)
            val oldValues = if (valuesResult.reliable) valuesResult.valuesByRecord[original?.id].orEmpty() else null
            val evidenceResult = readEvidence(preferences(bound))
            val oldEvidence = evidenceResult?.get(original?.id).orEmpty().takeIf { evidenceResult != null }
            val id = UUID.randomUUID().toString()
            val calendar = Calendar.getInstance()
            Draft(
                id = id, recordId = original?.id ?: id, companyId = companyId,
                sourceUri = original?.sourceUri ?: sourceUri, sourceMime = if (original == null) sourceMime else original.sourceMime,
                year = original?.year ?: calendar.get(Calendar.YEAR), month = original?.month ?: calendar.get(Calendar.MONTH),
                periodConfirmed = false,
                rawFields = buildMap {
                    oldValues.orEmpty().forEach { (key, value) -> put(key, value.toString()) }
                    original?.gross?.let { putIfAbsent(PayslipDocumentParserV2.KEY_GROSS, it.toString()) }
                    original?.net?.let { putIfAbsent(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX, it.toString()) }
                },
                proposedValues = oldValues.orEmpty(), proposedEvidence = oldEvidence.orEmpty(),
                originalRecord = original, createdAtMs = System.currentTimeMillis(),
                originalValues = if (original == null) emptyMap() else oldValues,
                originalEvidence = if (original == null) emptyMap() else oldEvidence,
                lease = SalaryAccountScope.lease(bound)
            ).takeIf(::validDraft)
        }
    }

    /** Reloaded drafts require a fresh, explicit period confirmation in the current session. */
    fun read(context: Context, companyId: String): ReadResult {
        val bound = SalaryAccountScope.bindContext(context)
        return withStorageAccess(bound, { ReadResult(emptyList(), false) }) {
            val drafts = readLocked(bound) ?: return@withStorageAccess ReadResult(emptyList(), false)
            ReadResult(drafts.filter { it.companyId == companyId }.map { it.copy(periodConfirmed = false) }
                .sortedByDescending { it.createdAtMs }, true)
        }
    }

    fun load(context: Context, companyId: String, draftId: String): Draft? =
        read(context, companyId).takeIf { it.reliable }?.drafts?.singleOrNull { it.id == draftId }

    /** Invalid/blank text is intentionally retained. It cannot be published as confirmed values. */
    fun save(context: Context, draft: Draft): Draft? {
        val bound = SalaryAccountScope.bindContext(context)
        return withStorageAccess(bound, { null }) {
            if (!owns(bound, draft) || !validDraft(draft) || !validBaseline(draft)) return@withStorageAccess null
            val prefs = preferences(bound)
            val drafts = readLocked(bound) ?: return@withStorageAccess null
            val current = drafts.singleOrNull { it.id == draft.id }
            val encoded = encodeDraft(draft).toString()
            if (current == null && draft.storageToken != null) {
                // A final commit can update RAM but return false. Do not allocate another record.
                val target = target(draft) ?: return@withStorageAccess null
                val canonical = canonical(bound) ?: return@withStorageAccess null
                return@withStorageAccess draft.takeIf { isPublished(canonical, target) }
            }
            if (current != null && (!sameIdentity(current, draft) ||
                    (current.storageToken != draft.storageToken && current.storageToken != encoded))) return@withStorageAccess null
            if (current == null && drafts.size >= MAX_DRAFTS) return@withStorageAccess null
            // An existing partial may only have one pending operation, including concurrent screens.
            if (drafts.any { it.id != draft.id && it.recordId == draft.recordId }) return@withStorageAccess null
            // If a failed final commit published a coherent target in RAM, a later edit may use
            // that exact state as its new baseline. Never rebase against merely similar values.
            val published = current?.let(::target)?.let { previous ->
                canonical(bound)?.takeIf { isPublished(it, previous) }?.let { previous }
            }
            val saving = if (published == null) draft else draft.copy(
                originalRecord = published.record,
                originalValues = published.values,
                originalEvidence = published.evidence
            )
            val updated = drafts.filterNot { it.id == draft.id } + saving
            if (!writeDrafts(prefs, updated)) {
                // commit(false) may already have changed RAM. Keep the previous revision usable;
                // the caller retains unsaved input and must not promise it survived the failure.
                writeDrafts(prefs, drafts)
                return@withStorageAccess null
            }
            saving.copy(storageToken = encodeDraft(saving).toString())
        }
    }

    fun discard(context: Context, draft: Draft): Boolean {
        val bound = SalaryAccountScope.bindContext(context)
        return withStorageAccess(bound, { false }) {
            if (!owns(bound, draft) || !validBaseline(draft)) return@withStorageAccess false
            val drafts = readLocked(bound) ?: return@withStorageAccess false
            val current = drafts.singleOrNull { it.id == draft.id }
            if (current == null) return@withStorageAccess writeDrafts(preferences(bound), drafts)
            if (!sameIdentity(current, draft) || current.storageToken != draft.storageToken) return@withStorageAccess false
            writeDrafts(preferences(bound), drafts.filterNot { it.id == draft.id })
        }
    }

    fun commit(context: Context, draft: Draft): CommitResult {
        val bound = SalaryAccountScope.bindContext(context)
        return withStorageAccess(bound, { CommitResult(Status.UNAVAILABLE) }) {
            if (!owns(bound, draft)) return@withStorageAccess CommitResult(Status.UNAVAILABLE)
            if (!validBaseline(draft)) return@withStorageAccess CommitResult(Status.CONFLICT)
            val target = target(draft) ?: return@withStorageAccess CommitResult(Status.INVALID_INPUT)
            val drafts = readLocked(bound) ?: return@withStorageAccess CommitResult(Status.STORAGE_BLOCKED)
            val current = drafts.singleOrNull { it.id == draft.id }
            val canonical = canonical(bound) ?: return@withStorageAccess CommitResult(Status.STORAGE_BLOCKED)
            if (draft.storageToken == null) return@withStorageAccess CommitResult(Status.CONFLICT)
            val alreadyPublished = isPublished(canonical, target)
            if (current == null) {
                if (!alreadyPublished) return@withStorageAccess CommitResult(Status.CONFLICT)
            } else {
                if (!sameIdentity(current, draft) || current.storageToken != draft.storageToken || current.storageToken != encodeDraft(draft).toString()) {
                    return@withStorageAccess CommitResult(Status.CONFLICT)
                }
                if (!unchanged(canonical, draft) && !alreadyPublished) return@withStorageAccess CommitResult(Status.CONFLICT)
            }
            val records = canonical.records.filterNot { it.id == draft.recordId } + target.record
            val values = canonical.values + (draft.recordId to target.values)
            val evidence = canonical.evidence.toMutableMap().apply {
                if (target.evidence.isEmpty()) remove(draft.recordId) else put(draft.recordId, target.evidence)
            }
            val encodedRecords = V2PayslipStore.encodeRecords(records) ?: return@withStorageAccess CommitResult(Status.INVALID_INPUT)
            val encodedValues = PayslipObservedValuesStoreV2.encodeValues(values) ?: return@withStorageAccess CommitResult(Status.INVALID_INPUT)
            val pending = encodeDrafts(drafts.filterNot { it.id == draft.id })
            val editor = preferences(bound).edit()
                .putString(V2PayslipStore.KEY_ITEMS, encodedRecords)
                .putString(PayslipObservedValuesStoreV2.KEY, encodedValues)
                .putString(EVIDENCE_KEY, PayslipObservedValuesStoreV2.encodeEvidence(evidence))
            if (pending == "{}") editor.remove(DRAFTS_KEY) else editor.putString(DRAFTS_KEY, pending)
            val saved = runCatching { editor.commit() }.getOrDefault(false)
            if (saved) CommitResult(Status.SUCCESS, target.record) else {
                // Keep the durable prior intent discoverable after an ambiguous disk failure.
                // All published payroll keys remain coherent; this only retains the retry intent.
                writeDrafts(preferences(bound), drafts.filterNot { it.id == draft.id } + draft)
                CommitResult(Status.STORAGE_BLOCKED)
            }
        }
    }

    private fun owns(context: Context, draft: Draft): Boolean =
        draft.lease == SalaryAccountScope.lease(context) && SalaryAccountScope.isCurrent(draft.lease)

    private fun sameIdentity(first: Draft, second: Draft): Boolean =
        first.id == second.id && first.recordId == second.recordId && first.companyId == second.companyId &&
            first.sourceUri == second.sourceUri && first.sourceMime == second.sourceMime &&
            first.originalRecord == second.originalRecord && first.createdAtMs == second.createdAtMs &&
            first.originalValues == second.originalValues && first.originalEvidence == second.originalEvidence &&
            first.proposedValues == second.proposedValues && first.proposedEvidence == second.proposedEvidence

    private fun validBaseline(draft: Draft): Boolean = draft.storageToken?.let { raw ->
        runCatching { sameIdentity(decodeDraft(JSONObject(raw), draft.lease), draft) }.getOrDefault(false)
    } ?: true

    private fun unchanged(canonical: Canonical, draft: Draft): Boolean {
        val record = canonical.records.singleOrNull { it.id == draft.recordId }
        if (record != draft.originalRecord) return false
        // If a damaged details store has since been repaired, never overwrite recovered values.
        return canonical.values[draft.recordId].orEmpty() == draft.originalValues.orEmpty() &&
            canonical.evidence[draft.recordId].orEmpty() == draft.originalEvidence.orEmpty()
    }

    private fun isPublished(canonical: Canonical, target: Target): Boolean =
        canonical.records.singleOrNull { it.id == target.record.id } == target.record &&
            canonical.values[target.record.id] == target.values &&
            canonical.evidence[target.record.id].orEmpty() == target.evidence

    private fun target(draft: Draft): Target? {
        if (!validDraft(draft) || !draft.periodConfirmed) return null
        if (draft.rawFields.values.any { !PayslipImportConfirmationPolicyV2.isOptionalAmountValid(it) }) return null
        val values = draft.rawFields.mapNotNull { (key, raw) -> PayslipImportConfirmationPolicyV2.parseAmount(raw)?.let { key to it } }.toMap()
        val gross = values[PayslipDocumentParserV2.KEY_GROSS] ?: return null
        val evidence = draft.proposedEvidence.filter { (key, _) ->
            values[key] != null && draft.proposedValues[key] == values[key]
        }
        val record = V2PayslipStore.Record(
            id = draft.recordId, year = draft.year, month = draft.month, sourceUri = draft.sourceUri,
            sourceMime = draft.sourceMime, gross = gross, net = values[PayslipDocumentParserV2.KEY_NET_BEFORE_TAX],
            extractionConfidence = 1.0, confirmedByUser = true,
            importedAtMs = draft.originalRecord?.importedAtMs ?: draft.createdAtMs, companyId = draft.companyId
        )
        return Target(record, values, evidence)
    }

    private fun preferences(context: Context) = SalaryAccountScope.preferences(context, PREFS)

    private fun canonical(context: Context): Canonical? {
        val records = V2PayslipStore.readResult(context)
        val values = PayslipObservedValuesStoreV2.readResult(context)
        val evidence = readEvidence(preferences(context)) ?: return null
        if (!records.reliable || !values.reliable) return null
        return Canonical(records.records, values.valuesByRecord, evidence)
    }

    private fun readEvidence(prefs: SharedPreferences): Map<String, Map<String, String>>? = runCatching {
        if (!prefs.contains(EVIDENCE_KEY)) emptyMap() else {
            val raw = prefs.getString(EVIDENCE_KEY, null) ?: return@runCatching null
            PayslipObservedValuesStoreV2.decodeEvidence(raw)
        }
    }.getOrNull()

    private fun readLocked(context: Context): List<Draft>? = runCatching {
        val prefs = preferences(context)
        if (!prefs.contains(DRAFTS_KEY)) return@runCatching emptyList()
        val raw = prefs.getString(DRAFTS_KEY, null) ?: return@runCatching null
        if (raw.length > MAX_JSON) return@runCatching null
        val root = JSONObject(raw)
        require(root.length() <= MAX_DRAFTS)
        val lease = SalaryAccountScope.lease(context)
        root.keys().asSequence().map { id ->
            val json = root.getJSONObject(id)
            val draft = decodeDraft(json, lease)
            require(draft.id == id && validDraft(draft))
            draft.copy(storageToken = json.toString())
        }.toList().also { list -> require(list.map { it.recordId }.distinct().size == list.size) }
    }.getOrNull()

    private fun writeDrafts(prefs: SharedPreferences, drafts: List<Draft>): Boolean = runCatching {
        val raw = encodeDrafts(drafts)
        if (raw.length > MAX_JSON) return@runCatching false
        val editor = prefs.edit()
        if (drafts.isEmpty()) editor.remove(DRAFTS_KEY) else editor.putString(DRAFTS_KEY, raw)
        editor.commit()
    }.getOrDefault(false)

    private fun encodeDrafts(drafts: List<Draft>): String = JSONObject().apply {
        drafts.sortedBy { it.id }.forEach { put(it.id, encodeDraft(it)) }
    }.toString()

    private fun validDraft(d: Draft): Boolean =
        d.id.isNotBlank() && d.id.length <= 128 && d.recordId.isNotBlank() && d.recordId.length <= 256 &&
            d.companyId.length <= 256 && d.companyId == d.companyId.trim() &&
            d.sourceUri.isNotBlank() && d.sourceUri.length <= 8192 && (d.sourceMime?.length ?: 0) <= 256 &&
            d.year in 2000..2200 && d.month in 0..11 && d.createdAtMs >= 0L && d.lease.scope != null &&
            d.rawFields.all { (key, value) -> key in PayslipObservedValuesStoreV2.allowedKeys && value.length <= 256 } &&
            d.proposedValues.all { (key, value) -> key in PayslipObservedValuesStoreV2.allowedKeys && value.isFinite() && value >= 0.0 } &&
            d.proposedEvidence.all { (key, value) -> key in PayslipObservedValuesStoreV2.allowedKeys && value.length <= 300 } &&
            (d.originalRecord == null || (d.originalRecord.id == d.recordId && d.originalRecord.companyId == d.companyId &&
                d.originalRecord.sourceUri == d.sourceUri && d.originalRecord.sourceMime == d.sourceMime))

    private fun encodeDraft(d: Draft): JSONObject = JSONObject()
        .put("schema", 1).put("id", d.id).put("recordId", d.recordId).put("owner", d.lease.scope)
        .put("companyId", d.companyId).put("uri", d.sourceUri).put("mime", d.sourceMime ?: JSONObject.NULL)
        .put("year", d.year).put("month", d.month).put("periodConfirmed", d.periodConfirmed)
        .put("createdAtMs", d.createdAtMs).put("rawFields", stringMap(d.rawFields))
        .put("proposedValues", numberMap(d.proposedValues)).put("proposedEvidence", stringMap(d.proposedEvidence))
        .put("originalRecord", d.originalRecord?.let { JSONArray(V2PayslipStore.encodeRecords(listOf(it))!!).getJSONObject(0) } ?: JSONObject.NULL)
        .put("originalValues", d.originalValues?.let(::numberMap) ?: JSONObject.NULL)
        .put("originalEvidence", d.originalEvidence?.let(::stringMap) ?: JSONObject.NULL)

    private fun decodeDraft(o: JSONObject, lease: SalaryAccountScope.Lease): Draft {
        require(o.get("schema") == 1 && o.getString("owner") == lease.scope)
        val original = if (o.isNull("originalRecord")) null else {
            val result = V2PayslipStore.decodeRecords(JSONArray().put(o.getJSONObject("originalRecord")).toString())
            require(result.reliable)
            result.records.single()
        }
        fun integer(key: String): Int {
            val value = o.get(key) as? Number ?: error("Invalid integer")
            val number = value.toDouble()
            require(number.isFinite() && number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE)
            return number.toInt()
        }
        val created = o.get("createdAtMs") as? Number ?: error("Invalid timestamp")
        require(created.toDouble().isFinite() && created.toDouble() % 1.0 == 0.0 && created.toLong() >= 0L)
        return Draft(
            id = o.getString("id"), recordId = o.getString("recordId"), companyId = o.getString("companyId"),
            sourceUri = o.getString("uri"), sourceMime = if (o.isNull("mime")) null else o.getString("mime"),
            year = integer("year"), month = integer("month"), periodConfirmed = o.get("periodConfirmed") as Boolean,
            rawFields = parseStrings(o.getJSONObject("rawFields")), proposedValues = parseNumbers(o.getJSONObject("proposedValues")),
            proposedEvidence = parseStrings(o.getJSONObject("proposedEvidence")), originalRecord = original,
            createdAtMs = created.toLong(), originalValues = if (o.isNull("originalValues")) null else parseNumbers(o.getJSONObject("originalValues")),
            originalEvidence = if (o.isNull("originalEvidence")) null else parseStrings(o.getJSONObject("originalEvidence")), lease = lease
        )
    }

    private fun stringMap(map: Map<String, String>) = JSONObject().apply { map.toSortedMap().forEach { (k, v) -> put(k, v) } }
    private fun numberMap(map: Map<String, Double>) = JSONObject().apply { map.toSortedMap().forEach { (k, v) -> put(k, v) } }
    private fun parseStrings(o: JSONObject): Map<String, String> = o.keys().asSequence().associateWith { o.get(it) as String }
    private fun parseNumbers(o: JSONObject): Map<String, Double> = o.keys().asSequence().associateWith {
        (o.get(it) as Number).toDouble().also { n -> require(n.isFinite() && n >= 0.0) }
    }
}
