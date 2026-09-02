package com.amaury.pointage.core.location

/**
 * Politique de stabilisation et de qualité des transitions GPS.
 *
 * Une entrée ou une sortie doit être observée plusieurs fois de suite avant d'être
 * confirmée. Une observation trop imprécise est ignorée : elle ne peut donc ni ouvrir,
 * ni fermer, ni confirmer une présence.
 *
 * Le fait conserve l'heure de la première observation fiable de la transition confirmée
 * afin de ne pas déplacer artificiellement l'heure réelle.
 */
data class ZonePresencePolicy(
    val confirmationSamples: Int = 2,
    val maxAccuracyMeters: Float = 50f
) {
    init {
        require(confirmationSamples >= 1) { "confirmationSamples must be >= 1" }
        require(maxAccuracyMeters > 0f) { "maxAccuracyMeters must be > 0" }
    }

    fun accepts(observation: LocationObservation): Boolean =
        observation.accuracyMeters == null || observation.accuracyMeters <= maxAccuracyMeters
}

/**
 * Transforme une suite d'observations GPS fiables en faits d'entrée/sortie de zones.
 *
 * Ce moteur ne crée aucun pointage de travail. Il décrit seulement où le téléphone
 * a été observé et à quel moment les transitions de zone ont eu lieu.
 *
 * La première observation fiable établit l'état initial d'une zone. Ensuite, toute
 * transition doit être confirmée par plusieurs observations fiables consécutives afin
 * d'éviter les rebonds entrée/sortie provoqués par l'imprécision GPS près d'une frontière.
 */
object ZonePresenceEngine {
    private data class ZoneState(
        var inside: Boolean,
        var openEnteredAtMs: Long? = null,
        var pendingTargetInside: Boolean? = null,
        var pendingFirstSeenAtMs: Long? = null,
        var pendingCount: Int = 0
    )

    fun evaluate(
        zones: List<WorkplaceZone>,
        observations: List<LocationObservation>,
        policy: ZonePresencePolicy = ZonePresencePolicy()
    ): List<ZonePresenceFact> {
        if (zones.isEmpty() || observations.isEmpty()) return emptyList()

        val enabledZones = zones.filter { it.enabled }
        if (enabledZones.isEmpty()) return emptyList()

        val ordered = observations
            .asSequence()
            .filter(policy::accepts)
            .sortedBy { it.occurredAtMs }
            .toList()
        if (ordered.isEmpty()) return emptyList()

        val states = linkedMapOf<String, ZoneState>()
        val facts = mutableListOf<ZonePresenceFact>()

        ordered.forEach { observation ->
            enabledZones.forEach { zone ->
                val observedInside = WorkplaceGeometryEngine.contains(zone, observation.point)
                val state = states[zone.id]

                if (state == null) {
                    states[zone.id] = ZoneState(
                        inside = observedInside,
                        openEnteredAtMs = observation.occurredAtMs.takeIf { observedInside }
                    )
                    return@forEach
                }

                if (observedInside == state.inside) {
                    state.pendingTargetInside = null
                    state.pendingFirstSeenAtMs = null
                    state.pendingCount = 0
                    return@forEach
                }

                if (state.pendingTargetInside != observedInside) {
                    state.pendingTargetInside = observedInside
                    state.pendingFirstSeenAtMs = observation.occurredAtMs
                    state.pendingCount = 1
                } else {
                    state.pendingCount += 1
                }

                if (state.pendingCount < policy.confirmationSamples) return@forEach

                val transitionAt = state.pendingFirstSeenAtMs ?: observation.occurredAtMs
                if (observedInside) {
                    state.openEnteredAtMs = transitionAt
                } else {
                    val enteredAt = state.openEnteredAtMs
                    if (enteredAt != null) {
                        facts += ZonePresenceFact(
                            zoneId = zone.id,
                            enteredAtMs = enteredAt,
                            exitedAtMs = transitionAt
                        )
                    }
                    state.openEnteredAtMs = null
                }

                state.inside = observedInside
                state.pendingTargetInside = null
                state.pendingFirstSeenAtMs = null
                state.pendingCount = 0
            }
        }

        states.forEach { (zoneId, state) ->
            val enteredAt = state.openEnteredAtMs
            if (state.inside && enteredAt != null) {
                facts += ZonePresenceFact(
                    zoneId = zoneId,
                    enteredAtMs = enteredAt,
                    exitedAtMs = null
                )
            }
        }

        return facts.sortedWith(
            compareBy<ZonePresenceFact> { it.enteredAtMs }
                .thenBy { it.zoneId }
                .thenBy { it.exitedAtMs ?: Long.MAX_VALUE }
        )
    }
}
