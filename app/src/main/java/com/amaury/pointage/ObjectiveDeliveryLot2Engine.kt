package com.amaury.pointage

import kotlin.math.max
import kotlin.math.roundToInt

enum class ObjectiveLot2Decision {
    MAINTAIN_VALUE,
    SMALL_CONCESSION,
    LARGE_DISCOUNT,
    WITHDRAW_OFFER,
    CONFIRM_SCOPE_AND_DEADLINE,
    PROMISE_FASTER_UNCHECKED,
    LEAVE_TERMS_VAGUE,

    COMPLETE_ORDER_PACKET,
    QUOTE_ONLY_HANDOFF,
    ORAL_ONLY_HANDOFF,
    CROSS_TEAM_REVIEW,
    ASK_FOR_CLARIFICATION,
    LAUNCH_WITHOUT_REVIEW,

    BALANCE_TEAM,
    REQUEST_HIRE,
    FORCE_OVERTIME,
    TRAIN_EXISTING,
    HIRE_CDI,
    HIRE_CDD,
    HIRE_INTERIM,
    UNAUTHORIZED_HIRE,
    APPROVE_LEAVE_WITH_COVER,
    REFUSE_LEAVE_WITH_ALTERNATIVE,
    CANCEL_LEAVE_WITHOUT_REASON,
    BALANCED_RAISE,
    TOP_PERFORMER_RAISE,
    NO_RAISE,
    EXCEED_RAISE_ENVELOPE,

    RESERVE_AVAILABLE_STOCK,
    WAIT_FOR_CONFIRMED_STOCK,
    PROMISE_WITHOUT_STOCK_CHECK,
    ORDER_STANDARD_EARLY,
    EXPEDITE_SUPPLY,
    DUAL_SOURCE,
    USE_UNVALIDATED_SUBSTITUTE,
    INFORM_CLIENT_AND_REPLAN,
    TAKE_RESERVED_STOCK,
    IGNORE_SUPPLIER_DELAY
}

object ObjectiveDeliveryLot2Engine {
    private const val CHAPTER_TWO = 2
    private const val CHAPTER_THREE = 3
    private const val CHAPTER_FOUR = 4
    private const val CHAPTER_FIVE = 5

    fun title(chapter: Int): String = when (chapter) {
        CHAPTER_TWO -> "Construire et négocier le devis"
        CHAPTER_THREE -> "Passer le relais"
        CHAPTER_FOUR -> "Organiser l'équipe"
        CHAPTER_FIVE -> "Préparer les matières"
        else -> "Chapitre $chapter"
    }

    fun objective(chapter: Int): String = when (chapter) {
        CHAPTER_TWO ->
            "Conserver une marge saine tout en répondant à la demande du client et en fixant des engagements clairs."
        CHAPTER_THREE ->
            "Transmettre une commande suffisamment précise pour que les autres métiers puissent la lancer sans deviner."
        CHAPTER_FOUR ->
            "Couvrir la charge sans sacrifier les droits, la compétence ou l'équilibre de l'équipe."
        CHAPTER_FIVE ->
            "Sécuriser les matières nécessaires sans voler le stock d'une autre commande ni contourner la validation qualité."
        else -> ""
    }

    fun newNotion(chapter: Int): String = when (chapter) {
        CHAPTER_TWO ->
            "Nouvelle notion : une remise change la marge, et une promesse de délai devient un engagement de la commande."
        CHAPTER_THREE ->
            "Nouvelle notion : une information perdue entre deux métiers peut coûter plus cher qu'une clarification avant lancement."
        CHAPTER_FOUR ->
            "Nouvelles notions : capacité, contrat, classification, horaires, congés, compétence, ancienneté et enveloppe d'augmentation."
        CHAPTER_FIVE ->
            "Nouvelles notions : disponible, réservé, attendu, besoin de commande et fiabilité fournisseur."
        else -> ""
    }

