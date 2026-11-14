package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.TimeBasisV2
import com.amaury.pointage.v2.model.WorkSegmentKindV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * Barrière de fiabilité du journal factuel des sessions V2.
 *
 * Ce composant ne calcule aucune durée et ne répare aucune donnée métier. Il vérifie uniquement
 * qu'un historique déjà présent peut être relu sans perte avant qu'un écrivain ne le modifie.
 */
object V2RuntimeHistoryGuardV2 {
    const val PREFS = "horatrack_v2_test_runtime"
    const val KEY_HISTORY = "history"
    const val KEY_PAUSES = "pauses"

    private const val HISTORY_WARNING =
        "Historique de pointage V2 illisible ou incohérent : aucune modification destructive n'est autorisée."

    data class ReadResult(
        val history: JSONArray,
        val reliable: Boolean,
        val warnings: List<String>,
        val issue: HistoryIssue? = null
    )
    data class HistoryIssue(val row: Int, val field: String?,
        val code: LegacyJournalDiagnosticV2.Code, val otherRow: Int? = null,
        val pause: Int? = null, val segment: Int? = null, val otherPause: Int? = null)

    data class SourceState(
        val reliable: Boolean,
        val warnings: List<String>,
        val accountScope: String? = null,
        val accountLease: SalaryAccountScope.Lease? = null
    )

    // Source reliability belongs to the caller's read, never to another worker's last read.
    private val lastSourceState = ThreadLocal<SourceState>()

    fun read(
        context: Context,
        allowLegacyMissingIds: Boolean = false
    ): ReadResult {
        val context = SalaryAccountScope.bindContext(context)
        if (!V2RuntimeAccountScope.available(context)) {
            val warning = V2RuntimeAccountScope.AUTH_WARNING
            publishSourceState(false, listOf(warning))
            return ReadResult(JSONArray(), false, listOf(warning))
        }
        val prefs = V2RuntimeAccountScope.preferences(context, PREFS)
        val result = if (!prefs.contains(KEY_HISTORY)) {
            ReadResult(JSONArray(), true, emptyList())
        } else {
            val raw = runCatching { prefs.getString(KEY_HISTORY, null) }.getOrNull()
            if (raw == null) corrupt() else decode(raw, allowLegacyMissingIds)
        }
        val guarded = if (V2RuntimeAccountScope.available(context)) result
            else ReadResult(JSONArray(), false, listOf(V2RestoreTransaction.WARNING))
        publishSourceState(guarded.reliable, guarded.warnings)
        return guarded
    }

    internal fun sourceState(): SourceState {
        val state = lastSourceState.get() ?: return SourceState(false, listOf(V2RuntimeAccountScope.AUTH_WARNING))
        return if (V2RuntimeAccountScope.available() && state.accountScope == V2RuntimeAccountScope.currentScope() &&
            state.accountLease?.let(SalaryAccountScope::isCurrent) == true) state
        else SourceState(false, listOf(V2RuntimeAccountScope.AUTH_WARNING))
    }

    internal fun publishSourceState(reliable: Boolean, warnings: List<String> = emptyList()) {
        lastSourceState.set(SourceState(
            reliable = reliable,
            warnings = warnings.distinct(),
            accountScope = V2RuntimeAccountScope.operationScope(),
            accountLease = V2RuntimeAccountScope.pinnedLease.get() ?: SalaryAccountScope.capture()
        ))
    }

    internal fun decode(
        raw: String,
        allowLegacyMissingIds: Boolean = false
    ): ReadResult {
        if (raw.isBlank()) return corrupt()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return corrupt()
        return inspect(array, allowLegacyMissingIds)
    }

