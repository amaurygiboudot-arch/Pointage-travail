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

internal enum class ObjectiveDeliveryNpcPriority(val label: String) {
    CUSTOMER("Relation client"),
    HANDOFF("Transmission"),
    TEAM("Équipe"),
    SUPPLY("Approvisionnement"),
    QUALITY("Qualité"),
    DELIVERY("Livraison"),
    SAFETY("Sécurité"),
    CASH("Trésorerie")
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

    fun priority(scene: ObjectiveDeliverySceneModel, personIndex: Int): ObjectiveDeliveryNpcPriority {
        if (personIndex !in scene.people.indices) return chapterPriority(scene.chapter)
        if (personIndex == directorIndex(scene)) return chapterPriority(scene.chapter)

        val role = scene.people[personIndex].role.lowercase(Locale.ROOT)
        return when {
            scene.chapter == 9 -> ObjectiveDeliveryNpcPriority.SAFETY
            scene.chapter == 10 -> ObjectiveDeliveryNpcPriority.CASH
            role.contains("client") -> if (scene.chapter >= 7) {
                ObjectiveDeliveryNpcPriority.DELIVERY
            } else {
                ObjectiveDeliveryNpcPriority.CUSTOMER
            }
            scene.chapter == 3 && (role.contains("commerce") || role.contains("vente")) ->
                ObjectiveDeliveryNpcPriority.HANDOFF
            role.contains("approvisionnement") || role.contains("fournisseur") ->
                ObjectiveDeliveryNpcPriority.SUPPLY
            scene.chapter == 6 && (role.contains("production") || role.contains("qualité") ||
                role.contains("technique") || role.contains("atelier")) ->
                ObjectiveDeliveryNpcPriority.QUALITY
            scene.chapter >= 7 && role.contains("logistique") ->
                ObjectiveDeliveryNpcPriority.DELIVERY
            (scene.chapter == 4 || scene.chapter == 8) -> ObjectiveDeliveryNpcPriority.TEAM
            else -> chapterPriority(scene.chapter)
        }
    }

    fun priorityLabel(scene: ObjectiveDeliverySceneModel, personIndex: Int): String {
        val base = priority(scene, personIndex).label
        val urgent = scene.people.getOrNull(personIndex)?.mood == ObjectiveDeliverySceneMood.ANGRY
        return if (urgent) "URGENT • $base" else base
    }

    fun initiative(scene: ObjectiveDeliverySceneModel, personIndex: Int): String {
        val person = scene.people.getOrNull(personIndex)
            ?: return "Attend une information fiable avant d’agir."
        if (personIndex == directorIndex(scene)) {
            return "Tu arbitres les priorités et donnes le cap à l’équipe."
        }
        if (person.mood == ObjectiveDeliverySceneMood.ANGRY) {
            return "Demande un échange avant de reprendre sa mission."
        }
        return when (priority(scene, personIndex)) {
            ObjectiveDeliveryNpcPriority.CUSTOMER ->
                "Clarifie le besoin et évite une promesse floue."
            ObjectiveDeliveryNpcPriority.HANDOFF ->
                "Vérifie que les informations utiles suivent la commande."
            ObjectiveDeliveryNpcPriority.TEAM ->
                "Cherche le bon relais et protège l’équilibre de charge."
            ObjectiveDeliveryNpcPriority.SUPPLY ->
                "Surveille le stock et les délais fournisseur."
            ObjectiveDeliveryNpcPriority.QUALITY ->
                "Contrôle la conformité avant de laisser avancer la commande."
            ObjectiveDeliveryNpcPriority.DELIVERY ->
                "Prépare le passage suivant jusqu’au client."
            ObjectiveDeliveryNpcPriority.SAFETY ->
                "Sécurise le poste avant de poursuivre."
            ObjectiveDeliveryNpcPriority.CASH ->
                "Protège les échéances sans oublier les engagements client."
        }
    }

    private fun chapterPriority(chapter: Int): ObjectiveDeliveryNpcPriority =
        when (chapter.coerceIn(1, 10)) {
            1, 2 -> ObjectiveDeliveryNpcPriority.CUSTOMER
            3 -> ObjectiveDeliveryNpcPriority.HANDOFF
            4, 8 -> ObjectiveDeliveryNpcPriority.TEAM
            5 -> ObjectiveDeliveryNpcPriority.SUPPLY
            6 -> ObjectiveDeliveryNpcPriority.QUALITY
            7 -> ObjectiveDeliveryNpcPriority.DELIVERY
            9 -> ObjectiveDeliveryNpcPriority.SAFETY
            else -> ObjectiveDeliveryNpcPriority.CASH
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
        val collaboration = ObjectiveDeliveryNpcStep(
            destinationZone = partnerZone,
            action = ObjectiveDeliveryNpcAction.COLLABORATE,
            dwellSeconds = 2.4f,
            partnerIndex = partner
        )
        val work = ObjectiveDeliveryNpcStep(home, ObjectiveDeliveryNpcAction.WORK, 3.5f)
        when (priority(scene, personIndex)) {
            ObjectiveDeliveryNpcPriority.HANDOFF,
            ObjectiveDeliveryNpcPriority.TEAM -> {
                routine += collaboration
                routine += work
            }
            else -> {
                routine += work
                routine += collaboration
            }
        }
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