    fun startChapter(state: ObjectiveDeliveryState, chapter: Int): ObjectiveDeliveryState {
        require(chapter in CHAPTER_TWO..CHAPTER_FIVE) { "Chapitre hors du Lot 2." }
        require(chapter <= state.unlockedChapter) { "Ce chapitre n'est pas encore débloqué." }

        val migratedBusiness = ensureBusinessInitialized(state)
        return state.copy(
            schemaVersion = ObjectiveDeliveryGameEngine.SCHEMA_VERSION,
            currentChapter = chapter,
            step = 0,
            outcome = ObjectiveOutcome.IN_PROGRESS,
            chapterMetricA = 0,
            chapterMetricB = 0,
            chapterChoice = "",
            business = migratedBusiness,
            revision = state.revision + 1,
            history = listOf(
                "Chapitre $chapter — " + title(chapter) + ".",
                objective(chapter)
            )
        )
    }

    fun retryCurrentChapter(state: ObjectiveDeliveryState): ObjectiveDeliveryState {
        require(state.currentChapter in CHAPTER_TWO..CHAPTER_FIVE) {
            "Ce redémarrage concerne les chapitres 2 à 5."
        }
        return state.copy(
            schemaVersion = ObjectiveDeliveryGameEngine.SCHEMA_VERSION,
            step = 0,
            outcome = ObjectiveOutcome.IN_PROGRESS,
            chapterMetricA = 0,
            chapterMetricB = 0,
            chapterChoice = "",
            revision = state.revision + 1,
            history = listOf(
                "Nouvelle tentative du chapitre " + state.currentChapter + ".",
                objective(state.currentChapter)
            )
        )
    }

    fun apply(
        state: ObjectiveDeliveryState,
        decision: ObjectiveLot2Decision
    ): ObjectiveDeliveryState {
        require(state.outcome == ObjectiveOutcome.IN_PROGRESS) {
            "Le tableau est déjà terminé."
        }
        return when (state.currentChapter) {
            CHAPTER_TWO -> applyChapterTwo(state, decision)
            CHAPTER_THREE -> applyChapterThree(state, decision)
            CHAPTER_FOUR -> applyChapterFour(state, decision)
            CHAPTER_FIVE -> applyChapterFive(state, decision)
            else -> error("Décision Lot 2 utilisée hors des chapitres 2 à 5.")
        }
    }

    private fun applyChapterTwo(
        state: ObjectiveDeliveryState,
        decision: ObjectiveLot2Decision
    ): ObjectiveDeliveryState {
        return when (state.step) {
            0 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.MAINTAIN_VALUE ->
                        Quad(35, 20, "maintain", "Prix maintenu avec explication de la valeur et des limites.")
                    ObjectiveLot2Decision.SMALL_CONCESSION ->
                        Quad(25, 30, "small_discount", "Petite concession accordée sans abandonner la marge.")
                    ObjectiveLot2Decision.LARGE_DISCOUNT ->
                        Quad(5, 35, "large_discount", "Forte remise accordée : le client apprécie, la marge devient fragile.")
                    ObjectiveLot2Decision.WITHDRAW_OFFER -> {
                        return finish(
                            state,
                            ObjectiveOutcome.LOST,
                            "L'entreprise se retire de la négociation. La commande n'est pas signée."
                        )
                    }
                    else -> error("Décision invalide pour la négociation.")
                }
                state.copy(
                    step = 1,
                    chapterMetricA = result.a,
                    chapterMetricB = result.b,
                    chapterChoice = result.c,
                    revision = state.revision + 1,
                    history = state.history + result.d
                )
            }

