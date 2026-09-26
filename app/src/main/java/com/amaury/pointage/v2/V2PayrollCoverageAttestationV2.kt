package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedPayrollSessionSourceV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class V2PayrollCoverageAttestationV2(
    val id: String,
    val employerId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val sessionFingerprint: String
)

data class V2PayrollCoverageResolutionV2(
    val reliable: Boolean,
    val exhaustive: Boolean,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val sourceId: String,
    val warnings: List<String>
)

object V2PayrollCoverageAttestationPolicyV2 {
    const val MISSING_WARNING =
        "Preuves B21 : aucune attestation explicite d'exhaustivité ne couvre cette période."
    const val STALE_WARNING =
        "Preuves B21 : les pointages ont changé depuis la confirmation d'exhaustivité."
    const val CORRUPT_WARNING =
        "Preuves B21 : registre d'attestations illisible ou incohérent ; exhaustivité non prouvée."
    const val SOURCE_WARNING =
        "Preuves B21 : historique V2 non fiable ; aucune attestation de couverture n'est utilisable."

    private const val MIN_EPOCH_DAY = -25_567L
    private const val MAX_EPOCH_DAY = 84_370L

    fun fingerprint(
        sessions: List<WorkSessionV2>,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        timeZoneId: String
    ): String? {
        val employer = employerId.trim()
        if (employer.isEmpty() || coveredStartEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            coveredEndEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            coveredEndEpochDay < coveredStartEpochDay
        ) return null
        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return null
        val from = startOfDay(coveredStartEpochDay, zone) ?: return null
        val to = startOfDay(coveredEndEpochDay + 1L, zone) ?: return null
        if (to <= from) return null

        val relevant = sessions.filter { session ->
            val sessionEmployer = session.employerId?.trim().orEmpty()
            (sessionEmployer.isEmpty() || sessionEmployer == employer) && touches(session, from, to)
        }.sortedWith(compareBy<WorkSessionV2>(
            { it.id.trim() },
            { it.realArrivalMs ?: it.countedEntryMs ?: Long.MIN_VALUE },
            { it.realExitMs ?: it.countedExitMs ?: Long.MIN_VALUE }
        ))

        val canonical = buildString {
            token("coverage-v1")
            token(employer)
            token(coveredStartEpochDay)
            token(coveredEndEpochDay)
            token(zone.id)
            relevant.forEach { session ->
                token("session")
                token(session.id.trim())
                token(session.employerId?.trim())
                token(session.realArrivalMs)
                token(session.countedEntryMs)
                token(session.countedExitMs)
                token(session.realExitMs)
                token(session.status.name)
                token(session.placeId)
                token(session.placeLabel)
                token(session.legacyFixedUnpaidPauseMs)
                session.pauses.sortedWith(compareBy(
                    { it.startMs }, { it.endMs ?: Long.MIN_VALUE }, { it.source.name }
                )).forEach { pause ->
                    token("pause")
                    token(pause.startMs)
                    token(pause.endMs)
                    token(pause.paid)
                    token(pause.source.name)
                    token(pause.status.name)
                }
                session.travels.sortedWith(compareBy(
                    { it.startMs }, { it.endMs ?: Long.MIN_VALUE }, { it.employerBeforeId ?: "" }
                )).forEach { travel ->
                    token("travel")
                    token(travel.startMs)
                    token(travel.endMs)
                    token(travel.employerBeforeId)
                    token(travel.employerAfterId)
                    token(travel.distanceMeters?.toBits())
                    token(travel.classification.name)
                }
                token("end-session")
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isStructurallyValid(item: V2PayrollCoverageAttestationV2): Boolean {
        if (runCatching { UUID.fromString(item.id) }.isFailure ||
            item.employerId.trim().isEmpty() ||
            item.coveredStartEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            item.coveredEndEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            item.coveredEndEpochDay < item.coveredStartEpochDay ||
            item.checkedAtMs <= 0L ||
            runCatching { ZoneId.of(item.timeZoneId.trim()) }.isFailure ||
            item.sessionFingerprint.length != 64 ||
            item.sessionFingerprint.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }
        ) return false
        return coverageClosedBeforeCheck(item.coveredEndEpochDay, item.checkedAtMs, item.timeZoneId)
    }

    fun isCurrent(
        item: V2PayrollCoverageAttestationV2,
        sessions: List<WorkSessionV2>,
        nowMs: Long
    ): Boolean {
        if (!isStructurallyValid(item) || nowMs <= 0L || item.checkedAtMs > nowMs) return false
        val current = fingerprint(
            sessions = sessions,
            employerId = item.employerId,
            coveredStartEpochDay = item.coveredStartEpochDay,
            coveredEndEpochDay = item.coveredEndEpochDay,
            timeZoneId = item.timeZoneId
        ) ?: return false
        return current.equals(item.sessionFingerprint, ignoreCase = true)
    }

    fun coverageClosedBeforeCheck(
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String
    ): Boolean {
        if (coveredEndEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY || checkedAtMs <= 0L) return false
        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return false
        val end = startOfDay(coveredEndEpochDay + 1L, zone) ?: return false
        return end <= checkedAtMs
    }

    private fun touches(session: WorkSessionV2, from: Long, to: Long): Boolean {
        val start = session.realArrivalMs ?: session.countedEntryMs ?: return true
        val end = session.realExitMs ?: session.countedExitMs
        if (end == null) return start < to
        if (end == start) return start in from until to
        return minOf(start, end) < to && maxOf(start, end) > from
    }

    private fun startOfDay(epochDay: Long, zone: ZoneId): Long? = runCatching {
        val day = LocalDate.ofEpochDay(epochDay)
        if (day.year !in 1900..2200) return@runCatching null
        val value = day.atStartOfDay(zone)
        if (value.toLocalDate() != day) return@runCatching null
        value.toInstant().toEpochMilli()
    }.getOrNull()

    private fun StringBuilder.token(value: Any?) {
        if (value == null) {
            append("-1:|")
        } else {
            val text = value.toString()
            append(text.toByteArray(Charsets.UTF_8).size).append(':').append(text).append('|')
        }
    }
}

object V2PayrollCoverageAttestationStoreV2 {
    private const val PREFS = "horatrack_v2_payroll_coverage"
    private const val KEY = "attestations"
    private const val SCHEMA_VERSION = 1
    private const val MAX_ATTESTATIONS = 64

    data class ReadResult(
        val attestations: List<V2PayrollCoverageAttestationV2>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun saveConfirmed(
        context: Context,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): V2PayrollCoverageAttestationV2? {
        val read = V2RuntimeReader.allSessions(context, nowMs)
        val employer = employerId.trim()
        val zone = timeZoneId.trim()
        if (!read.reliable || employer.isEmpty() || checkedAtMs !in 1..nowMs ||
            !V2PayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
                coveredEndEpochDay, checkedAtMs, zone
            )
        ) return null
        val fingerprint = V2PayrollCoverageAttestationPolicyV2.fingerprint(
            read.sessions, employer, coveredStartEpochDay, coveredEndEpochDay, zone
        ) ?: return null
        val stored = read(context)
        if (!stored.reliable) return null
        val item = V2PayrollCoverageAttestationV2(
            id = UUID.randomUUID().toString(),
            employerId = employer,
            coveredStartEpochDay = coveredStartEpochDay,
            coveredEndEpochDay = coveredEndEpochDay,
            checkedAtMs = checkedAtMs,
            timeZoneId = ZoneId.of(zone).id,
            sessionFingerprint = fingerprint
        )
        val next = (stored.attestations.filterNot {
            it.employerId == item.employerId &&
                it.coveredStartEpochDay == item.coveredStartEpochDay &&
                it.coveredEndEpochDay == item.coveredEndEpochDay &&
                it.timeZoneId == item.timeZoneId
        } + item).sortedByDescending { it.checkedAtMs }.take(MAX_ATTESTATIONS)
        val payload = encode(next) ?: return null
        val ok = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, payload).commit()
        if (!ok) return null
        val verified = read(context)
        return item.takeIf { verified.reliable && verified.attestations.contains(it) }
    }

    fun read(context: Context): ReadResult {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return ReadResult(emptyList(), true, emptyList())
        return decode(raw)
    }

    fun resolve(
        attestations: List<V2PayrollCoverageAttestationV2>,
        sessions: List<WorkSessionV2>,
        sourceReliable: Boolean,
        sourceWarnings: List<String>,
        employerId: String,
        requestedStartEpochDay: Long,
        requestedEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long
    ): V2PayrollCoverageResolutionV2 {
        val warnings = sourceWarnings.toMutableList()
        val employer = employerId.trim()
        val zone = timeZoneId.trim()
        if (!sourceReliable) {
            warnings += V2PayrollCoverageAttestationPolicyV2.SOURCE_WARNING
            return blocked(requestedStartEpochDay, requestedEndEpochDay, nowMs, warnings)
        }
        if (employer.isEmpty() || zone.isEmpty() || nowMs <= 0L ||
            requestedEndEpochDay < requestedStartEpochDay ||
            attestations.any { !V2PayrollCoverageAttestationPolicyV2.isStructurallyValid(it) }
        ) {
            warnings += V2PayrollCoverageAttestationPolicyV2.CORRUPT_WARNING
            return blocked(requestedStartEpochDay, requestedEndEpochDay, nowMs, warnings)
        }

        val relevant = attestations.filter {
            it.employerId.trim() == employer &&
                it.timeZoneId == zone &&
                it.coveredEndEpochDay >= requestedStartEpochDay &&
                it.coveredStartEpochDay <= requestedEndEpochDay
        }
        if (relevant.any { it.checkedAtMs > nowMs }) {
            warnings += V2PayrollCoverageAttestationPolicyV2.CORRUPT_WARNING
            return blocked(requestedStartEpochDay, requestedEndEpochDay, nowMs, warnings)
        }
        val current = relevant.filter {
            V2PayrollCoverageAttestationPolicyV2.isCurrent(it, sessions, nowMs)
        }
        val stalePresent = current.size != relevant.size

        var cursor = requestedStartEpochDay
        val used = mutableListOf<V2PayrollCoverageAttestationV2>()
        while (cursor <= requestedEndEpochDay) {
            val candidate = current
                .filter { it.coveredStartEpochDay <= cursor && it.coveredEndEpochDay >= cursor }
                .maxWithOrNull(compareBy<V2PayrollCoverageAttestationV2>(
                    { it.coveredEndEpochDay }, { it.checkedAtMs }
                )) ?: break
            used += candidate
            if (candidate.coveredEndEpochDay == Long.MAX_VALUE) {
                cursor = Long.MAX_VALUE
                break
            }
            cursor = candidate.coveredEndEpochDay + 1L
        }

        val exhaustive = cursor > requestedEndEpochDay
        if (stalePresent) warnings += V2PayrollCoverageAttestationPolicyV2.STALE_WARNING
        if (!exhaustive) warnings += V2PayrollCoverageAttestationPolicyV2.MISSING_WARNING
        val checkedAt = if (exhaustive) used.maxOfOrNull { it.checkedAtMs } ?: nowMs else nowMs
        val sourceId = if (exhaustive) {
            "coverage-v1:" + used.map { it.id }.distinct().sorted().joinToString(",")
        } else {
            ""
        }
        return V2PayrollCoverageResolutionV2(
            reliable = true,
            exhaustive = exhaustive,
            coveredStartEpochDay = requestedStartEpochDay,
            coveredEndEpochDay = requestedEndEpochDay,
            checkedAtMs = checkedAt,
            sourceId = sourceId,
            warnings = warnings.distinct()
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
        val work = V2RuntimeReader.allSessions(context, nowMs)
        val stored = read(context)
        val coverage = if (!stored.reliable) {
            blocked(
                coveredStartEpochDay, coveredEndEpochDay, nowMs,
                work.warnings + stored.warnings
            )
        } else {
            resolve(
                attestations = stored.attestations,
                sessions = work.sessions,
                sourceReliable = work.reliable,
                sourceWarnings = work.warnings,
                employerId = employerId,
                requestedStartEpochDay = coveredStartEpochDay,
                requestedEndEpochDay = coveredEndEpochDay,
                timeZoneId = timeZoneId,
                nowMs = nowMs
            )
        }
        return SegmentedPayrollSessionSourceV2(
            employerId = employerId.trim(),
            sessions = work.sessions,
            sourceId = coverage.sourceId.ifBlank { "runtime-unattested" },
            reliable = work.reliable && coverage.reliable,
            exhaustive = coverage.reliable && coverage.exhaustive,
            coveredStartEpochDay = coverage.coveredStartEpochDay,
            coveredEndEpochDay = coverage.coveredEndEpochDay,
            checkedAtMs = coverage.checkedAtMs,
            timeZoneId = timeZoneId.trim(),
            warnings = coverage.warnings.distinct()
        )
    }

    private fun encode(items: List<V2PayrollCoverageAttestationV2>): String? {
        if (items.map { it.id }.distinct().size != items.size ||
            items.any { !V2PayrollCoverageAttestationPolicyV2.isStructurallyValid(it) }
        ) return null
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject()
                        .put("id", item.id)
                        .put("employerId", item.employerId)
                        .put("coveredStartEpochDay", item.coveredStartEpochDay)
                        .put("coveredEndEpochDay", item.coveredEndEpochDay)
                        .put("checkedAtMs", item.checkedAtMs)
                        .put("timeZoneId", item.timeZoneId)
                        .put("sessionFingerprint", item.sessionFingerprint))
                }
            }).toString()
    }

    private fun decode(raw: String): ReadResult {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return corruptRead()
        if (root.optInt("schemaVersion", 0) != SCHEMA_VERSION) return corruptRead()
        val array = root.optJSONArray("items") ?: return corruptRead()
        val items = mutableListOf<V2PayrollCoverageAttestationV2>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: return corruptRead()
            val item = V2PayrollCoverageAttestationV2(
                id = o.optString("id"),
                employerId = o.optString("employerId"),
                coveredStartEpochDay = o.optLong("coveredStartEpochDay", Long.MIN_VALUE),
                coveredEndEpochDay = o.optLong("coveredEndEpochDay", Long.MIN_VALUE),
                checkedAtMs = o.optLong("checkedAtMs", 0L),
                timeZoneId = o.optString("timeZoneId"),
                sessionFingerprint = o.optString("sessionFingerprint").lowercase()
            )
            if (!V2PayrollCoverageAttestationPolicyV2.isStructurallyValid(item)) return corruptRead()
            items += item
        }
        if (items.map { it.id }.distinct().size != items.size) return corruptRead()
        return ReadResult(items.sortedBy { it.checkedAtMs }, true, emptyList())
    }

    private fun corruptRead() = ReadResult(
        emptyList(), false, listOf(V2PayrollCoverageAttestationPolicyV2.CORRUPT_WARNING)
    )

    private fun blocked(start: Long, end: Long, nowMs: Long, warnings: List<String>) =
        V2PayrollCoverageResolutionV2(
            reliable = false,
            exhaustive = false,
            coveredStartEpochDay = start,
            coveredEndEpochDay = end,
            checkedAtMs = nowMs,
            sourceId = "",
            warnings = warnings.distinct()
        )
}
