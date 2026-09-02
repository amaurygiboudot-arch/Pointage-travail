package com.amaury.pointage.core

import com.amaury.pointage.core.location.WorkEventCoherenceEngine
import com.amaury.pointage.core.location.WorkEventCoherenceFinding
import com.amaury.pointage.core.location.WorkEventLocationContext
import com.amaury.pointage.core.location.WorkEventLocationContextEngine
import com.amaury.pointage.core.location.WorkplaceZone
import com.amaury.pointage.core.location.ZoneMovementEngine
import com.amaury.pointage.core.location.ZoneMovementSequence
import com.amaury.pointage.core.location.ZonePresenceFact
import com.amaury.pointage.core.time.PresenceInterval
import com.amaury.pointage.core.time.WorkEvent
import com.amaury.pointage.core.time.WorkTimelineEngine
import com.amaury.pointage.core.time.WorkTimelineInput
import com.amaury.pointage.core.time.WorkTimelineResult

data class WorkFactsInput(
    val events: List<WorkEvent>,
    val zones: List<WorkplaceZone> = emptyList(),
    val presenceFacts: List<ZonePresenceFact> = emptyList()
)

data class WorkFactsResult(
    val timeline: WorkTimelineResult,
    val eventContexts: List<WorkEventLocationContext>,
    val movementSequences: List<ZoneMovementSequence>,
    val coherenceFindings: List<WorkEventCoherenceFinding>
)

/**
 * Point d'assemblage du nouveau moteur de faits HoraTrack.
 *
 * Il consolide ce qui a réellement été observé ou pointé, mais n'applique aucune règle
 * juridique, conventionnelle, salariale ou d'arrondi. Les événements restent la source
 * canonique du temps de travail ; le GPS fournit uniquement du contexte de présence.
 */
object WorkFactsEngine {
    fun evaluate(input: WorkFactsInput): WorkFactsResult {
        val timeline = WorkTimelineEngine.evaluate(
            WorkTimelineInput(
                events = input.events,
                presence = input.presenceFacts.mapNotNull { fact ->
                    val end = fact.exitedAtMs ?: return@mapNotNull null
                    PresenceInterval(
                        placeId = fact.zoneId,
                        startMs = fact.enteredAtMs,
                        endMs = end
                    )
                }
            )
        )

        val contexts = WorkEventLocationContextEngine.evaluate(
            events = input.events,
            zones = input.zones,
            presenceFacts = input.presenceFacts
        )

        return WorkFactsResult(
            timeline = timeline,
            eventContexts = contexts,
            movementSequences = ZoneMovementEngine.evaluate(input.zones, input.presenceFacts),
            coherenceFindings = WorkEventCoherenceEngine.evaluate(contexts)
        )
    }
}
