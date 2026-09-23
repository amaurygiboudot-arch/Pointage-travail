package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsTransitionV2

/**
 * État de présence dédié aux zones PAUSE.
 *
 * Il est volontairement séparé des zones POSTE : une zone de pause peut chevaucher une zone de
 * travail sans jamais fabriquer une entrée ou une sortie de journée.
 */
internal object GpsPauseZoneTransitionV2 {
    sealed class Action {
        object None : Action()
        data class PauseStart(val zoneIds: List<String>) : Action()
        data class PauseEnd(val zoneIds: List<String>) : Action()
    }

    data class Plan(
        val activePauseZoneIds: Set<String>,
        val action: Action
    )

    fun plan(
        activePauseZoneIds: Set<String>,
        triggeredZoneIds: List<String>,
        transition: GpsTransitionV2
    ): Plan {
        val active = activePauseZoneIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .toMutableSet()
        val triggered = triggeredZoneIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()

        if (triggered.isEmpty()) return Plan(active, Action.None)

        return when (transition) {
            GpsTransitionV2.ENTER -> {
                val wasOutside = active.isEmpty()
                active.addAll(triggered)
                Plan(
                    activePauseZoneIds = active,
                    action = if (wasOutside && active.isNotEmpty()) {
                        Action.PauseStart(triggered)
                    } else {
                        Action.None
                    }
                )
            }

            GpsTransitionV2.EXIT -> {
                val actuallyExited = triggered.filter { it in active }.toSet()
                if (actuallyExited.isEmpty()) {
                    Plan(active, Action.None)
                } else {
                    active.removeAll(actuallyExited)
                    Plan(
                        activePauseZoneIds = active,
                        action = if (active.isEmpty()) {
                            Action.PauseEnd(actuallyExited.sorted())
                        } else {
                            Action.None
                        }
                    )
                }
            }
        }
    }
}
