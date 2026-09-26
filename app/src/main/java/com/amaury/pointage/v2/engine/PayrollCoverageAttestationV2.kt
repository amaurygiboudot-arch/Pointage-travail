package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

data class PayrollCoverageAttestationV2(
    val employerId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val sourceId: String,
    val sessionFingerprint: String
)

object PayrollCoverageAttestationPolicyV2 {
    const val MISSING_WARNING =
        "Preuves B21 : aucune attestation explicite d'exhaustivité ne couvre cette période."
    const val STALE_WARNING =
        "Preuves B21 : les pointages ont changé depuis la confirmation d'exhaustivité."
    const val INVALID_WARNING =
        "Preuves B21 : attestation d'exhaustivité incohérente ou hors période vérifiable."

    fun fingerprint(
        sessions: List<WorkSessionV2>,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        timeZoneId: String
    ): String? {
        if (coveredEndEpochDay < coveredStartEpochDay) return null
        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrNull() ?: return null
        val from = runCatching {
            LocalDate.ofEpochDay(coveredStartEpochDay).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        val to = runCatching {
            LocalDate.ofEpochDay(Math.addExact(coveredEndEpochDay, 1L))
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        if (to <= from) return null

        val relevant = sessions.filter { touches(it, from, to) }.sortedBy { it.id.trim() }
        val canonical = buildString {
            appendField("v1")
            appendField(coveredStartEpochDay.toString())
            appendField(coveredEndEpochDay.toString())
            appendField(timeZoneId.trim())
            relevant.forEach { session ->
                appendField(session.id.trim())
                appendField(session.employerId?.trim().orEmpty())
                appendField(session.realArrivalMs?.toString().orEmpty())
                appendField(session.countedEntryMs?.toString().orEmpty())
                appendField(session.countedExitMs?.toString().orEmpty())
                appendField(session.realExitMs?.toString().orEmpty())
                appendField(session.status.name)
                appendField(session.placeId?.trim().orEmpty())
                appendField(session.placeLabel?.trim().orEmpty())
                appendField(session.legacyFixedUnpaidPauseMs.toString())
                session.pauses.sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MAX_VALUE }, { it.source.name }))
                    .forEach { pause ->
                        appendField("pause")
                        appendField(pause.startMs.toString())
                        appendField(pause.endMs?.toString().orEmpty())
                        appendField(pause.paid?.toString().orEmpty())
                        appendField(pause.source.name)
                        appendField(pause.status.name)
                    }
                session.travels.sortedWith(compareBy({ it.startMs }, { it.endMs ?: Long.MAX_VALUE }))
                    .forEach { travel ->
                        appendField("travel")
                        appendField(travel.startMs.toString())
                        appendField(travel.endMs?.toString().orEmpty())
                        appendField(travel.employerBeforeId?.trim().orEmpty())
                        appendField(travel.employerAfterId?.trim().orEmpty())
                        appendField(travel.distanceMeters?.toBits()?.toString().orEmpty())
                        appendField(travel.classification.name)
                    }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isValid(
        attestation: PayrollCoverageAttestationV2,
        sessions: List<WorkSessionV2>,
        employerId: String,
        coveredStartEpochDay: Long,
        coveredEndEpochDay: Long,
        timeZoneId: String,
        nowMs: Long
    ): Boolean {
        if (nowMs <= 0L || attestation.checkedAtMs <= 0L || attestation.checkedAtMs > nowMs) return false
        if (attestation.employerId.trim() != employerId.trim() || employerId.trim().isEmpty()) return false
        if (attestation.coveredStartEpochDay != coveredStartEpochDay ||
            attestation.coveredEndEpochDay != coveredEndEpochDay ||
            attestation.timeZoneId.trim() != timeZoneId.trim() ||
            attestation.sourceId.isBlank() ||
            attestation.sessionFingerprint.isBlank()
        ) return false
        val current = fingerprint(sessions, coveredStartEpochDay, coveredEndEpochDay, timeZoneId)
            ?: return false
        return current == attestation.sessionFingerprint
    }

    fun coverageClosedBeforeCheck(
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String
    ): Boolean {
        val zone = runCatching { ZoneId.of(timeZoneId) }.getOrNull() ?: return false
        val endExclusive = runCatching {
            LocalDate.ofEpochDay(Math.addExact(coveredEndEpochDay, 1L))
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return false
        return endExclusive <= checkedAtMs
    }

    private fun touches(session: WorkSessionV2, from: Long, to: Long): Boolean {
        val starts = listOfNotNull(session.realArrivalMs, session.countedEntryMs)
        val ends = listOfNotNull(session.realExitMs, session.countedExitMs)
        val start = starts.minOrNull() ?: return true
        val end = ends.maxOrNull() ?: return start < to
        return if (end == start) start in from until to else minOf(start, end) < to && maxOf(start, end) > from
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append('|')
    }
}
