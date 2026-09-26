package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.PayrollCoverageAttestationPolicyV2
import com.amaury.pointage.v2.engine.PayrollCoverageAttestationV2
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PayrollCoverageReadResultV2(
    val attestations: List<PayrollCoverageAttestationV2>,
    val reliable: Boolean,
    val warnings: List<String>
)

data class PayrollCoverageResolutionV2(
    val reliable: Boolean,
    val exhaustive: Boolean,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val sourceId: String,
    val warnings: List<String>
)

object V2PayrollCoverageStore {
    const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY_ITEMS = "confirmed_ranges"
    private const val SCHEMA_VERSION = 1

    fun saveConfirmed(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): PayrollCoverageAttestationV2? {
        val employer = employerId.trim()
        val zone = timeZoneId.trim()
        if (employer.isEmpty() || checkedAtMs <= 0L || checkedAtMs > nowMs ||
            !PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
                coveredEndEpochDay, checkedAtMs, zone
            )
        ) return null

        val sessions = V2RuntimeReader.allSessions(context, nowMs)
        if (!sessions.reliable) return null
        val store = read(context)
        if (!store.reliable) return null

        val fingerprint = PayrollCoverageAttestationPolicyV2.fingerprint(
            sessions = sessions.sessions,
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            timeZoneId = zone
        ) ?: return null

        val attestation = PayrollCoverageAttestationV2(
            id = UUID.randomUUID().toString(),
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = checkedAtMs,
            timeZoneId = zone,
            sessionFingerprint = fingerprint
        )

