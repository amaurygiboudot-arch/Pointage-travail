package com.amaury.pointage

import java.util.Locale

internal enum class ObjectiveDeliveryNpcAction {
    TRAVEL_TO_TASK,
    WORK,
    TRAVEL_TO_COLLEAGUE,
    COLLABORATE,
    WAIT,
    SEEK_DIRECTION
}

internal data class ObjectiveDeliveryNpcStep(
    val destinationZone: Int,
    val action: ObjectiveDeliveryNpcAction,
    val dwellSeconds: Float,
    val partnerIndex: Int? = null
)

/**
 * Local deterministic decisions for scene characters. The brain only sees each
 * character's role and task, the current chapter, and an explicit story outcome.
 */
internal object ObjectiveDeliveryNpcBrain {
    fun directorIndex(scene: ObjectiveDeliverySceneModel): Int =
        scene.people.indexOfFirst { person ->
            person.name.equals("Direction", ignoreCase = true) ||
                person.role.contains("direction", ignoreCase = true)
        }.let { if (it >= 0) it else 0 }

    fun activeZone(chapter: Int): Int = when (chapter.coerceIn(1, 10)) {
        1, 2 -> 0
        3 -> 1
        4, 8 -> 4
        5 -> 2
        6 -> 3
        7, 9 -> 5
        else -> 4
    }

    fun homeZone(scene: ObjectiveDeliverySceneModel, personIndex: Int): Int {
        if (personIndex !in scene.people.indices) return activeZone(scene.chapter)
        if (personIndex == directorIndex(scene)) return activeZone(scene.chapter)

        val role = scene.people[personIndex].role.lowercase(Locale.ROOT)
        return when {
            role.contains("client") && scene.chapter >= 7 -> 5
            role.contains("client") || role.contains("commerce") || role.contains("vente") -> 0
            role.contains("approvisionnement") || role.contains("fournisseur") -> 2
            role.contains("logistique") -> 5
            role.contains("production") || role.contains("qualité") ||
                role.contains("technique") || role.contains("atelier") -> 3
            role.contains("sécurité") -> 5
            role.contains("équipe") || role.contains("manager") ||
                role.contains("ressources humaines") -> 4
            else -> activeZone(scene.chapter)
        }
    }

    fun collaboratorIndex(scene: ObjectiveDeliverySceneModel, personIndex: Int): Int {
        if (personIndex !in scene.people.indices) return directorIndex(scene)
        val role = scene.people[personIndex].role.lowercase(Locale.ROOT)
        val preferences: List<(String) -> Boolean> = when {
            role.contains("client") -> listOf(
                { value: String -> value.contains("commerce") },
                { value: String -> value.contains("service client") }
            )
            role.contains("commerce") || role.contains("vente") -> listOf(
                { value: String -> value.contains("production") },
                { value: String -> value.contains("logistique") }
            )
            role.contains("production") || role.contains("technique") -> listOf(
                { value: String -> value.contains("qualité") },
                { value: String -> value.contains("logistique") }
            )
            role.contains("qualité") -> listOf(
                { value: String -> value.contains("production") },
                { value: String -> value.contains("logistique") }
            )
            role.contains("logistique") -> listOf(
                { value: String -> value.contains("production") },
                { value: String -> value.contains("client") }
            )
            role.contains("approvisionnement") || role.contains("fournisseur") -> listOf(
                { value: String -> value.contains("logistique") },
                { value: String -> value.contains("production") }
            )
            role.contains("sécurité") -> listOf(
                { value: String -> value.contains("technique") },
                { value: String -> value.contains("direction") }
            )
            else -> listOf(
                { value: String -> value.contains("direction") },
                { value: String -> value.contains("manager") }
            )
        }

        for (matches in preferences) {
            for (index in scene.people.indices) {
                if (index != personIndex &&
                    matches(scene.people[index].role.lowercase(Locale.ROOT))
                ) {
                    return index
                }
            }
        }
        return scene.people.indices.firstOrNull { it != personIndex }
            ?: directorIndex(scene)
    }

