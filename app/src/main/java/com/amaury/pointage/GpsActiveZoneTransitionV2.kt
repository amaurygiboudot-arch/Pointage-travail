package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsTransitionV2

/**
 * Transition pure de la présence dans les zones Android.
 *
 * L'ambiguïté d'une entrée est un état durable : tant qu'elle n'est pas résolue, la sortie
 * d'une des zones doit réévaluer les zones encore actives au lieu d'oublier l'arrivée.
 */
internal object GpsActiveZoneTransitionV2 {
    sealed class Action {
        object None : Action()
        data class ResolveEntry(val zoneIds: List<String>) : Action()
        data class ResolveExit(val zoneIds: List<String>) : Action()
    }

    data class Plan(
        val activeZoneIds: Set<String>,
        val pendingExitZoneIds: Set<String>,
        val entryResolutionPending: Boolean,
        val action: Action
    )

    fun needsDeferredEntryResolution(plan: Plan): Boolean =
        plan.entryResolutionPending && plan.action == Action.None

    fun plan(
        activeZoneIds: Set<String>,
        triggeredZoneIds: List<String>,
        transition: GpsTransitionV2,
        entryResolutionPending: Boolean,
        pendingExitZoneIds: Set<String> = emptySet()
    ): Plan {
        val active = activeZoneIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .toMutableSet()
        val triggered = triggeredZoneIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        val pendingExits = pendingExitZoneIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .toMutableSet()

        return when (transition) {
            GpsTransitionV2.ENTER -> {
                val wasOutside = active.isEmpty()
                active.addAll(triggered)
                pendingExits.removeAll(triggered.toSet())
                Plan(
                    activeZoneIds = active,
                    pendingExitZoneIds = pendingExits,
                    entryResolutionPending = entryResolutionPending,
                    action = if (wasOutside && active.isNotEmpty()) {
                        Action.ResolveEntry(triggered)
                    } else {
                        Action.None
                    }
                )
            }

            GpsTransitionV2.EXIT -> {
                // Un callback EXIT canonique ne prouve pas à lui seul que la zone était active.
                // Les doublons/stales Android ne doivent jamais polluer l'arbitrage final.
                val actuallyExited = triggered.filter { it in active }.toSet()
                if (actuallyExited.isEmpty()) {
                    Plan(
                        activeZoneIds = active,
                        pendingExitZoneIds = pendingExits,
                        entryResolutionPending = entryResolutionPending,
                        action = Action.None
                    )
                } else {
                    active.removeAll(actuallyExited)
                    pendingExits.addAll(actuallyExited)
                    when {
                        entryResolutionPending && active.isNotEmpty() -> Plan(
                            activeZoneIds = active,
                            pendingExitZoneIds = pendingExits,
                            entryResolutionPending = true,
                            action = Action.ResolveEntry(active.sorted())
                        )
                        entryResolutionPending -> Plan(
                            activeZoneIds = active,
                            pendingExitZoneIds = emptySet(),
                            entryResolutionPending = false,
                            action = Action.None
                        )
                        active.isEmpty() && pendingExits.isNotEmpty() -> Plan(
                            activeZoneIds = active,
                            pendingExitZoneIds = emptySet(),
                            entryResolutionPending = false,
                            action = Action.ResolveExit(pendingExits.sorted())
                        )
                        else -> Plan(
                            activeZoneIds = active,
                            pendingExitZoneIds = pendingExits,
                            entryResolutionPending = false,
                            action = Action.None
                        )
                    }
                }
            }
        }
    }
}

internal object GpsPresenceStateKeysV2 {
    const val ACTIVE_ZONES = "active_zones"
    const val ENTRY_RESOLUTION_PENDING = "entry_resolution_pending"
    const val ENTRY_RESOLUTION_TOKEN = "entry_resolution_token"
    const val PENDING_EXIT_ZONES = "pending_exit_zones"
    val EPHEMERAL_KEYS = setOf(
        ACTIVE_ZONES,
        ENTRY_RESOLUTION_PENDING,
        ENTRY_RESOLUTION_TOKEN,
        PENDING_EXIT_ZONES
    )

    fun isTransferablePreferenceKey(preferenceFileName: String, key: String): Boolean =
        preferenceFileName != "gps_settings" || key !in EPHEMERAL_KEYS

    fun isCurrentEntryResolution(
        expectedToken: String,
        entryResolutionPending: Boolean,
        storedToken: String?
    ): Boolean = entryResolutionPending && expectedToken == storedToken
}
