package com.amaury.pointage.core.rights

import com.amaury.pointage.core.WorkFactsResult
import com.amaury.pointage.core.time.WorkEvent

/**
 * Entrée factuelle du futur moteur de droits.
 *
 * Ce modèle ne contient volontairement aucune règle juridique, conventionnelle,
 * contractuelle ou salariale. Il transporte uniquement des faits déjà établis.
 */
data class WorkRightsInput(
    val events: List<WorkEvent>,
    val firstEntryMs: Long?,
    val lastExitMs: Long?,
    val workedMs: Long,
    val pausedMs: Long,
    val presenceMs: Long,
    val warnings: List<String>,
    val coherenceMessages: List<String>
)

object WorkRightsInputBuilder {
    fun from(facts: WorkFactsResult): WorkRightsInput = WorkRightsInput(
        events = facts.timeline.orderedEvents,
        firstEntryMs = facts.timeline.firstEntryMs,
        lastExitMs = facts.timeline.lastExitMs,
        workedMs = facts.timeline.workedMs,
        pausedMs = facts.timeline.pausedMs,
        presenceMs = facts.timeline.presenceMs,
        warnings = facts.timeline.warnings,
        coherenceMessages = facts.coherenceFindings.map { it.message }
    )
}
