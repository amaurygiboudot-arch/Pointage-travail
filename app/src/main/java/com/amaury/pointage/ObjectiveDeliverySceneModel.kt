package com.amaury.pointage

internal enum class ObjectiveDeliverySceneMood { FRIENDLY, ANGRY }

internal data class ObjectiveDeliveryScenePoint(val x: Float, val y: Float)

internal data class ObjectiveDeliveryScenePerson(
    val name: String,
    val role: String,
    val task: String,
    val route: List<ObjectiveDeliveryScenePoint>,
    val shirtColor: Int,
    val skinColor: Int,
    val hairColor: Int,
    val mood: ObjectiveDeliverySceneMood = ObjectiveDeliverySceneMood.FRIENDLY
)

internal data class ObjectiveDeliverySceneFixture(
    val x: Float,
    val y: Float,
    val width: Float,
    val depth: Float,
    val heightDp: Float,
    val label: String,
    val color: Int,
    val kind: Kind = Kind.BLOCK
) {
    enum class Kind { BLOCK, DESK, RACK, MACHINE, VAN, BARRIER }
}

internal data class ObjectiveDeliverySceneModel(
    val chapter: Int,
    val headline: String,
    val story: String,
    val zoneLabel: String,
    val people: List<ObjectiveDeliveryScenePerson>,
    val fixtures: List<ObjectiveDeliverySceneFixture>,
    val troubledPersonIndex: Int
)

internal object ObjectiveDeliverySceneCatalog {
    private val teal = 0xFF2D8B83.toInt()
    private val blue = 0xFF4387B5.toInt()
    private val coral = 0xFFE17B62.toInt()
    private val purple = 0xFF8B72B4.toInt()
    private val gold = 0xFFE6AF48.toInt()
    private val dark = 0xFF315460.toInt()
    private val lightSkin = 0xFFF2C9A5.toInt()
    private val warmSkin = 0xFFD99A70.toInt()
    private val deepSkin = 0xFF9B674E.toInt()
    private val darkHair = 0xFF3D302B.toInt()
    private val brownHair = 0xFF704B38.toInt()
    private val lightHair = 0xFFC9A05A.toInt()