    internal fun inspect(
        array: JSONArray,
        allowLegacyMissingIds: Boolean = false
    ): ReadResult {
        var malformed = false
        var issue: HistoryIssue? = null
        var rowNumber = 0
        var field: String? = null
        var pauseNumber: Int? = null
        var segmentNumber: Int? = null
        fun reject(code: LegacyJournalDiagnosticV2.Code = LegacyJournalDiagnosticV2.Code.INVALID_VALUE,
                   otherRow: Int? = null, otherPause: Int? = null) {
            malformed = true
            if (issue == null) issue = HistoryIssue(rowNumber, field, code, otherRow, pauseNumber, segmentNumber, otherPause)
        }
        val identities = mutableSetOf<String>()
        val identityRows = mutableMapOf<String, Int>()
        val sessionRanges = mutableListOf<Triple<Long, Long?, Int>>()

        for (index in 0 until array.length()) {
            rowNumber = index + 1
            pauseNumber = null
            segmentNumber = null
            field = "history"
            val item = array.optJSONObject(index)
            if (item == null) {
                reject()
                continue
            }
            field = "id"
            val id = optionalString(item, "id")
            if (id == null && !allowLegacyMissingIds) reject()
            if (id != null && !identities.add(id)) reject(otherRow = identityRows[id])
            if (id != null) identityRows.putIfAbsent(id, rowNumber)

            field = "realEntry"
            val realEntry = positiveLong(item, "realEntry")
            if (realEntry == null) reject()
            field = "realExit"
            val realExit = nullablePositiveLong(item, "realExit")
            if (!realExit.valid || (realEntry != null && realExit.value != null && realExit.value <= realEntry)) {
                reject()
            } else if (realEntry != null) {
                sessionRanges += Triple(realEntry, realExit.value, rowNumber)
            }

            field = "countedEntry"
            val countedEntry = nullablePositiveLong(item, "countedEntry")
            val countedExit = nullablePositiveLong(item, "countedExit")
            if (!countedEntry.valid) reject()
            field = "countedExit"
            if (!countedExit.valid) reject()
            if (countedEntry.value != null && countedExit.value != null && countedExit.value <= countedEntry.value) {
                reject()
            }

            field = "companySlot"
            if (item.has("companySlot") && !item.isNull("companySlot")) {
                val slot = strictInt(item.opt("companySlot"))
                if (slot == null || slot !in 1..2) reject()
            }
            field = "employerId"
            if (item.has("employerId") && !item.isNull("employerId") && optionalString(item, "employerId") == null) {
                reject()
            }
            field = "placeId"
            if (item.has("placeId") && !item.isNull("placeId") && optionalString(item, "placeId") == null) {
                reject()
            }
            field = "placeLabel"
            if (item.has("placeLabel") && !item.isNull("placeLabel") && optionalString(item, "placeLabel") == null) {
                reject()
            }
            field = "legacyFixedUnpaidPauseMs"
            if (item.has("legacyFixedUnpaidPauseMs") && !item.isNull("legacyFixedUnpaidPauseMs")) {
                val value = strictLong(item.opt("legacyFixedUnpaidPauseMs"))
                if (value == null || value < 0L) reject()
            }

            field = "timeBasis"
            if (item.has("timeBasis")) {
                val basis = optionalString(item, "timeBasis")
                if (basis == null || runCatching { TimeBasisV2.valueOf(basis) }.isFailure) reject()
            }
            field = "workSegments"
            if (item.has("workSegments")) {
                val segments = item.optJSONArray("workSegments")
                if (segments == null) {
                    reject()
                } else {
                    for (segmentIndex in 0 until segments.length()) {
                        segmentNumber = segmentIndex + 1
                        field = "workSegments"
                        val segment = segments.optJSONObject(segmentIndex)
                        if (segment == null) {
                            reject()
                            continue
                        }
                        val start = positiveLong(segment, "start")
                        val end = positiveLong(segment, "end")
                        val kind = optionalString(segment, "kind")
                        val status = optionalString(segment, "status")
                        field = "start"
                        if (start == null || (realEntry != null && start < realEntry)) reject()
                        field = "end"
                        if (end == null || (start != null && end <= start) ||
                            (realExit.value != null && end > realExit.value)) reject()
                        field = "kind"
                        if (kind == null || runCatching { WorkSegmentKindV2.valueOf(kind) }.isFailure) reject()
                        field = "status"
                        if (status == null || runCatching { DecisionStatusV2.valueOf(status) }.isFailure) reject()
                    }
                }
            }

            field = "pauses"
            segmentNumber = null
            val pauses = item.optJSONArray(KEY_PAUSES)
            val pauseProblem = pauses?.let(::pauseIssue)
            if (pauses == null) {
                reject()
            } else if (pauseProblem != null) {
                pauseNumber = pauseProblem.index
                field = pauseProblem.field
                reject(pauseProblem.code, pauseProblem.otherIndex?.let { rowNumber }, pauseProblem.otherIndex)
            } else if (realEntry != null) {
                for (pauseIndex in 0 until pauses.length()) {
                    pauseNumber = pauseIndex + 1
                    val pause = pauses.optJSONObject(pauseIndex)
                    val pauseStart = pause?.let { positiveLong(it, "start") }
                    val pauseEnd = pause?.let { positiveLong(it, "end") }
                    field = "start"
                    if (pauseStart == null || pauseStart < realEntry) reject(LegacyJournalDiagnosticV2.Code.PAUSE_INVALID)
                    field = "end"
                    if (pauseEnd == null || (realExit.value != null && pauseEnd > realExit.value))
                        reject(LegacyJournalDiagnosticV2.Code.PAUSE_INVALID)
                }
            }
        }

        // Deux sessions réelles ne peuvent pas occuper le même instant. Le contrôle est global,
        // indépendamment de l'employeur, comme sur iOS. Les sessions contiguës restent valides.
        val sortedRanges = sessionRanges.sortedBy { it.first }
        var furthestEnd = Long.MIN_VALUE
        var furthestRow: Int? = null
        pauseNumber = null
        segmentNumber = null
        for ((start, rawEnd, originalRow) in sortedRanges) {
            rowNumber = originalRow
            field = "realEntry"
            if (start < furthestEnd) reject(LegacyJournalDiagnosticV2.Code.OVERLAP, furthestRow)
            val end = rawEnd ?: Long.MAX_VALUE
            if (end > furthestEnd) { furthestEnd = end; furthestRow = originalRow }
        }

        return ReadResult(
            history = array,
            reliable = !malformed,
            warnings = if (malformed) listOf(HISTORY_WARNING) else emptyList(),
            issue = issue
        )
    }

