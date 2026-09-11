package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyBenefitInKindResolverV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** Stockage des avantages en nature valorisés, dans le fichier Salaire V2 de l'entreprise. */
object CompanyBenefitInKindStoreV2 {
    private const val KEY = "benefits_in_kind_v2"
    private const val COVERAGE_KEY = "benefits_in_kind_month_coverage_v2"
    private const val STORAGE_WARNING =
        "Avantages en nature : stockage local incohérent ; calcul automatique bloqué."
    private const val COVERAGE_STORAGE_WARNING =
        "Avantages en nature : stockage des confirmations mensuelles incohérent ; calcul automatique bloqué."

    data class ReadResult(
        val records: List<CompanyBenefitInKindResolverV2.Record>,
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

    internal fun companyUnavailableReadResult(): ReadResult = ReadResult(
        records = emptyList(),
        reliable = false,
        warnings = listOf("Avantages en nature : entreprise absente ou stockage des entreprises non fiable.")
    )

    internal fun companyUnavailableConfirmationResult(): ConfirmationReadResult = ConfirmationReadResult(
        confirmations = emptyList(),
        reliable = false,
        warnings = listOf("Avantages en nature : entreprise absente ou stockage des entreprises non fiable.")
    )

    fun read(context: Context, companyId: String): ReadResult {
        if (companyId.isBlank()) return companyUnavailableReadResult()
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            readRecordsConfirmed(context, companyId)
        } ?: companyUnavailableReadResult()
    }

    fun list(context: Context, companyId: String): List<CompanyBenefitInKindResolverV2.Record> =
        read(context, companyId).records

