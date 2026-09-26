package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.model.WorkSessionV2
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

data class RuntimeCoverageAttestationV2(
    val sourceId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val journalDigest: String
)

data class RuntimeCoverageSourceReadV2(
    val source: SegmentedPayrollSessionSourceV2?,
    val reliable: Boolean,
    val warnings: List<String>
)

enum class RuntimeCoverageClaimOriginV2 {
    USER_REVIEWED_CLOSED_PERIOD,
    LOCAL_BACKUP_RESTORE,
    CLOUD_BACKUP_RESTORE,
    LEGACY_MIGRATION,
    STORAGE_READ
}

object RuntimeCoverageClaimPolicyV2 {
    fun mayIssue(origin: RuntimeCoverageClaimOriginV2): Boolean =
        origin == RuntimeCoverageClaimOriginV2.USER_REVIEWED_CLOSED_PERIOD
}

object RuntimeCoverageAttestationPolicyV2 {
    const val WARNING = "Preuves B21 : attestation exhaustive des pointages absente, périmée ou incohérente."

    fun create(
        sessions: List<WorkSessionV2>,
        sourceId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String,
        nowMs: Long
    ): RuntimeCoverageAttestationV2? {
        if (sourceId.isBlank() || checkedAtMs <= 0L || checkedAtMs > nowMs ||
            coveredEndEpochDay < coveredStartEpochDay) return null
        val bounds = bounds(coveredStartEpochDay, coveredEndEpochDay, timeZoneId) ?: return null
        if (bounds.second > checkedAtMs) return null
        val relevant = relevant(sessions, bounds.first, bounds.second) ?: return null
        if (relevant.any { it.realExitMs == null || it.status.name != "CLOSED" }) return null
        return RuntimeCoverageAttestationV2(
            sourceId.trim(), coveredStartEpochDay, coveredEndEpochDay, checkedAtMs,
            timeZoneId, digest(relevant)
        )
    }

    fun validate(
        attestation: RuntimeCoverageAttestationV2,
        sessions: List<WorkSessionV2>,
        nowMs: Long
    ): Boolean {
        if (attestation.sourceId.isBlank() || attestation.checkedAtMs <= 0L ||
            attestation.checkedAtMs > nowMs ||
            attestation.coveredEndEpochDay < attestation.coveredStartEpochDay) return false
        val bounds = bounds(attestation.coveredStartEpochDay, attestation.coveredEndEpochDay,
            attestation.timeZoneId) ?: return false
        if (bounds.second > attestation.checkedAtMs) return false
        val relevant = relevant(sessions, bounds.first, bounds.second) ?: return false
        if (relevant.any { it.realExitMs == null || it.status.name != "CLOSED" }) return false
        return digest(relevant) == attestation.journalDigest
    }

    private fun bounds(start: Long, end: Long, zoneId: String): Pair<Long, Long>? = try {
        val zone = ZoneId.of(zoneId)
        val from = LocalDate.ofEpochDay(start).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.ofEpochDay(Math.addExact(end, 1L)).atStartOfDay(zone).toInstant().toEpochMilli()
        if (to <= from) null else from to to
    } catch (_: Exception) { null }

    private fun relevant(sessions: List<WorkSessionV2>, from: Long, to: Long): List<WorkSessionV2>? {
        val result = mutableListOf<WorkSessionV2>()
        for (session in sessions) {
            val entry = session.realArrivalMs ?: return null
            val exit = session.realExitMs
            if (entry < to && (exit == null || exit > from)) result += session
        }
        return result.sortedWith(compareBy<WorkSessionV2>({ it.realArrivalMs }, { it.id }))
    }