            1 -> {
                val termResult = when (decision) {
                    ObjectiveLot2Decision.CONFIRM_SCOPE_AND_DEADLINE ->
                        Triple(30, 30, "Produit, quantité, prix, délai et hypothèses sont confirmés avant signature.")
                    ObjectiveLot2Decision.PROMISE_FASTER_UNCHECKED ->
                        Triple(-20, 20, "Un délai plus court est promis sans vérifier la capacité : risque de promesse intenable.")
                    ObjectiveLot2Decision.LEAVE_TERMS_VAGUE ->
                        Triple(-15, -10, "Les conditions restent vagues : le client et l'équipe peuvent comprendre des choses différentes.")
                    else -> error("Décision invalide pour les engagements du devis.")
                }

                val a = state.chapterMetricA + termResult.first
                val b = state.chapterMetricB + termResult.second
                val scenario = ObjectiveDeliveryGameEngine.scenario(state.companyType)
                val baselinePrice = state.business.acceptedOrderPrice
                    .takeIf { it > 0 }
                    ?: state.quotedPrice.takeIf { it > 0 }
                    ?: scenario.referencePrice

                val negotiatedPrice = when (state.chapterChoice) {
                    "small_discount" -> (baselinePrice * 0.97).roundToInt()
                    "large_discount" -> (baselinePrice * 0.90).roundToInt()
                    else -> baselinePrice
                }
                val negotiatedMargin = negotiatedPrice - scenario.baseCost
                val marginRate = negotiatedMargin.toDouble() / scenario.baseCost.toDouble()

                val outcome = when {
                    a >= 50 && b >= 45 && marginRate >= 0.12 -> ObjectiveOutcome.WON
                    a >= 25 && b >= 25 && marginRate >= 0.05 -> ObjectiveOutcome.REWORK
                    else -> ObjectiveOutcome.LOST
                }

                val updatedBusiness = if (outcome == ObjectiveOutcome.WON) {
                    state.business.copy(
                        acceptedOrderPrice = negotiatedPrice,
                        acceptedOrderMargin = negotiatedMargin,
                        acceptedOrderDelayDays = when (decision) {
                            ObjectiveLot2Decision.PROMISE_FASTER_UNCHECKED ->
                                max(2, state.business.acceptedOrderDelayDays - 4)
                            else -> state.business.acceptedOrderDelayDays
                        }
                    )
                } else {
                    state.business
                }

                finish(
                    state.copy(
                        chapterMetricA = a,
                        chapterMetricB = b,
                        quotedPrice = negotiatedPrice,
                        marginAmount = negotiatedMargin,
                        business = updatedBusiness
                    ),
                    outcome,
                    termResult.third,
                    unlock = 3
                )
            }

