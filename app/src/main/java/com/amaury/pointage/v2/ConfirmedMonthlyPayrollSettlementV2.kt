package com.amaury.pointage.v2

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.PayrollMoneyV2
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal
import java.time.YearMonth

/** Confirmed facts for one company/month. Never a payroll rule or an IJSS transfer. */
object ConfirmedMonthlyPayrollSettlementV2 {
    const val PREFS = "salary_confirmed_payroll_settlement_v2"
    const val WARNING = "Frais et versement mensuels : source locale à vérifier ; aucun montant confirmé utilisable."
    const val ADJUSTMENTS_CONFIRMATION = "Je confirme tous les ajustements restant à appliquer après le net employeur après PAS : hors brut, hors cotisations et PAS, non déjà inclus dans ce net. Les autres ajouts comprennent tous les paniers et frais restant à verser. La retenue transport salariale et les avantages en nature déjà déduits ne doivent pas être déduits de nouveau. Chaque zéro est confirmé explicitement."
    data class Record(
        val companyId: String, val period: String,
        val transportReimbursement: Double? = null, val garnishment: Double? = null,
        val otherAddition: Double? = null, val otherDeduction: Double? = null,
        val netSocialObserved: Double? = null, val netPaidObserved: Double? = null,
        val source: String, val confirmedAt: Long, val adjustmentsComplete: Boolean = false
    ) {
        fun amounts() = listOf(transportReimbursement, garnishment, otherAddition, otherDeduction, netSocialObserved, netPaidObserved)
        fun adjustments() = amounts().take(4)
    }
    data class Result(val record: Record?, val reliable: Boolean, val warnings: List<String> = emptyList(), val revisionToken: String? = null)
    private val amountNames = listOf("transportReimbursement", "garnishment", "otherAddition", "otherDeduction", "netSocialObserved", "netPaidObserved")
    private val keys = (amountNames + listOf("version", "companyId", "period", "source", "confirmedAt", "adjustmentsComplete")).toSet()

    fun validSelection(companyId: String, period: String): Boolean = companyId.isNotBlank() && companyId.length <= 200 &&
        companyId.none { it == '|' || it.isISOControl() } && Regex("[0-9]{4}-[0-9]{2}").matches(period) &&
        runCatching { YearMonth.parse(period).year in 1..9999 }.getOrDefault(false)

    /** No rounding of confirmed facts: original decimal token must represent an exact cent. */
    fun parseAmount(raw: String): Double? = runCatching {
        require(raw.isNotEmpty() && raw.length <= 32)
        val decimal = BigDecimal(raw)
        require(decimal.signum() >= 0 && decimal.scale() in -400..400)
        val cents = decimal.movePointRight(2).longValueExact()
        val amount = requireNotNull(PayrollMoneyV2.amount(cents))
        require(PayrollMoneyV2.cents(amount) == cents)
        amount
    }.getOrNull()

    fun valid(record: Record): Boolean = validSelection(record.companyId, record.period) &&
        record.source.isNotBlank() && record.source.length <= 500 && record.confirmedAt > 0 &&
        record.amounts().any { it != null } && record.amounts().filterNotNull().all {
            it.isFinite() && it >= 0.0 && parseAmount(it.toString())?.toBits() == it.toBits()
        } && (!record.adjustmentsComplete || record.adjustments().all { it != null })

    internal fun encode(record: Record): String {
        require(valid(record))
        return JSONObject().put("version", 1).put("companyId", record.companyId).put("period", record.period)
            .put("source", record.source).put("confirmedAt", record.confirmedAt)
            .put("adjustmentsComplete", record.adjustmentsComplete).apply {
                amountNames.zip(record.amounts()).forEach { (key, amount) -> put(key, amount ?: JSONObject.NULL) }
            }.toString()
    }

