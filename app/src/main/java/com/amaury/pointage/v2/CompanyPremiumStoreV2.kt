package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyPremiumResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage local des primes contractuelles/personnelles, strictement séparé par entreprise. */
object CompanyPremiumStoreV2 {
    private const val KEY = "company_premiums_v2"
    private const val COVERAGE_KEY = "company_premiums_month_coverage_v2"
    private const val STORAGE_WARNING =
        "Primes contractuelles/personnelles : stockage local incohérent ; calcul du brut bloqué."
    private const val COVERAGE_STORAGE_WARNING =
        "Primes contractuelles/personnelles : stockage des confirmations mensuelles incohérent ; calcul du brut bloqué."

    data class ReadResult(
        val records: List<CompanyPremiumResolverV2.Record>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class MonthConfirmation(
        val period: YearMonth,
        val source: String
    )

    data class ConfirmationReadResult(
        val confirmations: List<MonthConfirmation>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    data class MonthCoverageSnapshot(
        val confirmed: Boolean,
        val source: String?,
        val storageReliable: Boolean,
        val warnings: List<String>
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) {
            return ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("Primes contractuelles/personnelles : entreprise non identifiée.")
            )
        }
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    fun list(context: Context, companyId: String): List<CompanyPremiumResolverV2.Record> =
        read(context, companyId).records

    fun monthCoverage(context: Context, companyId: String, period: YearMonth): MonthCoverageSnapshot {
        if (companyId.isBlank()) {
            return MonthCoverageSnapshot(
                confirmed = false,
                source = null,
                storageReliable = false,
                warnings = listOf("Primes contractuelles/personnelles : entreprise non identifiée.")
            )
        }
        val stored = readConfirmations(context, companyId)
        if (!stored.reliable) {
            return MonthCoverageSnapshot(false, null, false, stored.warnings)
        }
        val matching = stored.confirmations.filter { it.period == period }
        return when (matching.size) {
            0 -> MonthCoverageSnapshot(
                confirmed = false,
                source = null,
                storageReliable = true,
                warnings = listOf(
                    "Primes contractuelles/personnelles $period : exhaustivité du mois à confirmer ; aucun zéro implicite n'est retenu."
                )
            )
            1 -> MonthCoverageSnapshot(
                confirmed = true,
                source = matching.single().source,
                storageReliable = true,
                warnings = emptyList()
            )
            else -> MonthCoverageSnapshot(
                confirmed = false,
                source = null,
                storageReliable = false,
                warnings = listOf(COVERAGE_STORAGE_WARNING)
            )
        }
    }

    fun save(context: Context, companyId: String, record: CompanyPremiumResolverV2.Record): Boolean {
        if (companyId.isBlank() || !validRecord(record)) return false
        val stored = read(context, companyId)
        val confirmations = readConfirmations(context, companyId)
        if (!stored.reliable || !confirmations.reliable) return false
        val items = stored.records.toMutableList()
        val index = items.indexOfFirst { it.id == record.id }
        if (index >= 0) items[index] = record else items += record
        return writeRecordsAndInvalidateConfirmations(context, companyId, items)
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        if (companyId.isBlank() || id.isBlank()) return false
        val stored = read(context, companyId)
        val confirmations = readConfirmations(context, companyId)
        if (!stored.reliable || !confirmations.reliable) return false
        return writeRecordsAndInvalidateConfirmations(
            context,
            companyId,
            stored.records.filterNot { it.id == id }
        )
    }

    /**
     * Confirme que la liste des primes enregistrées est exhaustive pour ce mois.
     * Une liste vide confirmée signifie explicitement 0 € ; sans confirmation, 0 reste inconnu.
     */
    fun confirmMonth(
        context: Context,
        companyId: String,
        period: YearMonth,
        source: String
    ): Boolean {
        if (companyId.isBlank() || source.isBlank()) return false
        val stored = read(context, companyId)
        val confirmations = readConfirmations(context, companyId)
        if (!stored.reliable || !confirmations.reliable) return false
        val base = CompanyPremiumResolverV2.resolve(stored.records, period)
        if (!base.reliable) return false

        val updated = confirmations.confirmations
            .filterNot { it.period == period }
            .toMutableList()
            .apply { add(MonthConfirmation(period, source.trim())) }
        return writeConfirmations(context, companyId, updated)
    }

    fun clearMonthConfirmation(context: Context, companyId: String, period: YearMonth): Boolean {
        if (companyId.isBlank()) return false
        val stored = readConfirmations(context, companyId)
        if (!stored.reliable) return false
        return writeConfirmations(
            context,
            companyId,
            stored.confirmations.filterNot { it.period == period }
        )
    }

    fun resolve(
        context: Context,
        companyId: String,
        period: YearMonth
    ): CompanyPremiumResolverV2.Snapshot = resolve(
        records = read(context, companyId),
        confirmations = readConfirmations(context, companyId),
        period = period
    )

    internal fun resolve(
        records: ReadResult,
        confirmations: ConfirmationReadResult,
        period: YearMonth
    ): CompanyPremiumResolverV2.Snapshot {
        if (!records.reliable) {
            return CompanyPremiumResolverV2.Snapshot(
                applied = emptyList(),
                totalGross = 0.0,
                reliable = false,
                warnings = records.warnings.ifEmpty { listOf(STORAGE_WARNING) }
            )
        }

        val base = CompanyPremiumResolverV2.resolve(records.records, period)
        val matching = if (confirmations.reliable) {
            confirmations.confirmations.filter { it.period == period }
        } else emptyList()
        val coverageConfirmed = confirmations.reliable && matching.size == 1
        val coverageWarnings = buildList {
            if (!confirmations.reliable) {
                addAll(confirmations.warnings.ifEmpty { listOf(COVERAGE_STORAGE_WARNING) })
            } else when (matching.size) {
                0 -> add(
                    "Primes contractuelles/personnelles $period : liste mensuelle non confirmée exhaustive ; " +
                        "le total 0 € éventuel reste inconnu."
                )
                1 -> Unit
                else -> add(COVERAGE_STORAGE_WARNING)
            }
        }

        return base.copy(
            reliable = base.reliable && coverageConfirmed,
            warnings = (base.warnings + coverageWarnings).distinct()
        )
    }

    internal fun decodeRecords(raw: String): ReadResult = runCatching {
        val array = JSONArray(raw)
        val records = mutableListOf<CompanyPremiumResolverV2.Record>()
        var malformed = false
        for (i in 0 until array.length()) {
            val record = fromJson(array.opt(i) as? JSONObject)
            if (record == null) malformed = true else records += record
        }
        if (records.groupingBy { it.id }.eachCount().any { it.value > 1 }) malformed = true
        ReadResult(
            records = records,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }.getOrElse {
        ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
    }

    internal fun decodeConfirmations(raw: String): ConfirmationReadResult = runCatching {
        val array = JSONArray(raw)
        val confirmations = mutableListOf<MonthConfirmation>()
        var malformed = false
        for (i in 0 until array.length()) {
            val o = array.opt(i) as? JSONObject
            if (o == null) {
                malformed = true
                continue
            }
            val periodRaw = o.opt("period") as? String
            val source = (o.opt("source") as? String)?.trim()
            val period = periodRaw?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            if (period == null || source.isNullOrBlank()) {
                malformed = true
            } else {
                confirmations += MonthConfirmation(period, source)
            }
        }
        if (confirmations.groupingBy { it.period }.eachCount().any { it.value > 1 }) malformed = true
        ConfirmationReadResult(
            confirmations = confirmations,
            reliable = !malformed,
            warnings = if (malformed) listOf(COVERAGE_STORAGE_WARNING) else emptyList()
        )
    }.getOrElse {
        ConfirmationReadResult(emptyList(), false, listOf(COVERAGE_STORAGE_WARNING))
    }

    private fun readConfirmations(context: Context, companyId: String): ConfirmationReadResult {
        if (companyId.isBlank()) {
            return ConfirmationReadResult(
                emptyList(),
                false,
                listOf("Primes contractuelles/personnelles : entreprise non identifiée.")
            )
        }
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(COVERAGE_KEY, "[]")
            ?: return ConfirmationReadResult(emptyList(), false, listOf(COVERAGE_STORAGE_WARNING))
        return decodeConfirmations(raw)
    }

    private fun writeRecordsAndInvalidateConfirmations(
        context: Context,
        companyId: String,
        items: List<CompanyPremiumResolverV2.Record>
    ): Boolean {
        val recordsArray = JSONArray()
        items.forEach { recordsArray.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, recordsArray.toString())
            // Une prime peut concerner plusieurs mois : toute modification invalide les
            // anciennes confirmations d'exhaustivité au lieu de les conserver silencieusement.
            .putString(COVERAGE_KEY, "[]")
            .commit()
    }

    private fun writeConfirmations(
        context: Context,
        companyId: String,
        confirmations: List<MonthConfirmation>
    ): Boolean {
        val array = JSONArray()
        confirmations.sortedBy { it.period }.forEach { confirmation ->
            array.put(
                JSONObject()
                    .put("period", confirmation.period.toString())
                    .put("source", confirmation.source)
            )
        }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(COVERAGE_KEY, array.toString())
            .commit()
    }

    private fun toJson(record: CompanyPremiumResolverV2.Record) = JSONObject()
        .put("id", record.id)
        .put("label", record.label)
        .put("grossAmount", record.grossAmount)
        .put("kind", record.kind.name)
        .put("effectiveFrom", record.effectiveFrom?.toString() ?: JSONObject.NULL)
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("paymentMonth", record.paymentMonth?.toString() ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): CompanyPremiumResolverV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val label = (o.opt("label") as? String) ?: return null
        val amount = (o.opt("grossAmount") as? Number)?.toDouble() ?: return null
        val kindRaw = o.opt("kind") as? String ?: return null
        val kind = runCatching { CompanyPremiumResolverV2.Kind.valueOf(kindRaw) }.getOrNull()
            ?: return null

        val effectiveFrom = parseOptionalMonth(o, "effectiveFrom") ?: return null
        val effectiveTo = parseOptionalMonth(o, "effectiveTo") ?: return null
        val paymentMonth = parseOptionalMonth(o, "paymentMonth") ?: return null
        val record = CompanyPremiumResolverV2.Record(
            id = id,
            label = label,
            grossAmount = amount,
            kind = kind,
            effectiveFrom = effectiveFrom.value,
            effectiveTo = effectiveTo.value,
            paymentMonth = paymentMonth.value
        )
        return record.takeIf(::validRecord)
    }

    private data class OptionalMonth(val value: YearMonth?)

    private fun parseOptionalMonth(o: JSONObject, key: String): OptionalMonth? = when (val raw = o.opt(key)) {
        null, JSONObject.NULL -> OptionalMonth(null)
        is String -> {
            if (raw.isBlank() || raw == "null") OptionalMonth(null)
            else runCatching { OptionalMonth(YearMonth.parse(raw)) }.getOrNull()
        }
        else -> null
    }

    private fun validRecord(record: CompanyPremiumResolverV2.Record): Boolean {
        if (record.id.isBlank() || record.label.isBlank()) return false
        if (!record.grossAmount.isFinite() || record.grossAmount <= 0.0) return false
        return when (record.kind) {
            CompanyPremiumResolverV2.Kind.MONTHLY -> {
                val start = record.effectiveFrom ?: return false
                val end = record.effectiveTo
                record.paymentMonth == null && (end == null || end >= start)
            }
            CompanyPremiumResolverV2.Kind.ONE_OFF ->
                record.paymentMonth != null && record.effectiveFrom == null && record.effectiveTo == null
        }
    }
}
