package com.amaury.pointage.core.location

/**
 * Transforme une suite d'observations GPS en faits d'entrée/sortie de zones.
 *
 * Ce moteur ne crée aucun pointage de travail. Il décrit seulement où le téléphone
 * a été observé et à quel moment les transitions de zone ont eu lieu.
 */
object ZonePresenceEngine {
    fun evaluate(
        zones: List<WorkplaceZone>,
        observations: List<LocationObservation>
    ): List<ZonePresenceFact> {
        if (zones.isEmpty() || observations.isEmpty()) return emptyList()

        val enabledZones = zones.filter { it.enabled }
        if (enabledZones.isEmpty()) return emptyList()

        val ordered = observations.sortedBy { it.occurredAtMs }
        val openEntries = linkedMapOf<String, Long>()
        val facts = mutableListOf<ZonePresenceFact>()

        ordered.forEach { observation ->
            val current = WorkplaceGeometryEngine.matchingZoneIds(enabledZones, observation.point)
            val previouslyOpen = openEntries.keys.toSet()

            (previouslyOpen - current).forEach { zoneId ->
                val enteredAt = openEntries.remove(zoneId) ?: return@forEach
                facts += ZonePresenceFact(
                    zoneId = zoneId,
                    enteredAtMs = enteredAt,
                    exitedAtMs = observation.occurredAtMs
                )
            }

            (current - previouslyOpen).forEach { zoneId ->
                openEntries[zoneId] = observation.occurredAtMs
            }
        }

        openEntries.forEach { (zoneId, enteredAt) ->
            facts += ZonePresenceFact(
                zoneId = zoneId,
                enteredAtMs = enteredAt,
                exitedAtMs = null
            )
        }

        return facts.sortedWith(
            compareBy<ZonePresenceFact> { it.enteredAtMs }
                .thenBy { it.zoneId }
                .thenBy { it.exitedAtMs ?: Long.MAX_VALUE }
        )
    }
}