    internal fun digest(sessions: List<WorkSessionV2>): String {
        val canonical = buildString {
            sessions.forEach { s ->
                append(s.id.trim()).append('|').append(s.employerId?.trim().orEmpty()).append('|')
                append(s.realArrivalMs).append('|').append(s.countedEntryMs).append('|')
                append(s.realExitMs).append('|').append(s.countedExitMs).append('|')
                append(s.status.name).append('|').append(s.legacyFixedUnpaidPauseMs).append('|')
                s.pauses.sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MAX_VALUE }, { it.source.name }))
                    .forEach { p ->
                        append("P:").append(p.startMs).append(':').append(p.endMs).append(':')
                            .append(p.paid).append(':').append(p.source.name).append(':').append(p.status.name).append('|')
                    }
                s.travels.sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MAX_VALUE }))
                    .forEach { t ->
                        append("T:").append(t.startMs).append(':').append(t.endMs).append(':')
                            .append(t.employerBeforeId.orEmpty()).append(':').append(t.employerAfterId.orEmpty()).append(':')
                            .append(t.distanceMeters).append(':').append(t.classification.name).append('|')
                    }
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object V2RuntimeCoverageAttestationStore {
    private const val PREFS = "horatrack_v2_runtime_coverage"
    private const val KEY = "attestation_v1"

    fun confirm(
        context: Context,
        origin: RuntimeCoverageClaimOriginV2,
        sourceId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!RuntimeCoverageClaimPolicyV2.mayIssue(origin)) return false
        val read = V2RuntimeReader.allSessions(context, nowMs)
        if (!read.reliable) return false
        val attestation = RuntimeCoverageAttestationPolicyV2.create(
            read.sessions, sourceId, coveredStartEpochDay, coveredEndEpochDay,
            checkedAtMs, timeZoneId, nowMs
        ) ?: return false
        val json = JSONObject()
            .put("sourceId", attestation.sourceId)
            .put("coveredStartEpochDay", attestation.coveredStartEpochDay)
            .put("coveredEndEpochDay", attestation.coveredEndEpochDay)
            .put("checkedAtMs", attestation.checkedAtMs)
            .put("timeZoneId", attestation.timeZoneId)
            .put("journalDigest", attestation.journalDigest)
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).commit()
    }

    fun source(
        context: Context,
        employerId: String,
        requiredStartEpochDay: Long,
        requiredEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long = System.currentTimeMillis()
    ): RuntimeCoverageSourceReadV2 {
        val read = V2RuntimeReader.allSessions(context, nowMs)
        if (!read.reliable) return RuntimeCoverageSourceReadV2(null, false, read.warnings)
        val attestation = readStored(context) ?: return RuntimeCoverageSourceReadV2(
            null, false, listOf(RuntimeCoverageAttestationPolicyV2.WARNING))
        val valid = attestation.timeZoneId == timeZoneId &&
            attestation.coveredStartEpochDay <= requiredStartEpochDay &&
            attestation.coveredEndEpochDay >= requiredEndEpochDay &&
            RuntimeCoverageAttestationPolicyV2.validate(attestation, read.sessions, nowMs)
        if (!valid) return RuntimeCoverageSourceReadV2(
            null, false, listOf(RuntimeCoverageAttestationPolicyV2.WARNING))
        return RuntimeCoverageSourceReadV2(
            SegmentedPayrollSessionSourceV2(
                employerId = employerId,
                sessions = read.sessions,
                sourceId = attestation.sourceId,
                reliable = true,
                exhaustive = true,
                coveredStartEpochDay = attestation.coveredStartEpochDay,
                coveredEndEpochDay = attestation.coveredEndEpochDay,
                checkedAtMs = attestation.checkedAtMs,
                timeZoneId = attestation.timeZoneId,
                warnings = read.warnings
            ), true, read.warnings
        )
    }

    private fun readStored(context: Context): RuntimeCoverageAttestationV2? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            RuntimeCoverageAttestationV2(
                sourceId = o.getString("sourceId"),
                coveredStartEpochDay = o.getLong("coveredStartEpochDay"),
                coveredEndEpochDay = o.getLong("coveredEndEpochDay"),
                checkedAtMs = o.getLong("checkedAtMs"),
                timeZoneId = o.getString("timeZoneId"),
                journalDigest = o.getString("journalDigest")
            )
        }.getOrNull()
    }
}