    fun forCampaign(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliverySceneModel {
        val chapter = campaign.activeChapter.coerceIn(1, 10)
        val replay = campaign.replaySession?.takeIf { it.chapter == chapter }
        val base = forChapter(chapter, campaign.companyModel.clientName, isTroubled(campaign, replay))
        val narrative = campaignNarrative(campaign, replay)
        return base.copy(headline = narrative.first, story = narrative.second)
    }

    internal fun forChapter(
        chapter: Int,
        clientName: String = "Le client",
        isTroubled: Boolean = false
    ): ObjectiveDeliverySceneModel {
        val safeChapter = chapter.coerceIn(1, 10)
        val name = clientName.ifBlank { "Le client" }
        val people = when (safeChapter) {
            1 -> listOf(
                person(name, "Client", "Présente son besoin et sa date souhaitée.", 0.77f, 0.42f, coral, lightSkin, brownHair),
                person("Direction", "Toi • direction", "Écoute le client et choisis la suite.", 0.28f, 0.68f, teal, warmSkin, darkHair),
                person("Élise", "Commerce", "Note les informations utiles au devis.", 0.47f, 0.36f, purple, lightSkin, lightHair)
            )
            2 -> listOf(
                person(name, "Client", "Compare le prix, le contenu et le délai.", 0.76f, 0.42f, coral, lightSkin, brownHair),
                person("Direction", "Toi • direction", "Arbitre le prix et la marge.", 0.31f, 0.67f, teal, warmSkin, darkHair),
                person("Élise", "Commerce", "Présente le devis et répond aux questions.", 0.50f, 0.38f, purple, lightSkin, lightHair)
            )
            3 -> listOf(
                person("Élise", "Commerce", "Transmet le devis signé et les options.", 0.23f, 0.43f, purple, lightSkin, lightHair),
                person("Karim", "Production", "Vérifie les spécifications techniques.", 0.70f, 0.42f, blue, deepSkin, darkHair),
                person("Noah", "Logistique", "Confirme les conditions de livraison.", 0.58f, 0.72f, gold, warmSkin, brownHair),
                person("Direction", "Toi • direction", "Valide un dossier prêt à lancer.", 0.38f, 0.70f, teal, lightSkin, darkHair)
            )
            4 -> listOf(
                person("Direction", "Toi • direction", "Valide une équipe adaptée à la charge.", 0.25f, 0.70f, teal, warmSkin, darkHair),
                person("Élise", "Commerce", "Prépare et suit le dossier client.", 0.68f, 0.36f, purple, lightSkin, lightHair),
                person("Karim", "Production", "Organise la fabrication et le contrôle.", 0.65f, 0.69f, blue, deepSkin, darkHair),
                person("Noah", "Logistique", "Prépare l’expédition au bon moment.", 0.30f, 0.40f, gold, warmSkin, brownHair)
            )
            5 -> listOf(
                person("Karim", "Production", "Compte les matières nécessaires.", 0.30f, 0.67f, blue, deepSkin, darkHair),
                person("Fournisseur", "Approvisionnement", "Prépare une livraison et son délai.", 0.74f, 0.38f, coral, lightSkin, brownHair),
                person("Noah", "Logistique", "Réceptionne et range les composants.", 0.60f, 0.72f, gold, warmSkin, brownHair),
                person("Direction", "Toi • direction", "Choisit le coût et le niveau de sécurité du stock.", 0.25f, 0.38f, teal, lightSkin, darkHair)
            )
            6 -> listOf(
                person("Karim", "Production", "Fabrique la commande selon le dossier.", 0.27f, 0.67f, blue, deepSkin, darkHair),
                person("Élise", "Qualité", "Vérifie les points promis au client.", 0.70f, 0.38f, purple, lightSkin, lightHair),
                person("Noah", "Logistique", "Attend le feu vert avant l’expédition.", 0.64f, 0.70f, gold, warmSkin, brownHair),
                person("Direction", "Toi • direction", "Décide de reprendre, contrôler ou demander un accord.", 0.25f, 0.39f, teal, lightSkin, darkHair)
            )
            7 -> listOf(
                person("Noah", "Logistique", "Vérifie le colis et les documents.", 0.27f, 0.69f, gold, warmSkin, brownHair),
                person(name, "Client", "Réceptionne la commande et peut faire un retour.", 0.76f, 0.39f, coral, lightSkin, brownHair),
                person("Élise", "Service client", "Écoute et suit la demande du client.", 0.52f, 0.70f, purple, lightSkin, lightHair),
                person("Karim", "Production", "Reste disponible si une correction est nécessaire.", 0.39f, 0.38f, blue, deepSkin, darkHair)
            )
            8 -> listOf(
                person("Direction", "Toi • direction", "Équilibre les besoins de l’entreprise et de l’équipe.", 0.26f, 0.67f, teal, warmSkin, darkHair),
                person("Élise", "Commerce", "Partage les retours du terrain.", 0.69f, 0.37f, purple, lightSkin, lightHair),
                person("Karim", "Production", "Signale sa charge et ses besoins.", 0.67f, 0.69f, blue, deepSkin, darkHair),
                person("Noah", "Logistique", "Prépare la suite avec l’équipe.", 0.31f, 0.39f, gold, warmSkin, brownHair)
            )
            9 -> listOf(
                person("Inspecteur", "Visite de sécurité", "Vérifie les faits et les mesures prises.", 0.74f, 0.40f, coral, lightSkin, brownHair),
                person("Karim", "Équipe technique", "Alerte sur le poste et sécurise le travail.", 0.31f, 0.66f, blue, deepSkin, darkHair),
                person("Direction", "Toi • direction", "Répond honnêtement et organise les corrections.", 0.51f, 0.70f, teal, warmSkin, darkHair)
            )
            else -> listOf(
                person("Direction", "Toi • direction", "Protège les engagements et la trésorerie.", 0.24f, 0.68f, teal, warmSkin, darkHair),
                person("Élise", "Commerce", "Tient les clients informés des délais.", 0.72f, 0.38f, purple, lightSkin, lightHair),
                person("Karim", "Production", "Répartit la capacité entre les commandes.", 0.68f, 0.69f, blue, deepSkin, darkHair),
                person("Noah", "Logistique", "Coordonne les matières et les départs.", 0.30f, 0.39f, gold, warmSkin, brownHair)
            )
        }
        val troubledPersonIndex = when (safeChapter) {
            1, 2, 3, 7 -> 0
            4, 6, 8 -> 1
            5 -> 1
            9 -> 1
            else -> 0
        }
        val expressivePeople = people.mapIndexed { index, person ->
            person.copy(mood = if (isTroubled && index == troubledPersonIndex) {
                ObjectiveDeliverySceneMood.ANGRY
            } else {
                ObjectiveDeliverySceneMood.FRIENDLY
            })
        }
        return ObjectiveDeliverySceneModel(
            chapter = safeChapter,
            headline = defaultHeadline(safeChapter),
            story = defaultStory(safeChapter, name),
            zoneLabel = zoneLabel(safeChapter),
            people = expressivePeople,
            fixtures = fixtures(safeChapter),
            troubledPersonIndex = troubledPersonIndex
        )
    }

    private fun person(
        name: String,
        role: String,
        task: String,
        x: Float,
        y: Float,
        shirt: Int,
        skin: Int,
        hair: Int
    ) = ObjectiveDeliveryScenePerson(
        name = name,
        role = role,
        task = task,
        route = listOf(
            ObjectiveDeliveryScenePoint(x.coerceIn(0.12f, 0.88f), y.coerceIn(0.22f, 0.82f)),
            ObjectiveDeliveryScenePoint((x + 0.18f).coerceIn(0.12f, 0.88f), (y - 0.13f).coerceIn(0.22f, 0.82f)),
            ObjectiveDeliveryScenePoint((x - 0.08f).coerceIn(0.12f, 0.88f), (y + 0.10f).coerceIn(0.22f, 0.82f))
        ),
        shirtColor = shirt,
        skinColor = skin,
        hairColor = hair
    )

    private fun fixtures(chapter: Int): List<ObjectiveDeliverySceneFixture> {
        fun fixture(
            x: Float,
            y: Float,
            width: Float,
            depth: Float,
            height: Float,
            label: String,
            color: Int,
            kind: ObjectiveDeliverySceneFixture.Kind = ObjectiveDeliverySceneFixture.Kind.BLOCK
        ) = ObjectiveDeliverySceneFixture(x, y, width, depth, height, label, color, kind)
        return when (chapter) {
            1, 2 -> listOf(
                fixture(0.12f, 0.22f, 0.22f, 0.11f, 18f, "ACCUEIL", teal, ObjectiveDeliverySceneFixture.Kind.DESK),
                fixture(0.59f, 0.21f, 0.19f, 0.12f, 12f, "DEVIS", gold)
            )
            3, 4, 8 -> listOf(
                fixture(0.12f, 0.20f, 0.24f, 0.11f, 17f, "DOSSIER", purple, ObjectiveDeliverySceneFixture.Kind.DESK),
                fixture(0.59f, 0.23f, 0.24f, 0.13f, 14f, "ÉQUIPE", blue)
            )
            5 -> listOf(
                fixture(0.12f, 0.20f, 0.24f, 0.16f, 22f, "STOCK", gold, ObjectiveDeliverySceneFixture.Kind.RACK),
                fixture(0.60f, 0.22f, 0.19f, 0.13f, 12f, "MATIÈRES", coral)
            )
            6 -> listOf(
                fixture(0.12f, 0.21f, 0.23f, 0.15f, 25f, "ATELIER", blue, ObjectiveDeliverySceneFixture.Kind.MACHINE),
                fixture(0.60f, 0.21f, 0.22f, 0.14f, 14f, "CONTRÔLE", teal)
            )
            7 -> listOf(
                fixture(0.14f, 0.22f, 0.28f, 0.16f, 14f, "COMMANDE", gold),
                fixture(0.60f, 0.25f, 0.25f, 0.18f, 14f, "LIVRAISON", blue, ObjectiveDeliverySceneFixture.Kind.VAN)
            )
            9 -> listOf(
                fixture(0.13f, 0.24f, 0.29f, 0.10f, 12f, "ZONE SÉCURISÉE", coral, ObjectiveDeliverySceneFixture.Kind.BARRIER),
                fixture(0.61f, 0.23f, 0.22f, 0.14f, 20f, "POSTE", blue, ObjectiveDeliverySceneFixture.Kind.MACHINE)
            )
            else -> listOf(
                fixture(0.10f, 0.21f, 0.23f, 0.15f, 21f, "MATIÈRES", gold, ObjectiveDeliverySceneFixture.Kind.RACK),
                fixture(0.40f, 0.21f, 0.22f, 0.14f, 13f, "COMMANDES", coral),
                fixture(0.70f, 0.22f, 0.19f, 0.14f, 13f, "DÉPART", blue, ObjectiveDeliverySceneFixture.Kind.VAN)
            )
        }
    }

    private fun defaultHeadline(chapter: Int): String = when (chapter) {
        1 -> "Premier contact"
        2 -> "Le devis prend forme"
        3 -> "La commande change de mains"
        4 -> "L’équipe se met en place"
        5 -> "Les matières arrivent"
        6 -> "Fabriquer et contrôler"
        7 -> "La livraison rencontre le client"
        8 -> "Faire vivre l’équipe"
        9 -> "Protéger l’activité"
        else -> "Piloter sous pression"
    }

    private fun defaultStory(chapter: Int, client: String): String = when (chapter) {
        1 -> client + " présente son projet. Tu clarifies le besoin avant de faire une offre."
        2 -> "Tu compares le coût, le prix et le délai avant de répondre au client."
        3 -> "Le devis signé doit passer clairement du commerce à l’équipe technique."
        4 -> "Tu répartis les tâches selon les compétences, les horaires et la charge."
        5 -> "Tu choisis quoi commander, chez qui et avec quelle marge de sécurité."
        6 -> "L’équipe fabrique, contrôle les points promis, puis décide si la commande peut partir."
        7 -> "Tu prépares la livraison, vérifies la commande et écoutes le retour du client."
        8 -> "Tes décisions sur la charge, les absences et la reconnaissance ont des effets sur l’équipe."
        9 -> "Tu traites une alerte de sécurité et les demandes de vérification avec équité."
        else -> "Tu arbitres plusieurs commandes, des retards et une trésorerie limitée."
    }

    private fun zoneLabel(chapter: Int): String = when (chapter) {
        1, 2 -> "ACCUEIL CLIENT"
        3 -> "TRANSMISSION"
        4, 8 -> "ESPACE ÉQUIPE"
        5 -> "RÉSERVE"
        6 -> "ATELIER & QUALITÉ"
        7 -> "QUAI DE LIVRAISON"
        9 -> "ZONE DE SÉCURITÉ"
        else -> "ATELIER MULTI-COMMANDES"
    }

    private fun campaignNarrative(
        campaign: ObjectiveDeliveryCampaign,
        replay: ObjectiveDeliveryReplaySession?
    ): Pair<String, String> = when (campaign.activeChapter) {
        1 -> {
            val phase = replay?.chapterOne?.phase ?: campaign.phase
            val outcome = replay?.chapterOne?.outcome ?: campaign.outcome
            when (phase) {
                ObjectiveDeliveryPhase.QUALIFICATION ->
                    "Découvre le besoin" to "Le client t’explique son projet. Pose des questions avant de choisir une offre."
                ObjectiveDeliveryPhase.OFFER ->
                    "Prépare l’offre" to "Tu connais maintenant le budget et la date souhaitée. Choisis un prix et un délai adaptés."
                ObjectiveDeliveryPhase.RESULT -> when (outcome) {
                    ObjectiveDeliveryOutcome.ORDER_ACCEPTED ->
                        "Le client accepte le devis" to "La commande est gagnée. La campagne continue avec la préparation du dossier."
                    ObjectiveDeliveryOutcome.CORRECTION_REQUESTED ->
                        "Le client demande un ajustement" to "Relis son retour et ajuste ton offre avant de rejouer."
                    ObjectiveDeliveryOutcome.LOST ->
                        "Le client choisit une autre offre" to "Le tableau explique ce qui a pesé dans sa décision. Tu peux réessayer."
                    null -> defaultHeadline(1) to defaultStory(1, campaign.companyModel.clientName)
                }
            }
        }
        2 -> {
            val state = replay?.chapterTwo ?: campaign.chapterTwo
            when (state.phase) {
                ObjectiveDeliveryChapterTwoPhase.QUOTE ->
                    "Chiffre un devis viable" to "Couvre les coûts, reste dans le budget client et propose une date réaliste."
                ObjectiveDeliveryChapterTwoPhase.NEGOTIATION ->
                    "Le client réagit au prix" to "Explique la valeur de l’offre ou choisis une remise en gardant un œil sur la marge."
                ObjectiveDeliveryChapterTwoPhase.RESULT -> when (state.outcome) {
                    ObjectiveDeliveryOutcome.ORDER_ACCEPTED ->
                        "Le devis est accepté" to "Tu as couvert les coûts et respecté le budget et le délai."
                    ObjectiveDeliveryOutcome.CORRECTION_REQUESTED ->
                        "Le client demande une correction" to "Le bilan t’indique si le prix, la marge ou le délai doit être revu."
                    ObjectiveDeliveryOutcome.LOST ->
                        "La vente est perdue" to "Identifie le compromis qui n’a pas convenu puis prépare une nouvelle offre."
                    null -> defaultHeadline(2) to defaultStory(2, campaign.companyModel.clientName)
                }
            }
        }
        3 -> {
            val state = replay?.chapterThree ?: campaign.chapterThree
            if (state.phase == ObjectiveDeliveryChapterThreePhase.RESULT) {
                when (state.outcome) {
                    ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH ->
                        "Dossier prêt pour l’atelier" to "Le devis signé et les détails techniques suivent la commande jusqu’à l’équipe."
                    ObjectiveDeliveryChapterThreeOutcome.NEEDS_CLARIFICATION ->
                        "Un détail reste à confirmer" to "Complète l’information manquante avant de lancer la commande."
                    ObjectiveDeliveryChapterThreeOutcome.LAUNCH_BLOCKED ->
                        "Le lancement est bloqué" to "Reprends les éléments essentiels : l’équipe ne doit pas deviner les engagements."
                    null -> defaultHeadline(3) to defaultStory(3, campaign.companyModel.clientName)
                }
            } else defaultHeadline(3) to defaultStory(3, campaign.companyModel.clientName)
        }
        4 -> {
            val state = replay?.chapterFour ?: campaign.chapterFour
            if (state.outcome == ObjectiveDeliveryChapterFourOutcome.PLAN_NEEDS_REVIEW) {
                "Le plan d’équipe est à revoir" to "Vérifie les tâches non couvertes, les horaires et le besoin de renfort."
            } else defaultHeadline(4) to defaultStory(4, campaign.companyModel.clientName)
        }
        5 -> {
            val state = replay?.chapterFive ?: campaign.chapterFive
            if (state.outcome == ObjectiveDeliveryChapterFiveOutcome.PLAN_NEEDS_REVIEW) {
                "Une matière risque de manquer" to "Compare le délai fournisseur, le coût et la quantité avant de valider."
            } else defaultHeadline(5) to defaultStory(5, campaign.companyModel.clientName)
        }
        6 -> {
            val state = replay?.chapterSix ?: campaign.chapterSix
            when (state.phase) {
                ObjectiveDeliveryChapterSixPhase.PLANNING ->
                    defaultHeadline(6) to "Choisis le flux de production qui permet de tenir la date."
                ObjectiveDeliveryChapterSixPhase.QUALITY_CONTROL ->
                    "La commande passe au contrôle" to "Vérifie les points du devis avant de décider si elle peut partir."
                ObjectiveDeliveryChapterSixPhase.CORRECTION ->
                    "Un défaut est détecté" to "Choisis une correction sûre et vérifie le résultat avant l’expédition."
                ObjectiveDeliveryChapterSixPhase.RESULT -> when (state.outcome) {
                    ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ->
                        "Contrôle validé" to "La reprise et le nouveau contrôle sont terminés : la commande peut partir."
                    ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION ->
                        "Accord client consigné" to "L’écart mineur est expliqué et accepté avant l’expédition."
                    ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE ->
                        "Le contrôle est incomplet" to "Complète la vérification avant de libérer la commande."
                    ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD ->
                        "La commande reste en attente" to "La qualité n’est pas validée. Corrige l’écart avant toute expédition."
                    null -> defaultHeadline(6) to defaultStory(6, campaign.companyModel.clientName)
                }
            }
        }
        7 -> {
            val state = replay?.chapterSeven ?: campaign.chapterSeven
            when (state.phase) {
                ObjectiveDeliveryChapterSevenPhase.DELIVERY_PLAN ->
                    "Organise le départ" to "Choisis un transport adapté, puis vérifie le contenu et les documents."
                ObjectiveDeliveryChapterSevenPhase.ORDER_CHECK ->
                    "Le client vérifie la commande" to "Il manque encore une vérification avant de clore la livraison."
                ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM ->
                    "Le client signale un problème" to "Écoute, vérifie les faits et donne une suite claire à sa demande."
                ObjectiveDeliveryChapterSevenPhase.RESULT -> when (state.outcome) {
                    ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE ->
                        "La vérification n’est pas terminée" to "Reprends la liste de contrôle avant de confirmer la livraison."
                    ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED ->
                        "La demande est résolue" to "Le client a reçu une réponse et une suite concrète."
                    ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED ->
                        "La réserve reste ouverte" to "Reviens sur la réclamation et propose une vérification ou une correction."
                    null -> defaultHeadline(7) to defaultStory(7, campaign.companyModel.clientName)
                }
            }
        }
        8 -> {
            val state = replay?.chapterEight ?: campaign.chapterEight
            if (state.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_PLAN_NEEDS_REVIEW) {
                "L’équipe demande un nouvel équilibre" to "Ajuste la charge, les absences ou les engagements annoncés."
            } else defaultHeadline(8) to defaultStory(8, campaign.companyModel.clientName)
        }
        9 -> {
            val state = replay?.chapterNine ?: campaign.chapterNine
            when (state.phase) {
                ObjectiveDeliveryChapterNinePhase.INSPECTION ->
                    "Une visite arrive" to "Consigne les faits, écoute l’alerte et protège le poste concerné."
                ObjectiveDeliveryChapterNinePhase.SAFETY_ACTION ->
                    "Le poste doit être sécurisé" to "Réorganise le travail pendant que le risque est vérifié."
                ObjectiveDeliveryChapterNinePhase.CORRECTION ->
                    "Prépare la vérification" to "Corrige les écarts et garde une trace claire des actions."
                ObjectiveDeliveryChapterNinePhase.RESULT -> when (state.outcome) {
                    ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION ->
                        "L’activité reprend" to "Les corrections ont été vérifiées et le travail peut redémarrer."
                    ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD ->
                        "Le contrôle reste ouvert" to "Des points doivent encore être réglés avant la reprise."
                    ObjectiveDeliveryChapterNineOutcome.CLOSED_AFTER_DEADLINE ->
                        "Le délai est dépassé" to "Reprends les actions attendues pour protéger l’équipe et l’activité."
                    null -> defaultHeadline(9) to defaultStory(9, campaign.companyModel.clientName)
                }
            }
        }
        else -> {
            val state = replay?.chapterTen ?: campaign.chapterTen
            if (state.outcome == ObjectiveDeliveryChapterTenOutcome.CAMPAIGN_WON) {
                "La campagne est gagnée" to "Tu as tenu compte des commandes, des clients et des échéances financières."
            } else if (state.outcome == ObjectiveDeliveryChapterTenOutcome.CASH_OR_CUSTOMER_RISK) {
                "La trésorerie ou un client est en risque" to "Revois les priorités sans oublier les engagements déjà pris."
            } else defaultHeadline(10) to defaultStory(10, campaign.companyModel.clientName)
        }
    }

    private fun isTroubled(campaign: ObjectiveDeliveryCampaign, replay: ObjectiveDeliveryReplaySession?): Boolean =
        when (campaign.activeChapter) {
            1 -> {
                val phase = replay?.chapterOne?.phase ?: campaign.phase
                val outcome = replay?.chapterOne?.outcome ?: campaign.outcome
                phase == ObjectiveDeliveryPhase.RESULT && outcome == ObjectiveDeliveryOutcome.LOST
            }
            2 -> {
                val state = replay?.chapterTwo ?: campaign.chapterTwo
                state.phase == ObjectiveDeliveryChapterTwoPhase.RESULT &&
                    state.outcome == ObjectiveDeliveryOutcome.LOST
            }
            3 -> (replay?.chapterThree ?: campaign.chapterThree).outcome ==
                ObjectiveDeliveryChapterThreeOutcome.LAUNCH_BLOCKED
            4 -> (replay?.chapterFour ?: campaign.chapterFour).outcome ==
                ObjectiveDeliveryChapterFourOutcome.PLAN_NEEDS_REVIEW
            5 -> (replay?.chapterFive ?: campaign.chapterFive).outcome ==
                ObjectiveDeliveryChapterFiveOutcome.PLAN_NEEDS_REVIEW
            6 -> (replay?.chapterSix ?: campaign.chapterSix).outcome ==
                ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD
            7 -> (replay?.chapterSeven ?: campaign.chapterSeven).outcome ==
                ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED
            8 -> (replay?.chapterEight ?: campaign.chapterEight).outcome ==
                ObjectiveDeliveryChapterEightOutcome.TEAM_PLAN_NEEDS_REVIEW
            9 -> (replay?.chapterNine ?: campaign.chapterNine).outcome.let {
                it == ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD ||
                    it == ObjectiveDeliveryChapterNineOutcome.CLOSED_AFTER_DEADLINE
            }
            else -> (replay?.chapterTen ?: campaign.chapterTen).outcome ==
                ObjectiveDeliveryChapterTenOutcome.CASH_OR_CUSTOMER_RISK
        }

    private fun person(
        name: String,
        role: String,
        task: String,
        x: Float,
        y: Float,
        shirt: Int,
        skin: Int,
        hair: Int
    ): ObjectiveDeliveryScenePerson {
        val route = listOf(
            ObjectiveDeliveryScenePoint(x.coerceIn(0.12f, 0.88f), y.coerceIn(0.22f, 0.82f)),
            ObjectiveDeliveryScenePoint((x + 0.18f).coerceIn(0.12f, 0.88f), (y - 0.13f).coerceIn(0.22f, 0.82f)),
            ObjectiveDeliveryScenePoint((x - 0.08f).coerceIn(0.12f, 0.88f), (y + 0.10f).coerceIn(0.22f, 0.82f))
        )
        return ObjectiveDeliveryScenePerson(name, role, task, route, shirt, skin, hair)
    }
}
