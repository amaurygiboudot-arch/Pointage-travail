package com.amaury.pointage.core.location

import com.amaury.pointage.core.time.WorkEvent

/**
 * Contexte GPS associé à un pointage réel.
 *
 * L'événement source reste intact : ce résultat ne corrige ni son heure, ni son type,
 * ni sa source. Il indique uniquement les zones dont la présence GPS couvre l'instant.
 */
data class WorkEventLocationContext(
    val event: WorkEvent,
    val matchingZoneIds: List<String>
) {
    val hasGpsContext: Boolean get() = matchingZoneIds.isNotEmpty()
}

object WorkEventLocationContextEngine {
    fun evaluate(
        events: List<WorkEvent>,
        zones: List<WorkplaceZone>,
        presenceFacts: List<ZonePresenceFact>
    ): List<WorkEventLocationContext> {
        if (events.isEmpty()) return emptyList()

        val enabledZoneIds = zones.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
        val factsByZone = presenceFacts
            .asSequence()
            .filter { it.zoneId in enabledZoneIds }
            .groupBy { it.zoneId }

        return events
            .sortedWith(compareBy<WorkEvent> { it.occurredAtMs }.thenBy { it.id })
            .map { event ->
                val matching = factsByZone
                    .asSequence()
                    .filter { (_, facts) -> facts.any { it.contains(event.occurredAtMs) } }
                    .map { it.key }
                    .sorted()
                    .toList()

                WorkEventLocationContext(event = event, matchingZoneIds = matching)
            }
    }

    private fun ZonePresenceFact.contains(atMs: Long): Boolean =
        atMs >= enteredAtMs && (exitedAtMs == null || atMs <= exitedAtMs)
}
