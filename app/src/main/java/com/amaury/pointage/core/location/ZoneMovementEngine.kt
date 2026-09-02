package com.amaury.pointage.core.location

/**
 * Une étape de déplacement est un fait géographique ordonné.
 * Elle ne constitue jamais à elle seule un pointage ou du temps de travail.
 */
data class ZoneMovementStep(
    val zoneId: String,
    val kind: WorkplaceZoneKind,
    val enteredAtMs: Long,
    val exitedAtMs: Long?
)

data class ZoneMovementSequence(
    val steps: List<ZoneMovementStep>
) {
    init {
        require(steps.isNotEmpty()) { "a movement sequence requires at least one step" }
    }

    val startedAtMs: Long get() = steps.first().enteredAtMs
    val endedAtMs: Long? get() = steps.last().exitedAtMs
}

/**
 * Ordonne les faits de présence déjà stabilisés en séquences de déplacement.
 *
 * Le moteur ne déduit aucune activité professionnelle : SITE -> PARKING -> BUILDING
 * reste uniquement un contexte géographique exploitable plus tard par d'autres règles.
 */
object ZoneMovementEngine {
    fun evaluate(
        zones: List<WorkplaceZone>,
        presenceFacts: List<ZonePresenceFact>
    ): List<ZoneMovementSequence> {
        if (zones.isEmpty() || presenceFacts.isEmpty()) return emptyList()

        val zonesById = zones.filter { it.enabled }.associateBy { it.id }
        val steps = presenceFacts
            .mapNotNull { fact ->
                val zone = zonesById[fact.zoneId] ?: return@mapNotNull null
                ZoneMovementStep(
                    zoneId = fact.zoneId,
                    kind = zone.kind,
                    enteredAtMs = fact.enteredAtMs,
                    exitedAtMs = fact.exitedAtMs
                )
            }
            .sortedWith(compareBy<ZoneMovementStep> { it.enteredAtMs }.thenBy { it.zoneId })

        if (steps.isEmpty()) return emptyList()

        val sequences = mutableListOf<MutableList<ZoneMovementStep>>()
        steps.forEach { step ->
            val current = sequences.lastOrNull()
            if (current == null || startsNewSequence(current, step, zonesById)) {
                sequences += mutableListOf(step)
            } else {
                current += step
            }
        }

        return sequences.map(::ZoneMovementSequence)
    }

    private fun startsNewSequence(
        current: List<ZoneMovementStep>,
        next: ZoneMovementStep,
        zonesById: Map<String, WorkplaceZone>
    ): Boolean {
        val last = current.last()
        if (last.exitedAtMs == null) return false

        // Une relation parent/enfant garde naturellement les zones imbriquées dans
        // la même séquence, même lorsque leurs intervalles se chevauchent.
        if (isRelated(last.zoneId, next.zoneId, zonesById)) return false

        // Si le nouveau fait commence avant la fin du précédent, les présences se
        // chevauchent : elles appartiennent au même contexte de déplacement.
        if (next.enteredAtMs <= last.exitedAtMs) return false

        // Sans continuité géographique démontrée, on ouvre une nouvelle séquence au
        // lieu d'inventer un trajet entre deux zones indépendantes.
        return true
    }

    private fun isRelated(
        firstId: String,
        secondId: String,
        zonesById: Map<String, WorkplaceZone>
    ): Boolean = isAncestor(firstId, secondId, zonesById) ||
        isAncestor(secondId, firstId, zonesById)

    private fun isAncestor(
        candidateAncestorId: String,
        zoneId: String,
        zonesById: Map<String, WorkplaceZone>
    ): Boolean {
        val visited = mutableSetOf<String>()
        var currentId: String? = zoneId
        while (currentId != null && visited.add(currentId)) {
            val parentId = zonesById[currentId]?.parentZoneId ?: return false
            if (parentId == candidateAncestorId) return true
            currentId = parentId
        }
        return false
    }
}
