package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.TravelV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

data class PayrollCoverageAttestationV2(
    val id: String,
    val employerId: String,
    val coveredStartEpochDay: Long,
    val coveredEndEpochDay: Long,
    val checkedAtMs: Long,
    val timeZoneId: String,
    val sessionFingerprint: String
)

object PayrollCoverageAttestationPolicyV2 {
    const val MISSING_WARNING =
        "Preuves B21 : aucune attestation explicite d'exhaustivité ne couvre cette période."
    const val STALE_WARNING =
        "Preuves B21 : les pointages ont changé depuis la confirmation d'exhaustivité."
    const val CORRUPT_WARNING =
        "Preuves B21 : registre d'attestations illisible ou incohérent ; exhaustivité non prouvée."

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
        if (employer.isEmpty() ||
            coveredStartEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            coveredEndEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            coveredEndEpochDay < coveredStartEpochDay
        ) return null

        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return null
        val from = localStart(coveredStartEpochDay, zone) ?: return null
        val to = localStart(coveredEndEpochDay + 1L, zone) ?: return null
        if (to <= from) return null

        val relevant = sessions.asSequence()
            .filter { session ->
                val sessionEmployer = session.employerId?.trim().orEmpty()
                (sessionEmployer.isEmpty() || sessionEmployer == employer) &&
                    touches(session, from, to)
            }
            .sortedWith(compareBy<WorkSessionV2> { it.id.trim() }
                .thenBy { it.realArrivalMs ?: Long.MIN_VALUE }
                .thenBy { it.realExitMs ?: Long.MIN_VALUE })
            .toList()

        val canonical = buildString {
            field("coverage-v1")
            field(employer)
            field(coveredStartEpochDay)
            field(coveredEndEpochDay)
            field(zone.id)
            relevant.forEach { session ->
                field("session")
                field(session.id.trim())
                field(session.employerId?.trim())
                field(session.realArrivalMs)
                field(session.countedEntryMs)
                field(session.countedExitMs)
                field(session.realExitMs)
                field(session.status.name)
                field(session.placeId?.trim())
                field(session.placeLabel?.trim())
                field(session.legacyFixedUnpaidPauseMs)
                session.pauses.sortedWith(
                    compareBy<PauseV2> { it.startMs }
                        .thenBy { it.endMs ?: Long.MIN_VALUE }
                        .thenBy { it.source.name }
                        .thenBy { it.status.name }
                ).forEach { pause ->
                    field("pause")
                    field(pause.startMs)
                    field(pause.endMs)
                    field(pause.paid?.toString())
                    field(pause.source.name)
                    field(pause.status.name)
                }
                session.travels.sortedWith(
                    compareBy<TravelV2> { it.startMs }
                        .thenBy { it.endMs ?: Long.MIN_VALUE }
                        .thenBy { it.employerBeforeId ?: "" }
                        .thenBy { it.employerAfterId ?: "" }
                        .thenBy { it.classification.name }
                ).forEach { travel ->
                    field("travel")
                    field(travel.startMs)
                    field(travel.endMs)
                    field(travel.employerBeforeId?.trim())
                    field(travel.employerAfterId?.trim())
                    field(travel.distanceMeters?.let(java.lang.Double::doubleToLongBits))
                    field(travel.classification.name)
                }
                field("end-session")
            }
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isStructurallyValid(attestation: PayrollCoverageAttestationV2): Boolean {
        val employer = attestation.employerId.trim()
        val zone = runCatching { ZoneId.of(attestation.timeZoneId.trim()) }.getOrNull() ?: return false
        if (attestation.id.isBlank() || employer.isEmpty() ||
            attestation.coveredStartEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            attestation.coveredEndEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY ||
            attestation.coveredEndEpochDay < attestation.coveredStartEpochDay ||
            attestation.checkedAtMs <= 0L ||
            attestation.sessionFingerprint.isBlank()
        ) return false
        return coverageClosedBeforeCheck(
            attestation.coveredEndEpochDay,
            attestation.checkedAtMs,
            zone.id
        )
    }

    fun isCurrent(
        attestation: PayrollCoverageAttestationV2,
        sessions: List<WorkSessionV2>,
        nowMs: Long
    ): Boolean {
        if (!isStructurallyValid(attestation) || nowMs <= 0L || attestation.checkedAtMs > nowMs) {
            return false
        }
        val current = fingerprint(
            sessions = sessions,
            employerId = attestation.employerId,
            coveredStartEpochDay = attestation.coveredStartEpochDay,
            coveredEndEpochDay = attestation.coveredEndEpochDay,
            timeZoneId = attestation.timeZoneId
        ) ?: return false
        return current == attestation.sessionFingerprint
    }

    fun coverageClosedBeforeCheck(
        coveredEndEpochDay: Long,
        checkedAtMs: Long,
        timeZoneId: String
    ): Boolean {
        if (coveredEndEpochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY || checkedAtMs <= 0L) return false
        val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return false
        val endExclusive = localStart(coveredEndEpochDay + 1L, zone) ?: return false
        return endExclusive <= checkedAtMs
    }

    private fun touches(session: WorkSessionV2, from: Long, to: Long): Boolean {
        val starts = listOfNotNull(session.realArrivalMs, session.countedEntryMs)
        val ends = listOfNotNull(session.realExitMs, session.countedExitMs)
        val start = starts.minOrNull() ?: return true
        val end = ends.maxOrNull() ?: return start < to
        return if (end == start) start in from until to
        else minOf(start, end) < to && maxOf(start, end) > from
    }

    private fun localStart(epochDay: Long, zone: ZoneId): Long? =
        try {
            val day = LocalDate.ofEpochDay(epochDay)
            val value = day.atStartOfDay(zone)
            if (value.toLocalDate() != day) null else value.toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }

    private fun StringBuilder.field(value: Any?) {
        val raw = value?.toString() ?: "<null>"
        append(raw.length).append(':').append(raw).append('|')
    }
}
