package com.amaury.pointage.core.location

import com.amaury.pointage.core.time.WorkEvent
import com.amaury.pointage.core.time.WorkEventType

enum class CoherenceLevel {
    INFO,
    WARNING
}

enum class CoherenceCode {
    NO_GPS_CONTEXT,
    ENTRY_OUTSIDE_KNOWN_ZONE,
    EXIT_OUTSIDE_KNOWN_ZONE,
    PAUSE_OUTSIDE_KNOWN_ZONE
}

data class WorkEventCoherenceFinding(
    val eventId: String,
    val code: CoherenceCode,
    val level: CoherenceLevel,
    val occurredAtMs: Long,
    val message: String
)

/**
 * Analyse les écarts entre pointages réels et contexte GPS.
 *
 * Ce moteur est strictement consultatif : il ne crée, ne supprime, ne décale et ne
 * modifie jamais un WorkEvent. L'absence de GPS n'invalide jamais un pointage.
 */
object WorkEventCoherenceEngine {
    fun evaluate(contexts: List<WorkEventLocationContext>): List<WorkEventCoherenceFinding> =
        contexts.mapNotNull { context -> findingFor(context.event, context.matchingZoneIds) }

    private fun findingFor(event: WorkEvent, zoneIds: List<String>): WorkEventCoherenceFinding? {
        if (zoneIds.isNotEmpty()) return null

        val code = when (event.type) {
            WorkEventType.ENTRY -> CoherenceCode.ENTRY_OUTSIDE_KNOWN_ZONE
            WorkEventType.EXIT -> CoherenceCode.EXIT_OUTSIDE_KNOWN_ZONE
            WorkEventType.PAUSE_START,
            WorkEventType.PAUSE_END -> CoherenceCode.PAUSE_OUTSIDE_KNOWN_ZONE
        }

        return WorkEventCoherenceFinding(
            eventId = event.id,
            code = code,
            level = CoherenceLevel.INFO,
            occurredAtMs = event.occurredAtMs,
            message = "Pointage sans présence GPS confirmée dans une zone connue"
        )
    }
}
