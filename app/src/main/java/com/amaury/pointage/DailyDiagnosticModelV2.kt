package com.amaury.pointage

import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

/** Only these typed values may enter the diagnostic; no user labels, IDs or coordinates. */
internal enum class DailyDiagnosticKindV2 {
    RECORDING_ENABLED, RECORDING_DISABLED, APP_OPENED,
    GEOFENCE_ENTER, GEOFENCE_EXIT, GEOFENCE_OTHER, GEOFENCE_ERROR,
    GPS_DISABLED, GPS_CONFIG_UNREADABLE, GPS_REGISTRATION_STALE, GPS_ZONE_UNKNOWN,
    GPS_EVENT, GPS_DECISION, GPS_OUTCOME, GPS_REASON, PROMPT_SHOWN, CONFIRM_EXIT, CONFIRM_PAUSE_START,
    CONFIRM_PAUSE_END, CONFIRM_CANCEL,
    ENTRY_REQUEST, ENTRY_REFUSED, ENTRY_WRITE, EXIT_REQUEST, EXIT_WRITE, PAUSE_REQUEST, PAUSE_START_WRITE, PAUSE_END_WRITE
}
internal enum class ClosureOriginV2 { UNKNOWN, MANUAL, WIDGET, GPS_CONFIRMATION, AUTOMATIC }
internal data class DailyDiagnosticEventV2(
    val recordedAtMs: Long,
    val observedAtMs: Long,
    val kind: DailyDiagnosticKindV2,
    val code: String = "",
    val value: Long? = null,
    val zoneToken: String? = null
)
internal object DailyDiagnosticModelV2 {
    const val MAX_EVENTS = 2000
    const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
    // Codes are machine enums/flags; free text is deliberately not supported.
    private val codes = setOf("", "SUCCESS", "SUCCESS_PAID", "SUCCESS_UNPAID", "FAILED", "POSTE_ENTER", "POSTE_EXIT",
        "PARKING_ENTER", "PARKING_EXIT", "OTHER_ENTER", "OTHER_EXIT", "ACCEPTED", "REJECTED", "DUPLICATE",
        "IGNORED", "NO_CHANGE", "RETURNED_TO_POSTE", "ENTRY_STARTED", "DEPARTURE_OBSERVED", "EXIT_PENDING_CONFIRMATION",
        "AMBIGUOUS_PENDING_CONFIRMATION", "MANUAL", "GPS", "IMPORT", "SYSTEM", "WIDGET", "AUTOMATIC", "PAID", "UNPAID", "RUNTIME_UNRELIABLE", "FOREIGN_OBSERVATION", "CONTEXT_UNVERIFIED",
        "PENDING_CLEAR_FAILED", "NO_OPEN_SESSION", "PENDING_ALREADY_EXISTS", "SESSION_ALREADY_OPEN", "STORAGE_FAILED", "OTHER_REASON",
        "UNKNOWN", "GPS_CONFIRMATION", "INVALID_TIME", "HISTORY_UNRELIABLE", "HISTORY_OVERLAP_OR_INVALID")
    fun reasonCode(reason: String): String = when (reason) {
        "Runtime V2 non fiable : transition GPS bloquée" -> "RUNTIME_UNRELIABLE"
        "Observation GPS liée à un autre contexte" -> "FOREIGN_OBSERVATION"
        "Contexte GPS à vérifier" -> "CONTEXT_UNVERIFIED"
        "Effacement GPS non enregistré", "Retour GPS non enregistré" -> "PENDING_CLEAR_FAILED"
        "Aucune session V2 ouverte à terminer", "État de travail incompatible avec cette transition GPS ambiguë" -> "NO_OPEN_SESSION"
        "Une transition GPS attend déjà une confirmation" -> "PENDING_ALREADY_EXISTS"
        "Session déjà ouverte", "Session V2 déjà ouverte" -> "SESSION_ALREADY_OPEN"
        "Sortie GPS non enregistrée", "Transition GPS non enregistrée" -> "STORAGE_FAILED"
        else -> "OTHER_REASON"
    }
    fun safeCode(code: String) = code.takeIf { it in codes } ?: ""
    fun retain(events: List<DailyDiagnosticEventV2>, nowMs: Long): List<DailyDiagnosticEventV2> =
        events.filter { it.recordedAtMs >= nowMs - RETENTION_MS && it.recordedAtMs <= nowMs }
            .takeLast(MAX_EVENTS)
    fun forDay(events: List<DailyDiagnosticEventV2>, date: LocalDate, zone: ZoneId): List<DailyDiagnosticEventV2> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Delayed confirmations can belong to the observation day OR processing day.
        return events.filter { it.observedAtMs in start until end || it.recordedAtMs in start until end }
    }
    fun timestamp(atMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(atMs).atZone(zone).toString()
}