    fun speechLine(
        scene: ObjectiveDeliverySceneModel,
        personIndex: Int,
        step: ObjectiveDeliveryNpcStep,
        isMoving: Boolean
    ): String? {
        if (personIndex !in scene.people.indices || personIndex == directorIndex(scene)) {
            return null
        }

        val person = scene.people[personIndex]
        val partnerName = step.partnerIndex?.let { scene.people.getOrNull(it)?.name } ?: "un collègue"
        return when (step.action) {
            ObjectiveDeliveryNpcAction.SEEK_DIRECTION ->
                if (person.mood == ObjectiveDeliverySceneMood.ANGRY) {
                    "Je voudrais qu’on en parle."
                } else {
                    "Peux-tu m’aider un instant ?"
                }
            ObjectiveDeliveryNpcAction.WORK ->
                if (isMoving) "Je rejoins mon poste !" else "Je m’occupe de ma mission 🙂"
            ObjectiveDeliveryNpcAction.COLLABORATE ->
                if (isMoving) "Je vais voir $partnerName !" else "On fait le point ensemble 🙂"
            ObjectiveDeliveryNpcAction.WAIT ->
                if (isMoving) "Je rejoins le point de réception." else "J’attends les informations avant d’avancer."
            ObjectiveDeliveryNpcAction.TRAVEL_TO_TASK -> "Je rejoins mon poste !"
            ObjectiveDeliveryNpcAction.TRAVEL_TO_COLLEAGUE -> "Je vais voir $partnerName !"
        }
    }

    fun routine(
        scene: ObjectiveDeliverySceneModel,
        personIndex: Int
    ): List<ObjectiveDeliveryNpcStep> {
        if (personIndex !in scene.people.indices || personIndex == directorIndex(scene)) {
            return emptyList()
        }

        val person = scene.people[personIndex]
        val role = person.role.lowercase(Locale.ROOT)
        val home = homeZone(scene, personIndex)
        val partner = collaboratorIndex(scene, personIndex)
        val partnerZone = homeZone(scene, partner)
        val director = directorIndex(scene)
        val needsSupport = personIndex == scene.troubledPersonIndex &&
            person.mood == ObjectiveDeliverySceneMood.ANGRY

        val nextHandoffZone = when {
            role.contains("logistique") || role.contains("production") ||
                role.contains("qualité") || role.contains("technique") -> 5
            role.contains("client") && scene.chapter >= 7 -> 5
            role.contains("client") -> 0
            else -> activeZone(scene.chapter)
        }
        val waitsForInformation = role.contains("client") ||
            role.contains("approvisionnement") || role.contains("fournisseur")

        val routine = mutableListOf<ObjectiveDeliveryNpcStep>()
        if (needsSupport) {
            routine += ObjectiveDeliveryNpcStep(
                destinationZone = activeZone(scene.chapter),
                action = ObjectiveDeliveryNpcAction.SEEK_DIRECTION,
                dwellSeconds = 2.8f,
                partnerIndex = director
            )
        }
        routine += ObjectiveDeliveryNpcStep(home, ObjectiveDeliveryNpcAction.WORK, 3.5f)
        routine += ObjectiveDeliveryNpcStep(
            destinationZone = partnerZone,
            action = ObjectiveDeliveryNpcAction.COLLABORATE,
            dwellSeconds = 2.4f,
            partnerIndex = partner
        )
        routine += ObjectiveDeliveryNpcStep(
            destinationZone = nextHandoffZone,
            action = if (waitsForInformation) {
                ObjectiveDeliveryNpcAction.WAIT
            } else {
                ObjectiveDeliveryNpcAction.WORK
            },
            dwellSeconds = 2.0f
        )
        routine += ObjectiveDeliveryNpcStep(home, ObjectiveDeliveryNpcAction.WORK, 3.2f)
        return routine
    }
}