    fun save(context: Context, history: JSONArray): Boolean {
        val context = SalaryAccountScope.bindContext(context)
        if (!V2RuntimeAccountScope.available(context)) return false
        val inspected = inspect(history)
        if (!inspected.reliable) {
            publishSourceState(false, inspected.warnings)
            return false
        }
        val saved = runCatching {
            V2RuntimeAccountScope.preferences(context, PREFS)
                .edit()
                .putString(KEY_HISTORY, history.toString())
                .commit()
        }.getOrDefault(false)
        if (saved) {
            publishSourceState(true)
        } else {
            publishSourceState(
                false,
                listOf("Historique de pointage V2 : sauvegarde impossible ; le calcul de paie doit rester à confirmer.")
            )
        }
        return saved
    }

    internal fun validPauseArray(array: JSONArray): Boolean = pauseIssue(array) == null

    private data class PauseIssue(val index: Int, val field: String,
        val code: LegacyJournalDiagnosticV2.Code = LegacyJournalDiagnosticV2.Code.PAUSE_INVALID,
        val otherIndex: Int? = null)

    private fun pauseIssue(array: JSONArray): PauseIssue? {
        val identities = mutableMapOf<String, Int>()
        val ranges = mutableListOf<Triple<Long, Long, Int>>()
        for (index in 0 until array.length()) {
            val ordinal = index + 1
            val item = array.optJSONObject(index) ?: return PauseIssue(ordinal, "pauses")
            val start = positiveLong(item, "start") ?: return PauseIssue(ordinal, "start")
            val end = positiveLong(item, "end") ?: return PauseIssue(ordinal, "end")
            if (end <= start) return PauseIssue(ordinal, "end")

            if (!item.has("source") || item.isNull("source")) return PauseIssue(ordinal, "source")
            val source = optionalString(item, "source") ?: return PauseIssue(ordinal, "source")
            if (runCatching { EventSourceV2.valueOf(source) }.isFailure) return PauseIssue(ordinal, "source")

            if (!item.has("paid") || item.opt("paid") !is Boolean)
                return PauseIssue(ordinal, "paid", LegacyJournalDiagnosticV2.Code.PAUSE_UNQUALIFIED)
            val identity = "$start:$end:$source:${item.optBoolean("paid")}" 
            if (identity in identities) return PauseIssue(ordinal, "pauses", otherIndex = identities[identity])
            identities[identity] = ordinal
            ranges += Triple(start, end, ordinal)
        }
        val sorted = ranges.sortedBy { it.first }
        for (index in 1 until sorted.size) {
            if (sorted[index].first < sorted[index - 1].second)
                return PauseIssue(sorted[index].third, "start", LegacyJournalDiagnosticV2.Code.OVERLAP, sorted[index - 1].third)
        }
        return null
    }

    private data class NullableLong(val valid: Boolean, val value: Long?)

    private fun nullablePositiveLong(item: JSONObject, key: String): NullableLong {
        if (!item.has(key) || item.isNull(key)) return NullableLong(true, null)
        val value = strictLong(item.opt(key)) ?: return NullableLong(false, null)
        return NullableLong(value > 0L, value.takeIf { it > 0L })
    }

    private fun positiveLong(item: JSONObject, key: String): Long? =
        strictLong(item.opt(key))?.takeIf { it > 0L }

    private fun strictLong(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            number.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        }
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun strictInt(value: Any?): Int? {
        val long = strictLong(value) ?: return null
        return long.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun optionalString(item: JSONObject, key: String): String? =
        (item.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() && it != "null" }

    private fun corrupt() = ReadResult(JSONArray(), false, listOf(HISTORY_WARNING))
}
