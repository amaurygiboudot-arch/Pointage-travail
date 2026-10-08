package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsTransitionV2

/** EXIT sûr d'un seul site Travail alors que l'ENTER initial n'avait pas été reçu. */
internal object GpsUnseenExitRecoveryPolicyV2 {
    fun shouldRecover(
        transition: GpsTransitionV2,
        previouslyActive: Set<String>,
        previouslyPendingExits: Set<String>,
        entryResolutionWasPending: Boolean,
        triggeredZoneIds: List<String>,
        registeredWorkZoneIds: List<String>,
        hasReliableOpenSession: Boolean
    ): Boolean {
        if (!hasReliableOpenSession ||
            transition != GpsTransitionV2.EXIT ||
            previouslyActive.isNotEmpty() ||
            previouslyPendingExits.isNotEmpty() ||
            entryResolutionWasPending) return false
        val trigger = triggeredZoneIds.distinct()
        val work = registeredWorkZoneIds.distinct()
        return trigger.size == 1 && work.size == 1 && trigger.single() == work.single()
    }
}
