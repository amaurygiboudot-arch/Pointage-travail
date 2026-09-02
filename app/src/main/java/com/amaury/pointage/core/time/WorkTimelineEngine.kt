package com.amaury.pointage.core.time

/**
 * Nouveau coeur temps HoraTrack.
 *
 * Responsabilite volontairement limitee : reconstruire les faits temporels reels.
 * Aucune regle juridique, conventionnelle, de paie ou d'arrondi n'est appliquee ici.
 * Les donnees GPS restent des observations de presence et ne fabriquent jamais du travail.
 */
object WorkTimelineEngine {
    fun evaluate(input: WorkTimelineInput): WorkTimelineResult {
        val events = input.events.sortedWith(compareBy<WorkEvent> { it.occurredAtMs }.thenBy { it.id })
        val warnings = mutableListOf<String>()

        var firstEntryMs: Long? = null
        var lastExitMs: Long? = null
        var activeStartMs: Long? = null
        var pauseStartMs: Long? = null
        var workedMs = 0L
        var pausedMs = 0L

        events.forEach { event ->
            when (event.type) {
                WorkEventType.ENTRY -> {
                    if (activeStartMs != null) {
                        warnings += "Entrée ignorée : une période de travail est déjà ouverte"
                    } else {
                        firstEntryMs = firstEntryMs ?: event.occurredAtMs
                        activeStartMs = event.occurredAtMs
                        pauseStartMs = null
                    }
                }

                WorkEventType.PAUSE_START -> {
                    val active = activeStartMs
                    if (active == null) {
                        warnings += "Début de pause ignoré : aucune période de travail ouverte"
                    } else if (pauseStartMs != null) {
                        warnings += "Début de pause ignoré : une pause est déjà ouverte"
                    } else {
                        workedMs += (event.occurredAtMs - active).coerceAtLeast(0L)
                        pauseStartMs = event.occurredAtMs
                        activeStartMs = null
                    }
                }

                WorkEventType.PAUSE_END -> {
                    val pauseStart = pauseStartMs
                    if (pauseStart == null) {
                        warnings += "Fin de pause ignorée : aucune pause ouverte"
                    } else {
                        pausedMs += (event.occurredAtMs - pauseStart).coerceAtLeast(0L)
                        pauseStartMs = null
                        activeStartMs = event.occurredAtMs
                    }
                }

                WorkEventType.EXIT -> {
                    when {
                        pauseStartMs != null -> {
                            pausedMs += (event.occurredAtMs - pauseStartMs!!).coerceAtLeast(0L)
                            pauseStartMs = null
                            lastExitMs = event.occurredAtMs
                        }
                        activeStartMs != null -> {
                            workedMs += (event.occurredAtMs - activeStartMs!!).coerceAtLeast(0L)
                            activeStartMs = null
                            lastExitMs = event.occurredAtMs
                        }
                        else -> warnings += "Sortie ignorée : aucune période de travail ouverte"
                    }
                }
            }
        }

        if (activeStartMs != null || pauseStartMs != null) {
            warnings += "Journée incomplète : aucune heure de sortie n'a été inventée"
        }

        val presenceMs = input.presence.sumOf { it.durationMs }

        return WorkTimelineResult(
            firstEntryMs = firstEntryMs,
            lastExitMs = lastExitMs,
            workedMs = workedMs,
            pausedMs = pausedMs,
            presenceMs = presenceMs,
            orderedEvents = events,
            warnings = warnings
        )
    }
}