        val next = store.attestations.filterNot {
            it.employerId == employer &&
                it.coveredStartEpochDay == coveredStartEpochDay &&
                it.coveredEndEpochDay == coveredEndEpochDay &&
                it.timeZoneId == zone
        } + attestation
        return if (write(context, next)) attestation else null
    }

    fun read(context: Context): PayrollCoverageReadResultV2 {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ITEMS)) {
            return PayrollCoverageReadResultV2(emptyList(), true, emptyList())
        }
        val raw = runCatching { prefs.getString(KEY_ITEMS, null) }.getOrNull()
            ?: return corrupt()
        return decode(raw)
    }

    fun resolve(
        context: Context,
        sessionsRead: V2RuntimeReader.SessionsRead,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): PayrollCoverageResolutionV2 {
        if (!sessionsRead.reliable) {
            return PayrollCoverageResolutionV2(
                reliable = false,
                exhaustive = false,
                coveredStartEpochDay = requestedStartEpochDay,
                coveredEndEpochDay = requestedEndEpochDay,
                checkedAtMs = nowMs.coerceAtLeast(0L),
                sourceId = "",
                warnings = sessionsRead.warnings.ifEmpty {
                    listOf(V2RuntimeReader.UNRELIABLE_MESSAGE)
                }
            )
        }
        val stored = read(context)
        if (!stored.reliable) {
            return PayrollCoverageResolutionV2(
                reliable = false,
                exhaustive = false,
                coveredStartEpochDay = requestedStartEpochDay,
                coveredEndEpochDay = requestedEndEpochDay,
                checkedAtMs = nowMs.coerceAtLeast(0L),
                sourceId = "",
                warnings = stored.warnings
            )
        }
        return resolve(
            attestations = stored.attestations,
            sessions = sessionsRead.sessions,
            employerId = employerId,
            requestedStartEpochDay = requestedStartEpochDay,
            requestedEndEpochDay = requestedEndEpochDay,
            timeZoneId = timeZoneId,
            nowMs = nowMs
        )
    }

    internal fun resolve(
        attestations: List<PayrollCoverageAttestationV2>,
        sessions: List<WorkSessionV2>,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long
    ): PayrollCoverageResolutionV2 {
        val employer = employerId.trim()
        val zone = timeZoneId.trim()
        if (employer.isEmpty() || zone.isEmpty() || nowMs <= 0L ||
            requestedEndEpochDay < requestedStartEpochDay ||
            attestations.any { !PayrollCoverageAttestationPolicyV2.isStructurallyValid(it) }
        ) return corruptResolution(requestedStartEpochDay, requestedEndEpochDay, nowMs)

        val relevant = attestations.filter {
            it.employerId == employer &&
                it.timeZoneId == zone &&
                it.coveredEndEpochDay >= requestedStartEpochDay &&
                it.coveredStartEpochDay <= requestedEndEpochDay
        }
        if (relevant.any { it.checkedAtMs > nowMs }) {
            return corruptResolution(requestedStartEpochDay, requestedEndEpochDay, nowMs)
        }

        val current = relevant.filter {
            PayrollCoverageAttestationPolicyV2.isCurrent(it, sessions, nowMs)
        }
        val stalePresent = relevant.size != current.size

        var cursor = requestedStartEpochDay
        val used = mutableListOf<PayrollCoverageAttestationV2>()
        while (cursor <= requestedEndEpochDay) {
            val candidate = current.asSequence()
                .filter { it.coveredStartEpochDay <= cursor && it.coveredEndEpochDay >= cursor }
                .maxWithOrNull(
                    compareBy<PayrollCoverageAttestationV2> { it.coveredEndEpochDay }
                        .thenBy { it.checkedAtMs }
                ) ?: break
            used += candidate
            if (candidate.coveredEndEpochDay == Long.MAX_VALUE) {
                cursor = Long.MAX_VALUE
                break
            }
            cursor = candidate.coveredEndEpochDay + 1L
        }

        val exhaustive = cursor > requestedEndEpochDay
        val warnings = buildList {
            if (stalePresent) add(PayrollCoverageAttestationPolicyV2.STALE_WARNING)
            if (!exhaustive) add(PayrollCoverageAttestationPolicyV2.MISSING_WARNING)
        }.distinct()
        val checkedAt = if (exhaustive) used.maxOfOrNull { it.checkedAtMs } ?: nowMs else nowMs
        val sourceId = if (exhaustive) {
            "coverage-v1:" + used.map { it.id }.distinct().sorted().joinToString(",")
        } else {
            ""
        }
        return PayrollCoverageResolutionV2(
            reliable = true,
            exhaustive = exhaustive,
            coveredStartEpochDay = requestedStartEpochDay,
            coveredEndEpochDay = requestedEndEpochDay,
            checkedAtMs = checkedAt,
            sourceId = sourceId,
            warnings = warnings
        )
    }

    fun source(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SegmentedPayrollSessionSourceV2 {
        val runtime = V2RuntimeReader.allSessions(context, nowMs)
        val coverage = resolve(
            context = context,
            sessionsRead = runtime,
            employerId = employerId,
            requestedStartEpochDay = coveredStartEpochDay,
            requestedEndEpochDay = coveredEndEpochDay,
            timeZoneId = timeZoneId,
            nowMs = nowMs
        )
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = runtime.sessions,
            sourceId = coverage.sourceId.ifBlank { "runtime-unattested" },
            reliable = runtime.reliable && coverage.reliable,
            exhaustive = coverage.exhaustive,
            coveredStartEpochDay = coverage.coveredStartEpochDay,
            coveredEndEpochDay = coverage.coveredEndEpochDay,
            checkedAtMs = coverage.checkedAtMs,
            timeZoneId = timeZoneId.trim(),
            warnings = (runtime.warnings + coverage.warnings).distinct()
        )
    }

    internal fun decode(raw: String): PayrollCoverageReadResultV2 {
        if (raw.isBlank()) return corrupt()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return corrupt()
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return corrupt()
        val array = root.optJSONArray("items") ?: return corrupt()
        val result = mutableListOf<PayrollCoverageAttestationV2>()
        val ids = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return corrupt()
            val parsed = parse(item) ?: return corrupt()
            if (!ids.add(parsed.id)) return corrupt()
            result += parsed
        }
        return PayrollCoverageReadResultV2(result, true, emptyList())
    }

    internal fun encode(items: List<PayrollCoverageAttestationV2>): String? {
        if (items.map { it.id }.toSet().size != items.size ||
            items.any { !PayrollCoverageAttestationPolicyV2.isStructurallyValid(it) }
        ) return null
        val array = JSONArray()
        items.sortedWith(compareBy({ it.employerId }, { it.coveredStartEpochDay },
            { it.coveredEndEpochDay }, { it.checkedAtMs }, { it.id }))
            .forEach { item ->
                array.put(JSONObject()
                    .put("id", item.id)
                    .put("employerId", item.employerId)
                    .put("coveredStartEpochDay", item.coveredStartEpochDay)
                    .put("coveredEndEpochDay", item.coveredEndEpochDay)
                    .put("checkedAtMs", item.checkedAtMs)
                    .put("timeZoneId", item.timeZoneId)
                    .put("sessionFingerprint", item.sessionFingerprint))
            }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("items", array)
            .toString()
    }

    private fun write(context: Context, items: List<PayrollCoverageAttestationV2>): Boolean {
        val raw = encode(items) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit().putString(KEY_ITEMS, raw).commit()) return false
        return read(context) == PayrollCoverageReadResultV2(
            items.sortedWith(compareBy({ it.employerId }, { it.coveredStartEpochDay },
                { it.coveredEndEpochDay }, { it.checkedAtMs }, { it.id })),
            true,
            emptyList()
        )
    }

    private fun parse(item: JSONObject): PayrollCoverageAttestationV2? {
        val attestation = PayrollCoverageAttestationV2(
            id = item.optString("id").trim(),
            employerId = item.optString("employerId").trim(),
            coveredStartEpochDay = strictLong(item.opt("coveredStartEpochDay")) ?: return null,
            coveredEndEpochDay = strictLong(item.opt("coveredEndEpochDay")) ?: return null,
            checkedAtMs = strictLong(item.opt("checkedAtMs")) ?: return null,
            timeZoneId = item.optString("timeZoneId").trim(),
            sessionFingerprint = item.optString("sessionFingerprint").trim()
        )
        return attestation.takeIf(PayrollCoverageAttestationPolicyV2::isStructurallyValid)
    }

    private fun strictLong(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> (value as Number).toDouble()
            .takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun corrupt() = PayrollCoverageReadResultV2(
        emptyList(),
        false,
        listOf(PayrollCoverageAttestationPolicyV2.CORRUPT_WARNING)
    )

    private fun corruptResolution(start: Long, end: Long, nowMs: Long) =
        PayrollCoverageResolutionV2(
            reliable = false,
            exhaustive = false,
            coveredStartEpochDay = start,
            coveredEndEpochDay = end,
            checkedAtMs = nowMs.coerceAtLeast(0L),
            sourceId = "",
            warnings = listOf(PayrollCoverageAttestationPolicyV2.CORRUPT_WARNING)
        )
}