    /** Decode original tokens directly: org.json must not round large decimal amounts first. */
    internal fun decode(raw: String?, companyId: String, period: String): Result = runCatching {
        require(raw != null && raw.length <= 8_192 && validSelection(companyId, period))
        val values = mutableMapOf<String, Any?>()
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(name in keys && !values.containsKey(name))
                values[name] = when (name) {
                    in amountNames -> if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else {
                        require(reader.peek() == JsonToken.NUMBER)
                        requireNotNull(parseAmount(reader.nextString()))
                    }
                    "version", "confirmedAt" -> {
                        require(reader.peek() == JsonToken.NUMBER)
                        val token = reader.nextString()
                        require(token.length <= 19 && Regex("[0-9]+").matches(token))
                        token.toLong()
                    }
                    "adjustmentsComplete" -> { require(reader.peek() == JsonToken.BOOLEAN); reader.nextBoolean() }
                    else -> { require(reader.peek() == JsonToken.STRING); reader.nextString() }
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        require(values.keys == keys && values["version"] == 1L)
        fun amount(key: String) = values[key] as Double?
        val record = Record(values["companyId"] as String, values["period"] as String,
            amount("transportReimbursement"), amount("garnishment"), amount("otherAddition"), amount("otherDeduction"),
            amount("netSocialObserved"), amount("netPaidObserved"), values["source"] as String,
            values["confirmedAt"] as Long, values["adjustmentsComplete"] as Boolean)
        require(valid(record) && record.companyId == companyId && record.period == period)
        Result(record, true)
    }.getOrElse { Result(null, false, listOf(WARNING)) }

    /** Typed, injective encoding before hashing; never use a collection's ambiguous toString(). */
    internal fun revisionToken(stored: Any?): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val sink = object : java.io.OutputStream() { override fun write(value: Int) = Unit }
        var remainingCharacters = 262_144
        java.io.DataOutputStream(java.security.DigestOutputStream(sink, digest)).use { output ->
            fun string(value: String) {
                require(value.length <= remainingCharacters)
                remainingCharacters -= value.length
                output.writeInt(value.length)
                // Preserve every Java UTF-16 code unit, including malformed surrogate sequences.
                value.forEach { output.writeChar(it.code) }
            }
            when (stored) {
                null -> output.writeByte(0)
                is String -> { output.writeByte(1); string(stored) }
                is Boolean -> { output.writeByte(2); output.writeBoolean(stored) }
                is Int -> { output.writeByte(3); output.writeInt(stored) }
                is Long -> { output.writeByte(4); output.writeLong(stored) }
                is Float -> { output.writeByte(5); output.writeInt(java.lang.Float.floatToRawIntBits(stored)) }
                is Set<*> -> {
                    require(stored.size <= 4_096 && stored.all { it is String })
                    output.writeByte(6)
                    output.writeInt(stored.size)
                    stored.map { it as String }.sorted().forEach(::string)
                }
                else -> error("Unsupported preference value")
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrNull()

    private fun companyAvailable(context: Context, companyId: String): Boolean = SalaryAccountScope.available(context) &&
        SalaryCompanyStore.confirmedCompany(SalaryCompanyStore.readConfirmed(context), companyId) != null && SalaryAccountScope.available(context)

    @Synchronized
    fun read(context: Context, companyId: String, period: String): Result {
        val bound = SalaryAccountScope.bindContext(context)
        fun unavailable() = Result(null, false, listOf(WARNING))
        if (!validSelection(companyId, period) || !companyAvailable(bound, companyId)) return unavailable()
        val prefs = SalaryAccountScope.preferences(bound, PREFS)
        val key = "$companyId|$period"
        val snapshot = prefs.all
        val result = if (!snapshot.containsKey(key)) Result(null, true) else {
            val stored = snapshot[key]
            val token = revisionToken(stored)
            decode(stored as? String, companyId, period).copy(revisionToken = token)
        }
        return if (companyAvailable(bound, companyId)) result else unavailable()
    }

    @Synchronized
    fun save(context: Context, record: Record, confirmed: Boolean, expected: Result? = null): Boolean = synchronized(SalaryCompanyStore) {
        val bound = SalaryAccountScope.bindContext(context)
        val scope = SalaryAccountScope.lease(bound).scope ?: return false
        // Same monitor as restore publication: compare, write and readback are one guarded operation.
        V2RestoreTransaction.withAccess(bound, scope, { false }) {
            if (!confirmed || !valid(record) || !companyAvailable(bound, record.companyId)) return@withAccess false
            val previous = read(bound, record.companyId, record.period)
            if (!previous.reliable || (expected != null && expected != previous)) return@withAccess false
            val raw = encode(record)
            if (!companyAvailable(bound, record.companyId)) return@withAccess false
            SalaryAccountScope.preferences(bound, PREFS).edit().putString("${record.companyId}|${record.period}", raw).commit() &&
                read(bound, record.companyId, record.period).let { it.reliable && it.record == record }
        }
    }

    @Synchronized
    fun remove(context: Context, companyId: String, period: String, expected: Result? = null): Boolean = synchronized(SalaryCompanyStore) {
        val bound = SalaryAccountScope.bindContext(context)
        val scope = SalaryAccountScope.lease(bound).scope ?: return false
        V2RestoreTransaction.withAccess(bound, scope, { false }) {
            if (!validSelection(companyId, period) || !companyAvailable(bound, companyId)) return@withAccess false
            val current = read(bound, companyId, period)
            // An unreadable value without a bounded fingerprint cannot be safely confirmed for deletion.
            if (!current.reliable && current.revisionToken == null) return@withAccess false
            if (expected != null && current != expected) return@withAccess false
            SalaryAccountScope.preferences(bound, PREFS).edit().remove("$companyId|$period").commit() &&
                read(bound, companyId, period).let { it.reliable && it.record == null }
        }
    }
}