    fun monthCoverage(context: Context, companyId: String, period: YearMonth): MonthCoverageSnapshot {
        if (companyId.isBlank()) return unavailableCoverage()
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            monthCoverage(readConfirmationsConfirmed(context, companyId), period)
        } ?: unavailableCoverage()
    }

    private fun monthCoverage(stored: ConfirmationReadResult, period: YearMonth): MonthCoverageSnapshot {
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
                    "Avantages en nature $period : exhaustivité du mois à confirmer ; aucun zéro implicite n'est retenu."
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

    fun save(context: Context, companyId: String, record: CompanyBenefitInKindResolverV2.Record): Boolean {
        if (companyId.isBlank() || !validRecord(record)) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            val stored = readRecordsConfirmed(context, companyId)
            val confirmations = readConfirmationsConfirmed(context, companyId)
            if (!stored.reliable || !confirmations.reliable) return@withConfirmedCompany false
            val items = stored.records.toMutableList()
            val index = items.indexOfFirst { it.id == record.id }
            if (index >= 0) items[index] = record else items += record
            writeRecordsAndInvalidateConfirmationsConfirmed(context, companyId, items)
        } == true
    }

    fun remove(context: Context, companyId: String, id: String): Boolean {
        if (companyId.isBlank() || id.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            val stored = readRecordsConfirmed(context, companyId)
            val confirmations = readConfirmationsConfirmed(context, companyId)
            if (!stored.reliable || !confirmations.reliable) return@withConfirmedCompany false
            writeRecordsAndInvalidateConfirmationsConfirmed(
                context,
                companyId,
                stored.records.filterNot { it.id == id }
            )
        } == true
    }

    /**
     * Confirme que la liste enregistrée est exhaustive pour ce mois.
     * Une liste vide confirmée signifie explicitement 0 € ; sans confirmation, 0 reste inconnu.
     */
    fun confirmMonth(
        context: Context,
        companyId: String,
        period: YearMonth,
        source: String
    ): Boolean {
        if (companyId.isBlank() || source.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            val stored = readRecordsConfirmed(context, companyId)
            val confirmations = readConfirmationsConfirmed(context, companyId)
            if (!stored.reliable || !confirmations.reliable) return@withConfirmedCompany false
            val base = CompanyBenefitInKindResolverV2.resolve(stored.records, period)
            if (!base.reliable) return@withConfirmedCompany false

            val updated = confirmations.confirmations
                .filterNot { it.period == period }
                .toMutableList()
                .apply { add(MonthConfirmation(period, source.trim())) }
            writeConfirmationsConfirmed(context, companyId, updated)
        } == true
    }

    fun clearMonthConfirmation(context: Context, companyId: String, period: YearMonth): Boolean {
        if (companyId.isBlank()) return false
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            val stored = readConfirmationsConfirmed(context, companyId)
            if (!stored.reliable) return@withConfirmedCompany false
            writeConfirmationsConfirmed(
                context,
                companyId,
                stored.confirmations.filterNot { it.period == period }
            )
        } == true
    }

    fun resolve(
        context: Context,
        companyId: String,
        period: YearMonth
    ): CompanyBenefitInKindResolverV2.Snapshot {
        if (companyId.isBlank()) {
            return resolve(companyUnavailableReadResult(), companyUnavailableConfirmationResult(), period)
        }
        return SalaryCompanyStore.withConfirmedCompany(context, companyId) {
            resolve(
                records = readRecordsConfirmed(context, companyId),
                confirmations = readConfirmationsConfirmed(context, companyId),
                period = period
            )
        } ?: resolve(companyUnavailableReadResult(), companyUnavailableConfirmationResult(), period)
    }

    internal fun resolve(
        records: ReadResult,
        confirmations: ConfirmationReadResult,
        period: YearMonth
    ): CompanyBenefitInKindResolverV2.Snapshot {
        if (!records.reliable) {
            return CompanyBenefitInKindResolverV2.Snapshot(
                applied = emptyList(),
                totalGross = 0.0,
                reliable = false,
                warnings = records.warnings.ifEmpty { listOf(STORAGE_WARNING) }
            )
        }

        val base = CompanyBenefitInKindResolverV2.resolve(records.records, period)
        val matching = if (confirmations.reliable) {
            confirmations.confirmations.filter { it.period == period }
        } else emptyList()
        val coverageConfirmed = confirmations.reliable && matching.size == 1
        val coverageWarnings = buildList {
            if (!confirmations.reliable) {
                addAll(confirmations.warnings.ifEmpty { listOf(COVERAGE_STORAGE_WARNING) })
            } else when (matching.size) {
                0 -> add(
                    "Avantages en nature $period : liste mensuelle non confirmée exhaustive ; " +
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
        val records = mutableListOf<CompanyBenefitInKindResolverV2.Record>()
        var malformed = false
        for (i in 0 until array.length()) {
            val record = fromJson(array.opt(i) as? JSONObject)
            if (record == null) malformed = true else records += record
        }
        val duplicateIds = records.groupingBy { it.id }.eachCount().any { it.value > 1 }
        if (duplicateIds) malformed = true
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

    private fun readRecordsConfirmed(context: Context, companyId: String): ReadResult {
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]")
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeRecords(raw)
    }

    private fun readConfirmationsConfirmed(context: Context, companyId: String): ConfirmationReadResult {
        val raw = SalaryCompanyStore.prefs(context, companyId).getString(COVERAGE_KEY, "[]")
            ?: return ConfirmationReadResult(emptyList(), false, listOf(COVERAGE_STORAGE_WARNING))
        return decodeConfirmations(raw)
    }

    private fun writeRecordsAndInvalidateConfirmationsConfirmed(
        context: Context,
        companyId: String,
        items: List<CompanyBenefitInKindResolverV2.Record>
    ): Boolean {
        val recordsArray = JSONArray()
        items.forEach { recordsArray.put(toJson(it)) }
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, recordsArray.toString())
            // Toute modification de la liste peut changer plusieurs mois : aucune ancienne
            // confirmation d'exhaustivité n'est conservée silencieusement.
            .putString(COVERAGE_KEY, "[]")
            .commit()
    }

    private fun writeConfirmationsConfirmed(
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

    private fun unavailableCoverage() = MonthCoverageSnapshot(
        confirmed = false,
        source = null,
        storageReliable = false,
        warnings = companyUnavailableConfirmationResult().warnings
    )

    private fun toJson(record: CompanyBenefitInKindResolverV2.Record) = JSONObject()
        .put("id", record.id)
        .put("label", record.label)
        .put("grossValue", record.grossValue)
        .put("kind", record.kind.name)
        .put("effectiveFrom", record.effectiveFrom?.toString() ?: JSONObject.NULL)
        .put("effectiveTo", record.effectiveTo?.toString() ?: JSONObject.NULL)
        .put("paymentMonth", record.paymentMonth?.toString() ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject?): CompanyBenefitInKindResolverV2.Record? {
        o ?: return null
        val id = (o.opt("id") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val label = (o.opt("label") as? String) ?: return null
        val value = (o.opt("grossValue") as? Number)?.toDouble() ?: return null
        val kindRaw = o.opt("kind") as? String ?: return null
        val kind = runCatching { CompanyBenefitInKindResolverV2.Kind.valueOf(kindRaw) }.getOrNull()
            ?: return null

        val effectiveFrom = parseOptionalMonth(o, "effectiveFrom") ?: return null
        val effectiveTo = parseOptionalMonth(o, "effectiveTo") ?: return null
        val paymentMonth = parseOptionalMonth(o, "paymentMonth") ?: return null
        val record = CompanyBenefitInKindResolverV2.Record(
            id = id,
            label = label,
            grossValue = value,
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

    private fun validRecord(record: CompanyBenefitInKindResolverV2.Record): Boolean {
        if (record.id.isBlank() || record.label.isBlank()) return false
        if (!record.grossValue.isFinite() || record.grossValue <= 0.0) return false
        return when (record.kind) {
            CompanyBenefitInKindResolverV2.Kind.MONTHLY -> {
                val start = record.effectiveFrom ?: return false
                val end = record.effectiveTo
                record.paymentMonth == null && (end == null || end >= start)
            }
            CompanyBenefitInKindResolverV2.Kind.ONE_OFF ->
                record.paymentMonth != null && record.effectiveFrom == null && record.effectiveTo == null
        }
    }
}
