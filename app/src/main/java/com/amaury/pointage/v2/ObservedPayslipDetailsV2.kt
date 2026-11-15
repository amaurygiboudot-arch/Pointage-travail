package com.amaury.pointage.v2

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.amaury.pointage.SalaryCompanyStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal
import java.time.YearMonth

/** A transcription attached to one original bulletin. Never an input to a payroll calculation. */
object ObservedPayslipDetailsV2 {
    internal const val PREFIX = "observed_detail_v1_"
    const val MAX_ROWS = 150
    private const val MAX_JSON = 200_000
    data class Row(val label: String, val base: String? = null, val employeeRate: String? = null,
                   val employeeAmount: String? = null, val employerRate: String? = null, val employerAmount: String? = null, val employerBase: String? = null) {
        fun cells() = listOf(base, employeeRate, employeeAmount, employerBase, employerRate, employerAmount)
    }
    data class Document(val sourcePeriod: String, val source: String, val confirmedAt: Long, val rows: List<Row>)
    data class Snapshot internal constructor(val record: V2PayslipStore.Record?, val aggregates: Map<String, Double>,
        val document: Document?, val reliable: Boolean, val needsReconfirmation: Boolean = false,
        internal val raw: String? = null, internal val binding: String? = null, internal val lease: SalaryAccountScope.Lease? = null)

    /** Preserve precision without binary floating point; zero is not an absent cell. */
    fun decimal(text: String, scale: Int): String? = runCatching {
        require(text.length <= 40 && Regex("^-?[0-9]+(?:[.,][0-9]+)?$").matches(text))
        val n = BigDecimal(text.replace(',', '.'))
        require(n.stripTrailingZeros().scale() <= scale && n.abs() <= BigDecimal("1000000000"))
        n.stripTrailingZeros().toPlainString()
    }.getOrNull()
    fun period(record: V2PayslipStore.Record): String = YearMonth.of(record.year, record.month + 1).toString()
    fun valid(document: Document, record: V2PayslipStore.Record): Boolean =
        document.sourcePeriod == period(record) && document.confirmedAt > 0 && text(document.source, 500) &&
        document.rows.size in 1..MAX_ROWS && document.rows.all { row ->
            text(row.label, 160) && row.cells().any { it != null } && row.cells().mapIndexed { index, value ->
                value == null || decimal(value, if (index == 2 || index == 5) 2 else 4) == value
            }.all { it }
        }
    private fun text(s: String, max: Int) = s.isNotBlank() && s.length <= max && s == s.trim() && s.none { it.isISOControl() }
    private fun binding(record: V2PayslipStore.Record, aggregates: Map<String, Double>, evidence: Map<String, String>): String = JSONObject().apply {
        put("record", JSONArray(listOf(record.id, record.companyId, record.year, record.month, record.sourceUri,
            record.sourceMime, record.gross, record.net, record.extractionConfidence, record.confirmedByUser, record.importedAtMs)))
        put("evidence", JSONArray(evidence.toSortedMap().map { JSONArray(listOf(it.key, it.value)) }))
        put("aggregates", JSONArray(aggregates.toSortedMap().map { JSONArray(listOf(it.key, it.value.toString())) }))
    }.toString()
    private fun currentCompany(context: Context, id: String) = SalaryCompanyStore.confirmedCompany(SalaryCompanyStore.readConfirmed(context), id) != null