            else -> state
        }
    }

    private fun applyChapterThree(
        state: ObjectiveDeliveryState,
        decision: ObjectiveLot2Decision
    ): ObjectiveDeliveryState {
        return when (state.step) {
            0 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.COMPLETE_ORDER_PACKET ->
                        55 to "Le relais contient devis, dimensions, options, délai, validations et points encore ouverts."
                    ObjectiveLot2Decision.QUOTE_ONLY_HANDOFF ->
                        25 to "Le devis est transmis seul : plusieurs hypothèses restent dans la tête du commercial."
                    ObjectiveLot2Decision.ORAL_ONLY_HANDOFF ->
                        8 to "Transmission uniquement orale : aucune trace fiable ne permet de vérifier les engagements."
                    else -> error("Décision invalide pour le dossier de transmission.")
                }
                state.copy(
                    step = 1,
                    chapterMetricA = result.first,
                    revision = state.revision + 1,
                    history = state.history + result.second
                )
            }

            1 -> {
                val review = when (decision) {
                    ObjectiveLot2Decision.CROSS_TEAM_REVIEW ->
                        Triple(20, 40, "Commerce, administration et technique font une revue courte avant lancement.")
                    ObjectiveLot2Decision.ASK_FOR_CLARIFICATION ->
                        Triple(25, 30, "Un point ambigu est renvoyé au bon interlocuteur avant de lancer la commande.")
                    ObjectiveLot2Decision.LAUNCH_WITHOUT_REVIEW ->
                        Triple(-15, 5, "La commande est lancée immédiatement sans lever les points bloquants.")
                    else -> error("Décision invalide pour la revue de commande.")
                }
                val a = state.chapterMetricA + review.first
                val b = review.second
                val outcome = when {
                    a >= 65 && b >= 25 -> ObjectiveOutcome.WON
                    a >= 40 -> ObjectiveOutcome.REWORK
                    else -> ObjectiveOutcome.LOST
                }
                val updatedBusiness = if (outcome == ObjectiveOutcome.WON) {
                    state.business.copy(handoffQuality = ((a + b) / 2).coerceIn(0, 100))
                } else {
                    state.business
                }
                finish(
                    state.copy(
                        chapterMetricA = a,
                        chapterMetricB = b,
                        business = updatedBusiness
                    ),
                    outcome,
                    review.third,
                    unlock = 4
                )
            }

            else -> state
        }
    }

    private fun applyChapterFour(
        state: ObjectiveDeliveryState,
        decision: ObjectiveLot2Decision
    ): ObjectiveDeliveryState {
        return when (state.step) {
            0 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.BALANCE_TEAM ->
                        Quad(28, 24, "balance", "La charge est rééquilibrée selon horaires, compétences et disponibilités.")
                    ObjectiveLot2Decision.REQUEST_HIRE ->
                        Quad(24, 18, "hire", "Le manager documente le besoin et demande l'autorisation de recruter.")
                    ObjectiveLot2Decision.FORCE_OVERTIME ->
                        Quad(12, -18, "overtime", "Des heures supplémentaires sont imposées sans validation de la direction.")
                    else -> error("Décision invalide pour l'organisation de la capacité.")
                }
                state.copy(
                    step = 1,
                    chapterMetricA = result.a,
                    chapterMetricB = result.b,
                    chapterChoice = result.c,
                    revision = state.revision + 1,
                    history = state.history + result.d
                )
            }

            1 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.TRAIN_EXISTING ->
                        Quad(26, 28, "train", "Du temps de tutorat est réservé pour faire progresser l'équipe existante.")
                    ObjectiveLot2Decision.HIRE_CDI ->
                        Quad(30, 20, "hire_cdi", "La direction valide le besoin et un CDI est retenu pour un besoin durable.")
                    ObjectiveLot2Decision.HIRE_CDD ->
                        Quad(25, 18, "hire_cdd", "La direction valide un CDD lié à une charge temporaire du scénario.")
                    ObjectiveLot2Decision.HIRE_INTERIM ->
                        Quad(22, 15, "hire_interim", "Un renfort intérimaire est retenu pour absorber un pic court.")
                    ObjectiveLot2Decision.UNAUTHORIZED_HIRE ->
                        Quad(-20, -22, "unauthorized", "Le manager engage un recrutement sans validation de la direction.")
                    else -> error("Décision invalide pour le renfort de l'équipe.")
                }
                state.copy(
                    step = 2,
                    chapterMetricA = state.chapterMetricA + result.a,
                    chapterMetricB = state.chapterMetricB + result.b,
                    chapterChoice = state.chapterChoice + "|" + result.c,
                    revision = state.revision + 1,
                    history = state.history + result.d
                )
            }

            2 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.APPROVE_LEAVE_WITH_COVER ->
                        Quad(18, 30, "leave_ok", "Le congé est accepté car le solde suffit et une couverture est organisée.")
                    ObjectiveLot2Decision.REFUSE_LEAVE_WITH_ALTERNATIVE ->
                        Quad(14, 12, "leave_alt", "Les dates sont refusées pour une contrainte expliquée et une alternative est proposée.")
                    ObjectiveLot2Decision.CANCEL_LEAVE_WITHOUT_REASON ->
                        Quad(-18, -35, "leave_cancel", "Un congé est annulé sans circonstance exceptionnelle ni solution proposée.")
                    else -> error("Décision invalide pour la demande de congé.")
                }
                state.copy(
                    step = 3,
                    chapterMetricA = state.chapterMetricA + result.a,
                    chapterMetricB = state.chapterMetricB + result.b,
                    chapterChoice = state.chapterChoice + "|" + result.c,
                    revision = state.revision + 1,
                    history = state.history + result.d
                )
            }

            3 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.BALANCED_RAISE ->
                        Quad(20, 28, "raise_balanced", "Une hausse de 2,5 % de l'indice est accordée dans l'enveloppe simulée.")
                    ObjectiveLot2Decision.TOP_PERFORMER_RAISE ->
                        Quad(18, 18, "raise_top", "Une hausse de 5 % est ciblée sur la compétence la plus forte et expliquée.")
                    ObjectiveLot2Decision.NO_RAISE ->
                        Quad(5, -8, "raise_none", "Aucune hausse n'est accordée cette fois ; l'enveloppe reste disponible.")
                    ObjectiveLot2Decision.EXCEED_RAISE_ENVELOPE ->
                        Quad(-25, 8, "raise_over", "La proposition dépasse l'enveloppe annuelle autorisée par la direction.")
                    else -> error("Décision invalide pour l'évaluation annuelle.")
                }

                val a = state.chapterMetricA + result.a
                val b = state.chapterMetricB + result.b
                val finalChoice = state.chapterChoice + "|" + result.c
                val outcome = when {
                    a >= 75 && b >= 55 &&
                        !finalChoice.contains("unauthorized") &&
                        !finalChoice.contains("raise_over") -> ObjectiveOutcome.WON
                    a >= 50 && b >= 25 -> ObjectiveOutcome.REWORK
                    else -> ObjectiveOutcome.LOST
                }

                val updatedBusiness = if (outcome == ObjectiveOutcome.WON) {
                    applyTeamConsequences(state.business, state.companyType, finalChoice)
                } else {
                    state.business
                }

                finish(
                    state.copy(
                        chapterMetricA = a,
                        chapterMetricB = b,
                        chapterChoice = finalChoice,
                        business = updatedBusiness
                    ),
                    outcome,
                    result.d,
                    unlock = 5
                )
            }

            else -> state
        }
    }

    private fun applyChapterFive(
        state: ObjectiveDeliveryState,
        decision: ObjectiveLot2Decision
    ): ObjectiveDeliveryState {
        return when (state.step) {
            0 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.RESERVE_AVAILABLE_STOCK ->
                        Quad(30, 20, "stock_reserve", "Le stock libre est réservé à la commande avant de promettre le lancement.")
                    ObjectiveLot2Decision.WAIT_FOR_CONFIRMED_STOCK ->
                        Quad(22, 25, "stock_wait", "Le lancement attend les réceptions confirmées au lieu de compter une livraison hypothétique.")
                    ObjectiveLot2Decision.PROMISE_WITHOUT_STOCK_CHECK ->
                        Quad(-18, -22, "stock_blind", "Une date est promise sans vérifier disponible, réservé et attendu.")
                    else -> error("Décision invalide pour la lecture du stock.")
                }
                state.copy(
                    step = 1,
                    chapterMetricA = result.a,
                    chapterMetricB = result.b,
                    chapterChoice = result.c,
                    revision = state.revision + 1,
                    history = state.history + result.d
                )
            }

            1 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.ORDER_STANDARD_EARLY ->
                        Quad(25, 20, "supplier_standard", "La quantité manquante est commandée tôt chez le fournisseur qualifié.")
                    ObjectiveLot2Decision.EXPEDITE_SUPPLY ->
                        Quad(32, 15, "supplier_expedite", "Un approvisionnement accéléré réduit le délai mais augmente le coût.")
                    ObjectiveLot2Decision.DUAL_SOURCE ->
                        Quad(27, 28, "supplier_dual", "Deux sources déjà qualifiées réduisent le risque de rupture.")
                    ObjectiveLot2Decision.USE_UNVALIDATED_SUBSTITUTE ->
                        Quad(10, -30, "supplier_unvalidated", "Une matière de remplacement non validée est envisagée pour aller plus vite.")
                    else -> error("Décision invalide pour l'approvisionnement.")
                }
                state.copy(
                    step = 2,
                    chapterMetricA = state.chapterMetricA + result.a,
                    chapterMetricB = state.chapterMetricB + result.b,
                    chapterChoice = state.chapterChoice + "|" + result.c,
                    revision = state.revision + 1,
                    history = state.history + result.d
                )
            }

            2 -> {
                val result = when (decision) {
                    ObjectiveLot2Decision.INFORM_CLIENT_AND_REPLAN ->
                        Quad(25, 30, "delay_inform", "Le retard fournisseur est annoncé et le planning est recalculé avant une nouvelle promesse.")
                    ObjectiveLot2Decision.TAKE_RESERVED_STOCK ->
                        Quad(16, -22, "delay_steal", "Du stock réservé à une autre commande est déplacé : le problème est seulement transféré.")
                    ObjectiveLot2Decision.IGNORE_SUPPLIER_DELAY ->
                        Quad(-18, -28, "delay_ignore", "Le retard est ignoré alors que la date estimée n'est plus tenable.")
                    else -> error("Décision invalide pour le retard fournisseur.")
                }
                val a = state.chapterMetricA + result.a
                val b = state.chapterMetricB + result.b
                val finalChoice = state.chapterChoice + "|" + result.c
                val outcome = when {
                    a >= 75 && b >= 55 &&
                        !finalChoice.contains("unvalidated") &&
                        !finalChoice.contains("stock_blind") -> ObjectiveOutcome.WON
                    a >= 50 && b >= 25 -> ObjectiveOutcome.REWORK
                    else -> ObjectiveOutcome.LOST
                }

                val updatedBusiness = if (outcome == ObjectiveOutcome.WON) {
                    applyStockConsequences(state.business, finalChoice)
                } else {
                    state.business
                }

                finish(
                    state.copy(
                        chapterMetricA = a,
                        chapterMetricB = b,
                        chapterChoice = finalChoice,
                        business = updatedBusiness
                    ),
                    outcome,
                    result.d,
                    unlock = 6
                )
            }

            else -> state
        }
    }

    private fun applyTeamConsequences(
        business: ObjectiveBusinessState,
        type: ObjectiveCompanyType,
        choice: String
    ): ObjectiveBusinessState {
        var employees = business.employees

        if (choice.contains("train")) {
            val target = employees.minByOrNull { it.skillLevel }
            if (target != null) {
                employees = employees.map {
                    if (it.id == target.id) {
                        it.copy(
                            skillLevel = (it.skillLevel + 10).coerceAtMost(100),
                            morale = (it.morale + 5).coerceAtMost(100)
                        )
                    } else {
                        it
                    }
                }
            }
        }

        val hireContract = when {
            choice.contains("hire_cdi") -> ObjectiveContractType.CDI
            choice.contains("hire_cdd") -> ObjectiveContractType.CDD
            choice.contains("hire_interim") -> ObjectiveContractType.INTERIM
            else -> null
        }
        if (hireContract != null) {
            val role = when (type) {
                ObjectiveCompanyType.WORKSHOP -> "Renfort fabrication"
                ObjectiveCompanyType.RETAIL -> "Renfort logistique"
                ObjectiveCompanyType.SERVICES -> "Renfort technique"
            }
            employees = employees + ObjectiveEmployee(
                id = "noa_" + (employees.size + 1),
                firstName = "Noa",
                role = role,
                contractType = hireContract,
                classification = ObjectiveClassification.NON_CADRE,
                weeklyHours = if (hireContract == ObjectiveContractType.INTERIM) 28 else 35,
                leaveBalanceDays = if (hireContract == ObjectiveContractType.CDI) 5 else 2,
                skillLabel = "Prise de poste",
                skillLevel = 48,
                seniorityMonths = 0,
                payIndex = 104,
                morale = 72
            )
        }

        if (choice.contains("leave_ok")) {
            val leaveEmployee = employees.maxByOrNull { it.leaveBalanceDays }
            if (leaveEmployee != null) {
                employees = employees.map {
                    if (it.id == leaveEmployee.id) {
                        it.copy(
                            leaveBalanceDays = (it.leaveBalanceDays - 5).coerceAtLeast(0)
                        )
                    } else {
                        it
                    }
                }
            }
        }

        var usedBasisPoints = business.annualRaiseUsedBasisPoints
        if (choice.contains("raise_balanced")) {
            employees = employees.map {
                it.copy(
                    payIndex = max(
                        business.simulatedLegalFloorIndex,
                        (it.payIndex * 1.025).roundToInt()
                    ),
                    morale = (it.morale + 4).coerceAtMost(100)
                )
            }
            usedBasisPoints += 250
        } else if (choice.contains("raise_top")) {
            val top = employees.maxByOrNull { it.skillLevel }
            if (top != null) {
                employees = employees.map {
                    if (it.id == top.id) {
                        it.copy(
                            payIndex = max(
                                business.simulatedLegalFloorIndex,
                                (it.payIndex * 1.05).roundToInt()
                            ),
                            morale = (it.morale + 6).coerceAtMost(100)
                        )
                    } else {
                        it
                    }
                }
                usedBasisPoints += 125
            }
        }

        val moraleAverage = if (employees.isEmpty()) {
            business.teamMorale
        } else {
            employees.map { it.morale }.average().roundToInt()
        }

        return business.copy(
            employees = employees,
            teamMorale = moraleAverage.coerceIn(0, 100),
            annualRaiseUsedBasisPoints = usedBasisPoints.coerceAtMost(
                business.annualRaiseEnvelopeBasisPoints
            ),
            payGridIndex = max(
                business.simulatedLegalFloorIndex,
                employees.minOfOrNull { it.payIndex } ?: business.payGridIndex
            )
        )
    }

    private fun applyStockConsequences(
        business: ObjectiveBusinessState,
        choice: String
    ): ObjectiveBusinessState {
        val purchaseMultiplier = when {
            choice.contains("supplier_expedite") -> 1.35
            choice.contains("supplier_dual") -> 1.10
            else -> 1.0
        }

        var purchaseCost = 0
        val updatedStock = business.stock.map { item ->
            val free = item.freeToPromise
            val missing = (item.requiredForOrder - free).coerceAtLeast(0)
            purchaseCost += (missing * item.unitCost * purchaseMultiplier).roundToInt()

            val freeAfterReceipt = free + item.inbound + missing
            val freeAfterOrder = (freeAfterReceipt - item.requiredForOrder).coerceAtLeast(0)
            item.copy(
                available = item.reserved + freeAfterOrder,
                inbound = 0,
                requiredForOrder = 0
            )
        }

        val reliability = when {
            choice.contains("supplier_dual") -> business.supplierReliability + 6
            choice.contains("supplier_expedite") -> business.supplierReliability + 1
            else -> business.supplierReliability + 2
        }.coerceIn(0, 100)

        return business.copy(
            cash = (business.cash - purchaseCost).coerceAtLeast(0),
            stock = updatedStock,
            supplierReliability = reliability
        )
    }

    private fun ensureBusinessInitialized(
        state: ObjectiveDeliveryState
    ): ObjectiveBusinessState =
        if (state.business.employees.isEmpty() && state.business.stock.isEmpty()) {
            ObjectiveBusinessScenarioFactory.initial(state.companyType).copy(
                acceptedOrderPrice = state.quotedPrice,
                acceptedOrderDelayDays = state.quotedDelayDays,
                acceptedOrderMargin = state.marginAmount
            )
        } else {
            state.business
        }

    private fun finish(
        state: ObjectiveDeliveryState,
        outcome: ObjectiveOutcome,
        note: String,
        unlock: Int = state.unlockedChapter
    ): ObjectiveDeliveryState {
        val resultNote = when (outcome) {
            ObjectiveOutcome.WON ->
                "Objectif atteint : " + title(state.currentChapter) + " est validé."
            ObjectiveOutcome.REWORK ->
                "Résultat partiel : le tableau doit être retravaillé avant de débloquer la suite."
            ObjectiveOutcome.LOST ->
                "Objectif manqué : le bilan montre ce qui a fragilisé la décision."
            ObjectiveOutcome.IN_PROGRESS -> ""
        }

        return state.copy(
            schemaVersion = ObjectiveDeliveryGameEngine.SCHEMA_VERSION,
            outcome = outcome,
            unlockedChapter = if (outcome == ObjectiveOutcome.WON) {
                max(state.unlockedChapter, unlock)
            } else {
                state.unlockedChapter
            },
            revision = state.revision + 1,
            history = state.history + note + resultNote
        )
    }

    private data class Quad(
        val a: Int,
        val b: Int,
        val c: String,
        val d: String
    )
}