    fun read(context: Context, recordId: String): Snapshot = synchronized(SalaryCompanyStore) {
        val bound = SalaryAccountScope.bindContext(context)
        PayslipImportStoreV2.withStorageAccess(bound, { Snapshot(null, emptyMap(), null, false) }) {
            val records = V2PayslipStore.readResult(bound)
            val record = records.records.singleOrNull { it.id == recordId }
            val values = PayslipObservedValuesStoreV2.readResult(bound)
            if (!records.reliable || record == null || !values.reliable || !currentCompany(bound, record.companyId))
                return@withStorageAccess Snapshot(record, emptyMap(), null, false)
            val aggregates = values.valuesByRecord[record.id].orEmpty()
            val bind = binding(record, aggregates, PayslipObservedValuesStoreV2.evidence(bound, record.id))
            val lease = SalaryAccountScope.lease(bound)
            val prefs = SalaryAccountScope.preferences(bound, V2PayslipStore.PREFS)
            val key = PREFIX + record.id
            if (!prefs.contains(key)) return@withStorageAccess Snapshot(record, aggregates, null, true, binding = bind, lease = lease)
            val raw = runCatching { prefs.getString(key, null) }.getOrNull()
            val decoded = decode(raw, record)
            Snapshot(record, aggregates, decoded?.second, decoded != null,
                decoded != null && (decoded.first != bind || decoded.second.sourcePeriod != period(record)), raw, bind, lease)
        }
    }
    fun save(context: Context, expected: Snapshot, document: Document, confirmed: Boolean): Boolean = synchronized(SalaryCompanyStore) {
        val bound = SalaryAccountScope.bindContext(context)
        val record = expected.record ?: return false
        if (expected.lease == null || !SalaryAccountScope.isCurrent(expected.lease) || SalaryAccountScope.lease(bound) != expected.lease) return false
        PayslipImportStoreV2.withStorageAccess(bound, { false }) {
            if (!confirmed || !expected.reliable || !valid(document, record) || !currentCompany(bound, record.companyId)) return@withStorageAccess false
            val now = read(bound, record.id)
            if (now != expected || now.binding == null) return@withStorageAccess false
            val raw = encode(now.binding, document)
            if (raw.length > MAX_JSON) return@withStorageAccess false
            SalaryAccountScope.preferences(bound, V2PayslipStore.PREFS).edit().putString(PREFIX + record.id, raw).commit() &&
                read(bound, record.id).let { it.reliable && !it.needsReconfirmation && it.document == document }
        }
    }
    fun remove(context: Context, expected: Snapshot): Boolean = synchronized(SalaryCompanyStore) {
        val bound = SalaryAccountScope.bindContext(context)
        val record = expected.record ?: return false
        if (expected.lease == null || !SalaryAccountScope.isCurrent(expected.lease) || SalaryAccountScope.lease(bound) != expected.lease) return false
        PayslipImportStoreV2.withStorageAccess(bound, { false }) {
            if (!expected.reliable || read(bound, record.id) != expected || !currentCompany(bound, record.companyId)) return@withStorageAccess false
            SalaryAccountScope.preferences(bound, V2PayslipStore.PREFS).edit().remove(PREFIX + record.id).commit() &&
                read(bound, record.id).let { it.reliable && it.document == null }
        }
    }
    internal fun encode(binding: String, document: Document): String = JSONObject().apply {
        put("schema", 1); put("binding", binding); put("sourcePeriod", document.sourcePeriod)
        put("source", document.source); put("confirmedAt", document.confirmedAt.toString())
        put("rows", JSONArray(document.rows.map { row -> JSONObject().apply {
            put("label", row.label); row.cells().zip(listOf("base", "employeeRate", "employeeAmount", "employerBase", "employerRate", "employerAmount")).forEach { (v,k) -> put(k, v ?: JSONObject.NULL) }
        } }))
    }.toString()
    internal fun decode(raw: String?, record: V2PayslipStore.Record): Pair<String, Document>? = runCatching {
        require(raw != null && raw.length <= MAX_JSON)
        // Reject duplicate properties before JSONObject's last-value-wins decoding.
        JsonReader(StringReader(raw)).use { reader -> strictValue(reader, 0); require(reader.peek() == JsonToken.END_DOCUMENT) }
        val obj = JSONObject(raw)
        require(obj.keySet() == setOf("schema", "binding", "sourcePeriod", "source", "confirmedAt", "rows") && obj.get("schema") == 1)
        fun str(o: JSONObject, key: String): String = o.get(key) as? String ?: error("String required")
        val array = obj.getJSONArray("rows"); require(array.length() in 1..MAX_ROWS)
        val rows = (0 until array.length()).map { index ->
            val r = array.getJSONObject(index)
            val keys = listOf("base", "employeeRate", "employeeAmount", "employerBase", "employerRate", "employerAmount")
            require(r.keySet() == (keys + "label").toSet())
            val c = keys.map { if (r.isNull(it)) null else str(r, it) }
            Row(str(r, "label"), c[0], c[1], c[2], c[4], c[5], c[3])
        }
        val doc = Document(str(obj,"sourcePeriod"),str(obj,"source"),str(obj,"confirmedAt").toLong(),rows)
        val originalPeriod = YearMonth.parse(doc.sourcePeriod)
        require(originalPeriod.year in 2000..2200 && originalPeriod.toString() == doc.sourcePeriod && valid(doc, record.copy(year=originalPeriod.year, month=originalPeriod.monthValue-1)))
        str(obj,"binding") to doc
    }.getOrNull()
    private fun JSONObject.keySet() = keys().asSequence().toSet()
    private fun strictValue(reader: JsonReader, depth: Int) {
        require(depth < 6)
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> { reader.beginObject(); val keys = mutableSetOf<String>(); while(reader.hasNext()) { require(keys.add(reader.nextName())); strictValue(reader, depth+1) }; reader.endObject() }
            JsonToken.BEGIN_ARRAY -> { reader.beginArray(); var size=0; while(reader.hasNext()) { require(++size <= MAX_ROWS); strictValue(reader,depth+1) }; reader.endArray() }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.NULL -> reader.nextNull()
            else -> error("Unexpected JSON value")
        }
    }
}
