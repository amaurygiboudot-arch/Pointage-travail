package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Campagne 2D à progression explicite, sans horloge ni progression en arrière-plan. */
class ObjectiveDeliveryGameActivity : Activity() {
    private lateinit var store: ObjectiveDeliveryCampaignStore
    private lateinit var cloudSync: ObjectiveDeliveryCloudSync
    private lateinit var content: LinearLayout

    private var campaign: ObjectiveDeliveryCampaign? = null
    private var saveStatus = "La partie est enregistrée automatiquement sur cet appareil."
    private var conflictDialog: AlertDialog? = null
    private var lastAccountUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ObjectiveDeliveryCampaignStore(this)
        campaign = store.currentModel()?.let(store::load)
        cloudSync = ObjectiveDeliveryCloudSync(
            store = store,
            onStatus = { message ->
                runOnUiThread {
                    saveStatus = message
                    updateSaveStatus()
                }
            },
            onCampaignRestored = { restored ->
                runOnUiThread {
                    campaign = restored
                    render()
                }
            },
            onConflict = { local, tips ->
                runOnUiThread { showConflict(local, tips) }
            }
        )
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(20))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != lastAccountUid) {
            lastAccountUid = uid
            render()
        }
        campaign?.let { cloudSync.observe(it.companyModel) }
    }

    override fun onStop() {
        cloudSync.stop()
        super.onStop()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(text("OBJECTIF LIVRAISON", 25f, bold = true, centered = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        content.addView(text("Du premier contact au pilotage de l’entreprise", 14f, centered = true).apply {
            alpha = 0.78f
            setPadding(0, 0, 0, dp(12))
        })
        content.addView(actionButton("FERMER") { finish() }.apply {
            contentDescription = "Fermer le mini-jeu"
        }, buttonParams(top = 4, bottom = 10))

        val current = campaign
        if (current == null) {
            renderCompanyPicker()
        } else {
            renderCampaign(current)
        }
        AppearanceManager.apply(this)
    }

    private fun renderCompanyPicker() {
        content.addView(text("Choisis ton entreprise", 20f, bold = true).apply {
            setPadding(0, dp(4), 0, dp(6))
        })
        content.addView(text(
            "Tu diriges une entreprise fictive. Chaque activité ouvre sa propre partie. " +
                "Le jeu se met en pause quand tu le quittes ; aucun délai ne tourne en arrière-plan.",
            15f
        ).apply { setPadding(0, 0, 0, dp(8)) })

        ObjectiveDeliveryCompanyModel.values().forEach { model ->
            val existing = store.load(model)
            val label = if (existing == null) "COMMENCER" else "REPRENDRE • CHAPITRE ${existing.activeChapter}"
            content.addView(text(model.label, 17f, bold = true).apply {
                setPadding(0, dp(14), 0, dp(2))
            })
            content.addView(text(model.summary, 14f).apply {
                alpha = 0.8f
                setPadding(0, 0, 0, dp(6))
            })
            content.addView(actionButton(label) { openModel(model) }, buttonParams(bottom = 8))
        }
        content.addView(accountAndSaveCard())
    }

    private fun renderCampaign(current: ObjectiveDeliveryCampaign) {
        content.addView(actionButton("CHOISIR UNE AUTRE ENTREPRISE") {
            cloudSync.stop()
            campaign = null
            render()
        }, buttonParams(bottom = 10))

        val replay = current.replaySession
        if (replay != null) {
            content.addView(actionButton("RETOUR À LA CAMPAGNE • CHAPITRE ${replay.returnChapter}") {
                commit(ObjectiveDeliveryGameRules.returnFromReplay(current))
            }, buttonParams(bottom = 8))
        } else {
            if (current.activeChapter >= 2) {
                content.addView(actionButton("REJOUER LE CHAPITRE 1") {
                    commit(ObjectiveDeliveryGameRules.replayChapterOne(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter >= 3) {
                content.addView(actionButton("REJOUER LE CHAPITRE 2") {
                    commit(ObjectiveDeliveryGameRules.replayChapterTwo(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter >= 4) {
                content.addView(actionButton("REJOUER LE CHAPITRE 3") {
                    commit(ObjectiveDeliveryGameRules.replayChapterThree(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter >= 5) {
                content.addView(actionButton("REJOUER LE CHAPITRE 4") {
                    commit(ObjectiveDeliveryGameRules.replayChapterFour(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter >= 6) {
                content.addView(actionButton("REJOUER LE CHAPITRE 5") {
                    commit(ObjectiveDeliveryGameRules.replayChapterFive(current))
                }, buttonParams(bottom = 8))
                if (current.activeChapter > 6 || current.chapterSix.outcome != null) {
                    content.addView(actionButton("REJOUER LE CHAPITRE 6") {
                        commit(ObjectiveDeliveryGameRules.replayChapterSix(current))
                    }, buttonParams(bottom = 8))
                }
            }
            if (current.activeChapter >= 7) {
                if (current.chapterSeven.outcome != null) {
                    content.addView(actionButton("REJOUER LE CHAPITRE 7") {
                        commit(ObjectiveDeliveryGameRules.replayChapterSeven(current))
                    }, buttonParams(bottom = 8))
                }
            }
            if (current.activeChapter >= 8 && current.chapterEight.outcome != null) {
                content.addView(actionButton("REJOUER LE CHAPITRE 8") {
                    commit(ObjectiveDeliveryGameRules.replayChapterEight(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter >= 9 && current.chapterNine.outcome != null) {
                content.addView(actionButton("REJOUER LE CHAPITRE 9") {
                    commit(ObjectiveDeliveryGameRules.replayChapterNine(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter >= 10 && current.chapterTen.outcome != null) {
                content.addView(actionButton("REJOUER LE CHAPITRE 10") {
                    commit(ObjectiveDeliveryGameRules.replayChapterTen(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 1 && current.chapterOneWon && current.unlockedChapter >= 2) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 2") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterTwo(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 2 && current.unlockedChapter >= 3 &&
                current.chapterTwo.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 3") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterThree(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 3 && current.unlockedChapter >= 4 &&
                current.chapterThree.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 4") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterFour(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 4 && current.unlockedChapter >= 5 &&
                current.chapterFour.outcome == ObjectiveDeliveryChapterFourOutcome.TEAM_READY
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 5") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterFive(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 5 && current.unlockedChapter >= 6 &&
                current.chapterFive.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 6") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterSix(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 6 && current.unlockedChapter >= 7 &&
                (current.chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
                    current.chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION)
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 7") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterSeven(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 7 && current.unlockedChapter >= 8 &&
                current.chapterSeven.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 8") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterEight(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 8 && current.unlockedChapter >= 9 &&
                current.chapterEight.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 9") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterNine(current))
                }, buttonParams(bottom = 8))
            }
            if (current.activeChapter == 9 && current.unlockedChapter >= 10 &&
                current.chapterNine.outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION
            ) {
                content.addView(actionButton("CONTINUER VERS LE CHAPITRE 10") {
                    commit(ObjectiveDeliveryGameRules.continueToChapterTen(current))
                }, buttonParams(bottom = 8))
            }
        }

        val chapter = current.activeChapter
        content.addView(text("CHAPITRE $chapter / 10", 13f, bold = true).apply {
            alpha = 0.75f
            setPadding(0, dp(4), 0, dp(3))
        })
        val chapterTitle = when (chapter) {
            1 -> "Premier contact"
            2 -> "Construire le devis"
            3 -> "Passer le relais"
            4 -> "Organiser l’équipe"
            5 -> "Préparer les matières"
            6 -> "Fabriquer et contrôler"
            7 -> "Livrer et écouter le client"
            8 -> "Faire vivre l’entreprise et l’équipe"
            9 -> "Protéger l’activité"
            else -> "Piloter sous pression"
        }
        content.addView(text(chapterTitle, 21f, bold = true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val attempt = when (chapter) {
            1 -> current.attemptNumber
            2 -> current.chapterTwoAttemptNumber
            3 -> current.chapterThreeAttemptNumber
            4 -> current.chapterFourAttemptNumber
            5 -> current.chapterFiveAttemptNumber
            6 -> current.chapterSixAttemptNumber
            7 -> current.chapterSevenAttemptNumber
            8 -> current.chapterEightAttemptNumber
            9 -> current.chapterNineAttemptNumber
            else -> current.chapterTenAttemptNumber
        }
        content.addView(text("${current.companyModel.label} • tentative $attempt", 14f).apply {
            alpha = 0.82f
            setPadding(0, 0, 0, dp(12))
        })

        if (chapter == 1) {
            val board = ObjectiveDeliveryGameRules.chapterOneState(current)
            when (board.phase) {
                ObjectiveDeliveryPhase.QUALIFICATION -> renderQualification(current)
                ObjectiveDeliveryPhase.OFFER -> renderOffer(current, board)
                ObjectiveDeliveryPhase.RESULT -> renderResult(current, board)
            }
        } else if (chapter == 2) {
            renderChapterTwo(current, ObjectiveDeliveryGameRules.chapterTwoState(current))
        } else if (chapter == 3) {
            renderChapterThree(current, ObjectiveDeliveryGameRules.chapterThreeState(current))
        } else if (chapter == 4) {
            renderChapterFour(current, ObjectiveDeliveryGameRules.chapterFourState(current))
        } else if (chapter == 5) {
            renderChapterFive(current, ObjectiveDeliveryGameRules.chapterFiveState(current))
        } else if (chapter == 6) {
            renderChapterSix(current, ObjectiveDeliveryGameRules.chapterSixState(current))
        } else when (chapter) {
            7 -> renderChapterSeven(current, ObjectiveDeliveryGameRules.chapterSevenState(current))
            8 -> renderChapterEight(current, ObjectiveDeliveryGameRules.chapterEightState(current))
            9 -> renderChapterNine(current, ObjectiveDeliveryGameRules.chapterNineState(current))
            else -> renderChapterTen(current, ObjectiveDeliveryGameRules.chapterTenState(current))
        }
        content.addView(accountAndSaveCard())
    }

    private fun renderQualification(current: ObjectiveDeliveryCampaign) {
        content.addView(infoCard(
            "LE CLIENT",
            "${current.companyModel.clientName} demande ${current.companyModel.request}. " +
                "La personne souhaite recevoir une proposition adaptée à son besoin."
        ))
        content.addView(text("Ton objectif : vérifier les informations utiles avant de préparer le devis.", 15f).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(actionButton("POSER LES QUESTIONS ESSENTIELLES") {
            commit(ObjectiveDeliveryGameRules.qualifyNeed(current, askedQuestions = true))
        }, buttonParams(bottom = 8))
        content.addView(text("Confirme l’usage, le budget et la date souhaitée. Ces informations t’aideront à faire une offre complète.", 13f).apply {
            alpha = 0.78f
            setPadding(dp(4), 0, dp(4), dp(10))
        })
        content.addView(actionButton("PASSER DIRECTEMENT AU DEVIS") {
            commit(ObjectiveDeliveryGameRules.qualifyNeed(current, askedQuestions = false))
        }, buttonParams(bottom = 8))
    }

    private fun renderOffer(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterOneState) {
        content.addView(infoCard(
            "BESOIN DU CLIENT",
            if (board.needQualified) {
                "${current.companyModel.request}\n" +
                    "Budget maximum : ${formatEuro(current.companyModel.clientBudgetCents)}\n" +
                    "Délai souhaité : ${current.companyModel.requestedDays} jours"
            } else {
                "Tu as sauté la qualification : le budget et le délai ne sont pas connus. " +
                    "Tu peux quand même envoyer une proposition, mais le client risque de la faire corriger."
            }
        ))
        content.addView(text("1. Choisis le prix", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryPriceChoice.values().forEach { choice ->
            val amount = ObjectiveDeliveryGameRules.priceFor(current.companyModel, choice)
            val detail = when (choice) {
                ObjectiveDeliveryPriceChoice.ATTRACTIVE -> "Prix plus bas • recette plus faible"
                ObjectiveDeliveryPriceChoice.BALANCED -> "Prix de référence • compromis équilibré"
                ObjectiveDeliveryPriceChoice.AMBITIOUS -> "Prix plus élevé • risque de dépasser le budget"
            }
            content.addView(actionButton(
                selectedLabel(choice == board.priceChoice, "${choice.label} — ${formatEuro(amount)}\n$detail")
            ) {
                commit(ObjectiveDeliveryGameRules.choosePrice(current, choice))
            }, buttonParams(bottom = 6))
        }

        content.addView(text("2. Choisis un délai", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryTimelineChoice.values().forEach { choice ->
            val days = choice.daysFor(current.companyModel.requestedDays)
            val detail = when (choice) {
                ObjectiveDeliveryTimelineChoice.FAST -> "Délai court • demande une organisation rapide"
                ObjectiveDeliveryTimelineChoice.REQUESTED -> "Respecte la date demandée par le client"
                ObjectiveDeliveryTimelineChoice.PRUDENT -> "Délai plus confortable • dépasse la demande"
            }
            content.addView(actionButton(
                selectedLabel(choice == board.timelineChoice, "${choice.label} — $days jours\n$detail")
            ) {
                commit(ObjectiveDeliveryGameRules.chooseTimeline(current, choice))
            }, buttonParams(bottom = 6))
        }

        content.addView(actionButton("ENVOYER LE DEVIS") {
            val updated = ObjectiveDeliveryGameRules.submitOffer(current)
            commit(updated)
        }.apply {
            isEnabled = board.priceChoice != null && board.timelineChoice != null
            alpha = if (isEnabled) 1f else 0.55f
        }, buttonParams(top = 12, bottom = 8))
        if (board.priceChoice == null || board.timelineChoice == null) {
            content.addView(text("Choisis un prix et un délai pour envoyer le devis.", 13f).apply {
                gravity = Gravity.CENTER
                alpha = 0.75f
                setPadding(0, 0, 0, dp(8))
            })
        }
    }

    private fun renderResult(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterOneState) {
        val evaluation = runCatching { ObjectiveDeliveryGameRules.evaluate(current) }.getOrNull()
        val accepted = board.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED
        val heading = when (board.outcome) {
            ObjectiveDeliveryOutcome.ORDER_ACCEPTED -> "COMMANDE GAGNÉE !"
            ObjectiveDeliveryOutcome.CORRECTION_REQUESTED -> "LE CLIENT DEMANDE UNE CORRECTION"
            ObjectiveDeliveryOutcome.LOST -> "LE CLIENT CHOISIT UNE AUTRE OFFRE"
            null -> "BILAN DU TABLEAU"
        }
        val explanation = when (board.outcome) {
            ObjectiveDeliveryOutcome.ORDER_ACCEPTED ->
                "Le client accepte le devis. Le chapitre 2 « Construire le devis » est débloqué. " +
                    "Ta campagne est enregistrée et cette victoire ne termine pas la partie."
            ObjectiveDeliveryOutcome.CORRECTION_REQUESTED ->
                "Une condition manque. Regarde le bilan, puis recommence le tableau en ajustant ton offre."
            ObjectiveDeliveryOutcome.LOST ->
                "Plusieurs conditions ne conviennent pas au client. Le bilan explique quoi corriger avant de rejouer."
            null -> "Le tableau est terminé."
        }
        content.addView(infoCard(heading, explanation))
        if (evaluation != null) {
            content.addView(text("Ton offre : ${formatEuro(evaluation.quotedPriceCents)} • ${evaluation.quotedDays} jours", 15f).apply {
                setPadding(0, dp(12), 0, dp(6))
            })
            content.addView(checkLine("Besoin suffisamment clarifié", evaluation.needQualified))
            content.addView(checkLine("Prix dans le budget client", evaluation.priceWithinBudget))
            content.addView(checkLine("Date compatible avec sa demande", evaluation.deadlineMet))
        }
        if (accepted) {
            content.addView(infoCard(
                "PROGRESSION",
                "Chapitre 2 déverrouillé • Construire le devis\n" +
                    "Le rejeu du chapitre 1 est conservé dans une copie séparée de ta campagne."
            ).apply { setPadding(0, dp(12), 0, 0) })
        }
        content.addView(actionButton(if (accepted) "REJOUER LE CHAPITRE 1" else "RECOMMENCER LE TABLEAU") {
            commit(ObjectiveDeliveryGameRules.replayChapterOne(current))
        }, buttonParams(top = 14, bottom = 8))
    }

    private fun renderChapterTwo(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterTwoState
    ) {
        val model = current.companyModel
        when (board.phase) {
            ObjectiveDeliveryChapterTwoPhase.QUOTE -> {
                content.addView(infoCard(
                    "LA DEMANDE",
                    "Prépare un devis pour ${model.clientName} : ${model.request}.\n" +
                        "Budget maximum : ${formatEuro(model.chapterTwoClientBudgetCents)} • " +
                        "délai souhaité : ${model.requestedDays} jours."
                ))
                val totalCost = model.directCostCents + model.fixedCostShareCents
                content.addView(infoCard(
                    "TES COÛTS FICTIFS",
                    "Coûts directs : ${formatEuro(model.directCostCents)}\n" +
                        "Part des frais fixes : ${formatEuro(model.fixedCostShareCents)}\n" +
                        "Coût total à couvrir : ${formatEuro(totalCost)}"
                ).apply { setPadding(0, dp(6), 0, 0) })
                content.addView(text("1. Choisis le niveau de marge", 17f, bold = true).apply {
                    setPadding(0, dp(12), 0, dp(4))
                })
                ObjectiveDeliveryChapterTwoPriceChoice.values().forEach { choice ->
                    val quote = ObjectiveDeliveryGameRules.chapterTwoQuoteFor(model, choice)
                    val margin = quote - totalCost
                    content.addView(actionButton(selectedLabel(
                        choice == board.priceChoice,
                        "${choice.label} — ${formatEuro(quote)}\n" +
                            "Marge estimée : ${formatEuro(margin)} • budget ${formatEuro(model.chapterTwoClientBudgetCents)}"
                    )) {
                        commit(ObjectiveDeliveryGameRules.chooseChapterTwoPrice(current, choice))
                    }, buttonParams(bottom = 6))
                }

                content.addView(text("2. Choisis un délai réaliste", 17f, bold = true).apply {
                    setPadding(0, dp(12), 0, dp(4))
                })
                ObjectiveDeliveryTimelineChoice.values().forEach { choice ->
                    val days = choice.daysFor(model.requestedDays)
                    val detail = if (days <= model.requestedDays) {
                        "Respecte le délai demandé"
                    } else {
                        "Dépasse le délai demandé"
                    }
                    content.addView(actionButton(selectedLabel(
                        choice == board.timelineChoice,
                        "${choice.label} — $days jours\n$detail"
                    )) {
                        commit(ObjectiveDeliveryGameRules.chooseChapterTwoTimeline(current, choice))
                    }, buttonParams(bottom = 6))
                }
                content.addView(actionButton("PRÉSENTER LE DEVIS AU CLIENT") {
                    commit(ObjectiveDeliveryGameRules.submitChapterTwoQuote(current))
                }.apply {
                    isEnabled = board.priceChoice != null && board.timelineChoice != null
                    alpha = if (isEnabled) 1f else 0.55f
                }, buttonParams(top = 12, bottom = 8))
            }
            ObjectiveDeliveryChapterTwoPhase.NEGOTIATION -> renderChapterTwoNegotiation(current, board)
            ObjectiveDeliveryChapterTwoPhase.RESULT -> renderChapterTwoResult(current, board)
        }
    }

    private fun renderChapterTwoNegotiation(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterTwoState
    ) {
        val model = current.companyModel
        val quote = ObjectiveDeliveryGameRules.chapterTwoQuoteFor(model, requireNotNull(board.priceChoice))
        val days = requireNotNull(board.timelineChoice).daysFor(model.requestedDays)
        content.addView(infoCard(
            "LE CLIENT RÉAGIT AU DEVIS",
            "Le devis est de ${formatEuro(quote)} pour $days jours. " +
                "Le client te demande si tu peux ajuster le prix ou expliquer ce qui est inclus."
        ))
        content.addView(text("Choisis ta réponse", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryNegotiationChoice.values().forEach { choice ->
            val finalPrice = (quote * (100 - choice.discountPercent) / 100.0).roundToInt()
            val detail = if (choice.discountPercent == 0) {
                "Tu gardes le prix et détailles la valeur de l’offre"
            } else {
                "Nouveau prix : ${formatEuro(finalPrice)} • vérifie la marge restante"
            }
            content.addView(actionButton(selectedLabel(
                choice == board.negotiationChoice,
                "${choice.label}\n$detail"
            )) {
                commit(ObjectiveDeliveryGameRules.chooseNegotiation(current, choice))
            }, buttonParams(bottom = 6))
        }
        content.addView(actionButton("ENVOYER MA RÉPONSE") {
            commit(ObjectiveDeliveryGameRules.submitChapterTwoNegotiation(current))
        }.apply {
            isEnabled = board.negotiationChoice != null
            alpha = if (isEnabled) 1f else 0.55f
        }, buttonParams(top = 12, bottom = 8))
    }

    private fun renderChapterTwoResult(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterTwoState
    ) {
        val evaluation = runCatching { ObjectiveDeliveryGameRules.evaluateChapterTwo(current) }.getOrNull()
        val accepted = board.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED
        val heading = when (board.outcome) {
            ObjectiveDeliveryOutcome.ORDER_ACCEPTED -> "DEVIS ACCEPTÉ !"
            ObjectiveDeliveryOutcome.CORRECTION_REQUESTED -> "LE CLIENT DEMANDE UN AJUSTEMENT"
            ObjectiveDeliveryOutcome.LOST -> "LA VENTE EST PERDUE"
            null -> "BILAN DU DEVIS"
        }
        val explanation = when (board.outcome) {
            ObjectiveDeliveryOutcome.ORDER_ACCEPTED ->
                "Tu as couvert les coûts, respecté le budget et proposé un délai adapté. Chapitre 2 validé."
            ObjectiveDeliveryOutcome.CORRECTION_REQUESTED ->
                "Une condition n’est pas remplie. Le bilan indique si le prix, la marge ou le délai est à revoir."
            ObjectiveDeliveryOutcome.LOST ->
                "Plusieurs conditions ne conviennent pas. Tu peux préparer un autre devis sans perdre ta campagne."
            null -> "Le client a répondu à ta proposition."
        }
        content.addView(infoCard(heading, explanation))
        if (evaluation != null) {
            content.addView(text(
                "Prix initial : ${formatEuro(evaluation.baseQuoteCents)}\n" +
                    "Prix final : ${formatEuro(evaluation.finalPriceCents)} • ${evaluation.quotedDays} jours\n" +
                    "Coûts : ${formatEuro(evaluation.totalCostCents)}\n" +
                    "Marge : ${formatEuro(evaluation.marginCents)} (${evaluation.marginPercent} % des coûts)",
                15f
            ).apply { setPadding(0, dp(12), 0, dp(8)) })
            content.addView(checkLine("Prix dans le budget client", evaluation.priceWithinBudget))
            content.addView(checkLine("Prix supérieur au coût total", evaluation.aboveCost))
            content.addView(checkLine("Délai compatible avec la demande", evaluation.deadlineMet))
        }

        val replay = current.replaySession?.chapter == 2
        if (replay && accepted) {
            content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
                commit(ObjectiveDeliveryGameRules.keepChapterTwoReplayResult(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 2") {
            commit(ObjectiveDeliveryGameRules.replayChapterTwo(current))
        }, buttonParams(top = if (replay && accepted) 2 else 14, bottom = 8))
    }

    private fun renderChapterThree(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterThreeState
    ) {
        when (board.phase) {
            ObjectiveDeliveryChapterThreePhase.REVIEW -> {
                val terms = ObjectiveDeliveryGameRules.evaluateChapterTwo(current)
                content.addView(infoCard(
                    "OBJECTIF DU TABLEAU",
                    "Le client a signé le devis. Avant le lancement, transmets le dossier du commerce " +
                        "à l’administration et à l’équipe technique. Une information manquante peut bloquer " +
                        "le travail ou obliger l’équipe à demander une clarification."
                ))
                content.addView(infoCard(
                    "ENGAGEMENTS SIGNÉS",
                    "${current.companyModel.request}\n" +
                        "Prix convenu : ${formatEuro(terms.finalPriceCents)}\n" +
                        "Délai promis : ${terms.quotedDays} jours"
                ).apply { setPadding(0, dp(6), 0, 0) })
                content.addView(text(
                    "Vérifie chaque élément du dossier avant de le transmettre. Les détails de l’offre " +
                        "restent ceux convenus au chapitre précédent.",
                    14f
                ).apply { setPadding(0, dp(12), 0, dp(6)) })

                val technicalDetail = when (current.companyModel) {
                    ObjectiveDeliveryCompanyModel.WORKSHOP -> "dimensions et finition du produit"
                    ObjectiveDeliveryCompanyModel.DISTRIBUTION -> "références et quantités commandées"
                    ObjectiveDeliveryCompanyModel.SERVICES -> "périmètre et contraintes de l’intervention"
                }
                handoffToggle(
                    "Devis signé joint, prix et délai repris sans modification",
                    ObjectiveDeliveryHandoffItem.SIGNED_QUOTE,
                    board.signedQuoteAttached,
                    current
                )
                handoffToggle(
                    "Fiche technique confirmée : $technicalDetail",
                    ObjectiveDeliveryHandoffItem.TECHNICAL_SPECIFICATION,
                    board.technicalSpecificationConfirmed,
                    current
                )
                handoffToggle(
                    "Options validées avec le client, ou absence d’option indiquée",
                    ObjectiveDeliveryHandoffItem.CUSTOMER_OPTIONS,
                    board.customerOptionsConfirmed,
                    current
                )
                handoffToggle(
                    "Lieu et conditions de livraison ou d’intervention confirmés",
                    ObjectiveDeliveryHandoffItem.DELIVERY_CONDITIONS,
                    board.deliveryConditionsConfirmed,
                    current
                )
                handoffToggle(
                    "Date promise reprise du devis et confirmée par l’équipe",
                    ObjectiveDeliveryHandoffItem.PROMISED_DATE,
                    board.promisedDateConfirmed,
                    current
                )
                content.addView(actionButton("TRANSMETTRE LE DOSSIER") {
                    commit(ObjectiveDeliveryGameRules.submitChapterThreeReview(current))
                }, buttonParams(top = 12, bottom = 8))
            }
            ObjectiveDeliveryChapterThreePhase.RESULT -> renderChapterThreeResult(current, board)
        }
    }

    private fun handoffToggle(
        label: String,
        item: ObjectiveDeliveryHandoffItem,
        included: Boolean,
        current: ObjectiveDeliveryCampaign
    ) {
        content.addView(actionButton(selectedLabel(included, label)) {
            commit(ObjectiveDeliveryGameRules.setHandoffItem(current, item, !included))
        }, buttonParams(bottom = 6))
    }

    private fun renderChapterThreeResult(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterThreeState
    ) {
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterThree(current)
        val heading = when (board.outcome) {
            ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH -> "DOSSIER PRÊT À ÊTRE LANCÉ"
            ObjectiveDeliveryChapterThreeOutcome.NEEDS_CLARIFICATION -> "UNE CLARIFICATION EST NÉCESSAIRE"
            ObjectiveDeliveryChapterThreeOutcome.LAUNCH_BLOCKED -> "LE LANCEMENT EST BLOQUÉ"
            null -> "BILAN DU RELAIS"
        }
        val explanation = when (board.outcome) {
            ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH ->
                "Le devis signé, les exigences techniques, les options, les conditions de livraison et la date " +
                    "sont réunis. L’administration et l’équipe technique peuvent travailler sur le même dossier."
            ObjectiveDeliveryChapterThreeOutcome.NEEDS_CLARIFICATION ->
                "Il manque un élément. Le lancement attend sa confirmation ; l’équipe ne doit pas deviner."
            ObjectiveDeliveryChapterThreeOutcome.LAUNCH_BLOCKED ->
                "Plusieurs éléments manquent. Le lancement est suspendu jusqu’à ce que le dossier soit complété."
            null -> "Le dossier a été examiné."
        }
        content.addView(infoCard(heading, explanation))
        content.addView(text("Éléments manquants : ${evaluation.missingDocuments}", 15f).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        content.addView(checkLine("Devis signé et engagements", board.signedQuoteAttached))
        content.addView(checkLine("Spécification technique", board.technicalSpecificationConfirmed))
        content.addView(checkLine("Options client", board.customerOptionsConfirmed))
        content.addView(checkLine("Livraison ou intervention", board.deliveryConditionsConfirmed))
        content.addView(checkLine("Date promise", board.promisedDateConfirmed))

        val replay = current.replaySession?.chapter == 3
        if (board.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH && replay) {
            content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
                commit(ObjectiveDeliveryGameRules.keepChapterThreeReplayResult(current))
            }, buttonParams(top = 14, bottom = 8))
        } else if (board.outcome != ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH) {
            content.addView(actionButton("COMPLÉTER LE DOSSIER") {
                commit(ObjectiveDeliveryGameRules.correctChapterThreeReview(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 3") {
            commit(ObjectiveDeliveryGameRules.replayChapterThree(current))
        }, buttonParams(top = if (replay && board.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH) 2 else 8,
            bottom = 8))
    }

    private fun renderChapterFour(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFourState
    ) {
        when (board.phase) {
            ObjectiveDeliveryChapterFourPhase.PLAN -> renderChapterFourPlan(current, board)
            ObjectiveDeliveryChapterFourPhase.RESULT -> renderChapterFourResult(current, board)
        }
    }

    private fun renderChapterFourPlan(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFourState
    ) {
        content.addView(infoCard(
            "OBJECTIF DU TABLEAU",
            "Répartis les trois tâches selon les compétences et les horaires de l’équipe. Karim demande " +
            "un jour de congé le mercredi. Tu peux l’accepter si un renfort couvre l’atelier, ou lui " +
                "proposer une autre date. Termine par une revue annuelle fondée sur des faits et une " +
                "enveloppe d’augmentation annoncée. Dans ce scénario, 1 point représente 1 % simulé ; " +
                "le plafond individuel de 5 % est une règle du jeu."
        ))
        ObjectiveDeliveryTeamMember.values()
            .filter { it != ObjectiveDeliveryTeamMember.TEMPORARY_TECHNICIAN }
            .forEach { member ->
                val seniorityYears = member.seniorityMonths / 12
                val seniorityMonths = member.seniorityMonths % 12
                content.addView(infoCard(
                    "${member.displayName} • ${member.jobTitle}",
                    "${member.weeklyHours} h par semaine • ${member.schedule}\n" +
                        "Ancienneté : ${seniorityYears} an(s) ${seniorityMonths} mois\n" +
                        "Compétences : ${member.skills}"
                ).apply { setPadding(0, dp(5), 0, 0) })
            }
        content.addView(infoCard(
            "DEMANDE DE CONGÉ",
            "Karim demande une journée le mercredi. Son solde de congés fictif est suffisant ; " +
                "la question est de couvrir la tâche planifiée."
        ).apply { setPadding(0, dp(5), 0, 0) })

        renderTaskAssignees(
            current, board, ObjectiveDeliveryChapterFourTask.CUSTOMER_FILE,
            "1. Dossier client • lundi et mardi", board.customerFileAssignee
        )
        renderTaskAssignees(
            current, board, ObjectiveDeliveryChapterFourTask.PRODUCTION,
            "2. Travail d’atelier • mercredi", board.productionAssignee
        )
        renderTaskAssignees(
            current, board, ObjectiveDeliveryChapterFourTask.DISPATCH,
            "3. Préparation et expédition • jeudi et vendredi", board.dispatchAssignee
        )

        content.addView(text("Réponds à la demande de congé", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryChapterFourLeaveChoice.values().forEach { choice ->
            val label = when (choice) {
                ObjectiveDeliveryChapterFourLeaveChoice.APPROVE_WITH_COVER ->
                    "Approuver le mercredi si l’atelier est couvert"
                ObjectiveDeliveryChapterFourLeaveChoice.OFFER_ALTERNATIVE_DATE ->
                    "Proposer une autre date et l’expliquer"
            }
            content.addView(actionButton(selectedLabel(choice == board.leaveChoice, label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterFourLeave(current, choice))
            }, buttonParams(bottom = 6))
        }

        content.addView(text("Demande de renfort du manager", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryChapterFourHireDecision.values().forEach { decision ->
            val label = when (decision) {
                ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE ->
                    "Autoriser un remplacement temporaire pour l’absence de mercredi"
                ObjectiveDeliveryChapterFourHireDecision.DECLINE_WITH_ALTERNATIVE ->
                    "Refuser le recrutement et réorganiser le planning"
            }
            content.addView(actionButton(selectedLabel(decision == board.hireDecision, label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterFourHire(current, decision))
            }, buttonParams(bottom = 6))
        }
        if (board.hireDecision == ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE) {
            content.addView(text(
                "Le contrat et la classification sont deux choix distincts. Pour ce besoin ponctuel, " +
                "compare un CDD ou une mission d’intérim pour remplacer un salarié temporairement absent. " +
                    "La classification est choisie séparément.",
                13f
            ).apply { alpha = 0.82f; setPadding(0, dp(4), 0, dp(4)) })
            ObjectiveDeliveryChapterFourContract.values().forEach { contract ->
                content.addView(actionButton(selectedLabel(contract == board.hireContract, contract.name)) {
                    commit(ObjectiveDeliveryGameRules.setChapterFourHireTerms(
                        current, contract, board.hireClassification
                    ))
                }, buttonParams(bottom = 4))
            }
            ObjectiveDeliveryChapterFourClassification.values().forEach { classification ->
                val label = when (classification) {
                    ObjectiveDeliveryChapterFourClassification.NON_CADRE -> "Classification non-cadre"
                    ObjectiveDeliveryChapterFourClassification.CADRE -> "Classification cadre"
                }
                content.addView(actionButton(selectedLabel(
                    classification == board.hireClassification, label
                )) {
                    commit(ObjectiveDeliveryGameRules.setChapterFourHireTerms(
                        current, board.hireContract, classification
                    ))
                }, buttonParams(bottom = 4))
            }
        }

        content.addView(text("Revue annuelle et enveloppe", 17f, bold = true).apply {
            setPadding(0, dp(14), 0, dp(4))
        })
        content.addView(text(
            "Choisis un salarié, discute des résultats et des compétences acquis, puis répartis " +
                "l’enveloppe en points de simulation. Une attribution individuelle ne dépasse jamais 5 % " +
                    "du salaire de base fictif.",
            14f
        ).apply { alpha = 0.85f; setPadding(0, 0, 0, dp(6)) })
        ObjectiveDeliveryTeamMember.values()
            .filter { it != ObjectiveDeliveryTeamMember.TEMPORARY_TECHNICIAN }
            .forEach { member ->
                content.addView(actionButton(selectedLabel(
                    board.reviewedEmployee == member,
                    "Évaluer ${member.displayName} • compétences et faits observés"
                )) {
                    commit(ObjectiveDeliveryGameRules.recordChapterFourAnnualReview(
                        current, member, factsDiscussed = board.reviewFactsDiscussed
                    ))
                }, buttonParams(bottom = 4))
            }
        content.addView(actionButton(selectedLabel(
            board.reviewFactsDiscussed, "Documenter les faits et recueillir le point de vue du salarié"
        )) {
            val employee = board.reviewedEmployee ?: ObjectiveDeliveryTeamMember.KARIM
            commit(ObjectiveDeliveryGameRules.recordChapterFourAnnualReview(
                current, employee, factsDiscussed = !board.reviewFactsDiscussed
            ))
        }, buttonParams(bottom = 8))

        renderRaisePointChoices(
            current, "Enveloppe de la direction", board.raiseEnvelopePoints, isEnvelope = true
        )
        renderRaisePointChoices(
            current, "Part attribuée au salarié évalué", board.individualRaisePoints, isEnvelope = false
        )
        content.addView(actionButton("VALIDER L’ORGANISATION DE L’ÉQUIPE") {
            commit(ObjectiveDeliveryGameRules.submitChapterFourPlan(current))
        }, buttonParams(top = 14, bottom = 8))
    }

    private fun renderTaskAssignees(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFourState,
        task: ObjectiveDeliveryChapterFourTask,
        title: String,
        selected: ObjectiveDeliveryTeamMember?
    ) {
        content.addView(text(title, 16f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryTeamMember.values().forEach { employee ->
            content.addView(actionButton(selectedLabel(
                employee == selected,
                "${employee.displayName} • ${employee.jobTitle} • ${employee.weeklyHours} h, ${employee.schedule}"
            )) {
                commit(ObjectiveDeliveryGameRules.assignChapterFourTask(current, task, employee))
            }, buttonParams(bottom = 4))
        }
    }

    private fun renderRaisePointChoices(
        current: ObjectiveDeliveryCampaign,
        title: String,
        selected: Int?,
        isEnvelope: Boolean
    ) {
        content.addView(text(title, 15f, bold = true).apply {
            setPadding(0, dp(8), 0, dp(3))
        })
        (0..5).forEach { points ->
            content.addView(actionButton(selectedLabel(
                points == selected,
                "$points point(s)${if (points == 5) " • 5 % maximum individuel" else ""}"
            )) {
                val updated = if (isEnvelope) {
                    ObjectiveDeliveryGameRules.setChapterFourRaiseEnvelope(current, points)
                } else {
                    ObjectiveDeliveryGameRules.setChapterFourIndividualRaise(current, points)
                }
                commit(updated)
            }, buttonParams(bottom = 4))
        }
    }

    private fun renderChapterFourResult(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFourState
    ) {
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterFour(current)
        val ready = board.outcome == ObjectiveDeliveryChapterFourOutcome.TEAM_READY
        val heading = if (ready) "ÉQUIPE ORGANISÉE" else "LE PLAN DOIT ÊTRE AJUSTÉ"
        val explanation = if (ready) {
            "Les tâches correspondent aux compétences et aux horaires. Le congé est couvert ou reporté " +
                "avec une autre date ; la décision d’embauche est cohérente ; la revue et l’enveloppe sont documentées."
        } else {
            "Le bilan indique les points à reprendre. Une absence n’est pas une faute : adapte la couverture, " +
                "explique une autre date ou révise le besoin de renfort."
        }
        content.addView(infoCard(heading, explanation))
        content.addView(checkLine("Dossier client, atelier et expédition couverts", evaluation.tasksCovered))
        content.addView(checkLine("Congé accepté avec remplacement ou autre date proposée", evaluation.leaveCovered))
        content.addView(checkLine("Décision de recrutement et termes cohérents", evaluation.hiringDecisionCoherent))
        content.addView(checkLine("Revue annuelle fondée sur des faits discutés", evaluation.annualReviewComplete))
        content.addView(checkLine("Attribution comprise dans l’enveloppe et sous le plafond de 5 % du jeu",
            evaluation.raisesWithinEnvelope))

        val replay = current.replaySession?.chapter == 4
        if (ready && replay) {
            content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
                commit(ObjectiveDeliveryGameRules.keepChapterFourReplayResult(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        if (!ready) {
            content.addView(actionButton("CORRIGER LE PLAN") {
                commit(ObjectiveDeliveryGameRules.correctChapterFourPlan(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 4") {
            commit(ObjectiveDeliveryGameRules.replayChapterFour(current))
        }, buttonParams(top = if (ready && replay) 2 else 8, bottom = 8))
    }

    private fun renderChapterFive(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFiveState
    ) {
        when (board.phase) {
            ObjectiveDeliveryChapterFivePhase.PROCUREMENT -> renderChapterFiveProcurement(current, board)
            ObjectiveDeliveryChapterFivePhase.RESULT -> renderChapterFiveResult(current, board)
        }
    }

    private fun renderChapterFiveProcurement(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFiveState
    ) {
        val profile = current.companyModel.chapterFiveProfile()
        content.addView(infoCard(
            "OBJECTIF DU TABLEAU",
            "Vérifie le stock et les commandes déjà réservées, puis choisis une quantité et un fournisseur. " +
                "Le fournisseur habituel annonce un retard ; le fournisseur de secours livre la même matière conforme. " +
                "Les montants et stocks sont fictifs."
        ))
        content.addView(infoCard(
            "STOCK • ${profile.materialLabel}",
            "En stock : ${profile.stockOnHandUnits} • Réservés : ${profile.reservedUnits} • " +
                "En transit : ${profile.inTransitUnits} • Besoin de la commande : ${profile.newOrderUnits}\n" +
                "À commander pour couvrir les engagements : ${profile.purchaseShortfallUnits} unité(s)."
        ))
        content.addView(text("1. Quelle quantité commander ?", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryChapterFiveQuantityChoice.values().forEach { choice ->
            val units = ObjectiveDeliveryGameRules.chapterFiveOrderUnits(current.companyModel, choice)
            val label = when (choice) {
                ObjectiveDeliveryChapterFiveQuantityChoice.SHORT_BY_ONE ->
                    "Commander une unité de moins • $units unité(s)"
                ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED ->
                    "Couvrir le besoin calculé • $units unité(s)"
                ObjectiveDeliveryChapterFiveQuantityChoice.SAFETY_BUFFER ->
                    "Ajouter une petite réserve • $units unité(s)"
            }
            content.addView(actionButton(selectedLabel(choice == board.quantityChoice, label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterFiveQuantity(current, choice))
            }, buttonParams(bottom = 5))
        }

        content.addView(text("2. Quel fournisseur choisir ?", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        val displayedQuantity = board.quantityChoice ?: ObjectiveDeliveryChapterFiveQuantityChoice.EXACT_NEED
        val displayedUnits = ObjectiveDeliveryGameRules.chapterFiveOrderUnits(current.companyModel, displayedQuantity)
        ObjectiveDeliveryChapterFiveSupplier.values().forEach { supplier ->
            val orderCost = ObjectiveDeliveryGameRules.chapterFiveOrderCostCents(
                current.companyModel, supplier, displayedQuantity
            )
            val label = "${supplier.displayName} • ${supplier.arrivalDays} jours • " +
                "${formatEuro(orderCost)} pour $displayedUnits unité(s)"
            content.addView(actionButton(selectedLabel(supplier == board.supplier, label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterFiveSupplier(current, supplier))
            }, buttonParams(bottom = 5))
        }
        content.addView(infoCard(
            "BUDGET D’ACHAT DU TABLEAU",
            "Jusqu’à ${formatEuro(profile.purchaseBudgetCents)}. Date limite d’approvisionnement : " +
                "${profile.procurementDeadlineDays} jours. Compare le coût, le délai et le stock restant."
        ))
        content.addView(actionButton("VALIDER LE PLAN D’APPROVISIONNEMENT") {
            commit(ObjectiveDeliveryGameRules.submitChapterFivePlan(current))
        }.apply {
            isEnabled = board.quantityChoice != null && board.supplier != null
        }, buttonParams(top = 12, bottom = 8))
    }

    private fun renderChapterFiveResult(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFiveState
    ) {
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterFive(current)
        val ready = board.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY
        content.addView(infoCard(
            if (ready) "APPROVISIONNEMENT PRÊT" else "LE PLAN D’ACHAT DOIT ÊTRE AJUSTÉ",
            if (ready) {
                "La quantité couvre les engagements, arrive avant la date limite et reste dans le budget simulé."
            } else {
                "Le bilan indique si le retard fournisseur, le manque de stock ou le coût bloque la commande. " +
                    "Tu peux corriger le choix sans perdre les autres décisions."
            }
        ))
        content.addView(checkLine(
            "Quantité suffisante : ${evaluation.orderedUnits} commandée(s) pour ${evaluation.neededUnits} nécessaire(s)",
            evaluation.stockCoversDemand
        ))
        content.addView(checkLine(
            "Arrivée dans le délai prévu : ${board.supplier?.arrivalDays ?: 0} jour(s)",
            evaluation.supplierOnTime
        ))
        content.addView(checkLine(
            "Achat dans le budget : ${formatEuro(evaluation.orderCostCents)} / " +
                formatEuro(current.companyModel.chapterFiveProfile().purchaseBudgetCents),
            evaluation.purchaseWithinBudget
        ))

        val replay = current.replaySession?.chapter == 5
        if (ready && replay) {
            content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
                commit(ObjectiveDeliveryGameRules.keepChapterFiveReplayResult(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        if (!ready) {
            content.addView(actionButton("CORRIGER LE PLAN") {
                commit(ObjectiveDeliveryGameRules.correctChapterFivePlan(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 5") {
            commit(ObjectiveDeliveryGameRules.replayChapterFive(current))
        }, buttonParams(top = if (ready && replay) 2 else 8, bottom = 8))
    }

    private fun renderChapterSix(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSixState
    ) {
        when (board.phase) {
            ObjectiveDeliveryChapterSixPhase.PLANNING -> renderChapterSixPlan(current, board)
            ObjectiveDeliveryChapterSixPhase.QUALITY_CONTROL -> renderChapterSixInspection(current, board)
            ObjectiveDeliveryChapterSixPhase.CORRECTION -> renderChapterSixCorrection(current, board)
            ObjectiveDeliveryChapterSixPhase.RESULT -> renderChapterSixResult(current, board)
        }
    }

    private fun renderChapterSixPlan(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSixState
    ) {
        val profile = current.companyModel.chapterSixProfile()
        content.addView(infoCard(
            "OBJECTIF DU TABLEAU",
            "Organise la fabrication selon la date promise, puis contrôle les critères convenus avant toute expédition. " +
                "Le scénario n’ajoute pas d’heures supplémentaires : tu peux gagner du temps en préparant en parallèle les tâches indépendantes."
        ))
        content.addView(infoCard(
            "COMMANDE ET ÉQUIPE",
            "Commande : ${current.companyModel.request}. La fabrication est confiée à ${current.chapterFour.productionAssignee?.displayName ?: "l’équipe de production"}. " +
                "Délai convenu : ${current.companyModel.requestedDays} jours."
        ))
        content.addView(text("1. Choisis l’organisation du travail", 17f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        ObjectiveDeliveryChapterSixProductionPlan.values().forEach { plan ->
            val days = when (plan) {
                ObjectiveDeliveryChapterSixProductionPlan.BALANCED_FLOW -> profile.balancedProductionDays
                ObjectiveDeliveryChapterSixProductionPlan.PARALLEL_PREPARATION -> profile.parallelProductionDays
            }
            val label = "${plan.displayName} • estimation $days jours\n${plan.description}"
            content.addView(actionButton(selectedLabel(plan == board.productionPlan, label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterSixProductionPlan(current, plan))
            }, buttonParams(bottom = 6))
        }
        content.addView(actionButton("VALIDER LE PLANNING") {
            commit(ObjectiveDeliveryGameRules.submitChapterSixPlan(current))
        }.apply { isEnabled = board.productionPlan != null }, buttonParams(top = 10, bottom = 8))
    }

    private fun renderChapterSixInspection(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSixState
    ) {
        val profile = current.companyModel.chapterSixProfile()
        content.addView(infoCard(
            "ÉTAPE 2 • CONTRÔLE QUALITÉ",
            "Vérifie ${profile.inspectedItem}. Critères convenus : ${profile.acceptanceCriteria}"
        ))
        ObjectiveDeliveryChapterSixInspectionChoice.values().forEach { choice ->
            content.addView(actionButton(selectedLabel(
                choice == board.inspectionChoice,
                "${choice.displayName}\n${choice.description}"
            )) {
                commit(ObjectiveDeliveryGameRules.chooseChapterSixInspection(current, choice))
            }, buttonParams(bottom = 6))
        }
        content.addView(actionButton("ENREGISTRER LE CONTRÔLE") {
            commit(ObjectiveDeliveryGameRules.submitChapterSixInspection(current))
        }.apply { isEnabled = board.inspectionChoice != null }, buttonParams(top = 10, bottom = 8))
    }

    private fun renderChapterSixCorrection(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSixState
    ) {
        val profile = current.companyModel.chapterSixProfile()
        content.addView(infoCard(
            "NON-CONFORMITÉ REPÉRÉE",
            "${profile.nonconformityDescription} La sortie reste bloquée jusqu’à une reprise contrôlée ou un accord client consigné."
        ))
        ObjectiveDeliveryChapterSixCorrectionChoice.values().forEach { choice ->
            val detail = when (choice) {
                ObjectiveDeliveryChapterSixCorrectionChoice.REWORK_AND_RECHECK ->
                    "${choice.description} Coût estimé : ${formatEuro(profile.reworkCostCents)}."
                ObjectiveDeliveryChapterSixCorrectionChoice.REQUEST_CUSTOMER_DEVIATION ->
                    "${choice.description} Le défaut mineur de ce scénario est accepté et documenté avant expédition."
                ObjectiveDeliveryChapterSixCorrectionChoice.DISPATCH_WITHOUT_CORRECTION -> choice.description
            }
            content.addView(actionButton(selectedLabel(
                choice == board.correctionChoice,
                "${choice.displayName}\n$detail"
            )) {
                commit(ObjectiveDeliveryGameRules.chooseChapterSixCorrection(current, choice))
            }, buttonParams(bottom = 6))
        }
        content.addView(actionButton("VALIDER L’ACTION QUALITÉ") {
            commit(ObjectiveDeliveryGameRules.submitChapterSixCorrection(current))
        }.apply { isEnabled = board.correctionChoice != null }, buttonParams(top = 10, bottom = 8))
    }

    private fun renderChapterSixResult(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSixState
    ) {
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterSix(current)
        val replay = current.replaySession?.chapter == 6
        val ready = evaluation.readyForDispatch
        val title = when (evaluation.outcome) {
            ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH -> "CONFORME APRÈS REPRISE ET CONTRÔLE"
            ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION -> "DÉROGATION CLIENT CONSIGNÉE"
            ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE -> "CONTRÔLE À COMPLÉTER"
            ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD -> "SORTIE BLOQUÉE PAR LA QUALITÉ"
        }
        val summary = when (evaluation.outcome) {
            ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ->
                "La non-conformité a été reprise et le contrôle est fait avant l’expédition."
            ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION ->
                "Le client a accepté l’écart mineur du scénario. L’accord est enregistré avant la sortie."
            ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE ->
                "Le contrôle par échantillon n’a pas révélé l’écart prévu. La commande reste bloquée : complète la liste de contrôle."
            ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD ->
                "L’écart n’est ni corrigé ni accepté par le client. Ne libère pas la commande ; choisis une action sûre."
        }
        content.addView(infoCard(title, summary))
        content.addView(checkLine(
            "Non-conformité repérée avant sortie",
            evaluation.defectDetected
        ))
        val dispositionRecorded = evaluation.outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
            evaluation.dispositionApproved
        content.addView(checkLine(
            if (evaluation.dispositionApproved) "Accord client écrit consigné" else "Reprise contrôlée effectuée",
            dispositionRecorded
        ))
        content.addView(checkLine(
            "Délai annoncé respecté : ${evaluation.estimatedFinishDays} / ${evaluation.promisedDays} jours",
            evaluation.deadlineMet
        ))
        content.addView(infoCard(
            "EFFETS DU CHOIX",
            "Fabrication estimée : ${evaluation.plannedProductionDays} jours. Reprise : ${evaluation.correctionDays} jour(s). " +
                "Coût correctif simulé : ${formatEuro(evaluation.correctionCostCents)}." +
                if (!evaluation.deadlineMet) " Le client devra être informé du décalage avant l’expédition." else ""
        ))

        if (ready && replay) {
            content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
                commit(ObjectiveDeliveryGameRules.keepChapterSixReplayResult(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        if (evaluation.outcome == ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE) {
            content.addView(actionButton("COMPLÉTER LA LISTE DE CONTRÔLE") {
                commit(ObjectiveDeliveryGameRules.retryChapterSixInspection(current))
            }, buttonParams(top = 14, bottom = 8))
        } else if (evaluation.outcome == ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD) {
            content.addView(actionButton("REPRENDRE L’ACTION QUALITÉ") {
                commit(ObjectiveDeliveryGameRules.reopenChapterSixCorrection(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 6") {
            commit(ObjectiveDeliveryGameRules.replayChapterSix(current))
        }, buttonParams(top = if (ready && replay) 2 else 8, bottom = 8))
    }

    private fun renderChapterSeven(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSevenState
    ) {
        when (board.phase) {
            ObjectiveDeliveryChapterSevenPhase.DELIVERY_PLAN -> renderChapterSevenPlan(current, board)
            ObjectiveDeliveryChapterSevenPhase.ORDER_CHECK -> renderChapterSevenOrderCheck(current, board)
            ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM -> renderChapterSevenClaim(current, board)
            ObjectiveDeliveryChapterSevenPhase.RESULT -> renderChapterSevenResult(current, board)
        }
    }

    private fun renderChapterSevenPlan(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSevenState
    ) {
        val profile = current.companyModel.chapterSevenProfile()
        content.addView(infoCard(
            "OBJECTIF DU TABLEAU",
            "Choisis un mode de livraison, puis vérifie la commande à la remise. Une photo de suivi est facultative et ne prouve jamais à elle seule que la commande est complète."
        ))
        content.addView(infoCard("COMMANDE À LIVRER", "${profile.deliveryItem} • ${current.companyModel.request}"))
        ObjectiveDeliveryChapterSevenDeliveryChoice.values().forEach { choice ->
            content.addView(actionButton(selectedLabel(
                choice == board.deliveryChoice,
                "${choice.displayName} • ${choice.arrivalDays} jour(s) • ${formatEuro(choice.costCents)}\n${choice.description}"
            )) {
                commit(ObjectiveDeliveryGameRules.chooseChapterSevenDelivery(current, choice))
            }, buttonParams(bottom = 5))
        }
        content.addView(actionButton(selectedLabel(
            board.photoShared,
            if (board.photoShared) "Photo simulée jointe • toucher pour retirer" else "Joindre une photo de suivi simulée (facultatif)"
        )) {
            commit(ObjectiveDeliveryGameRules.setChapterSevenPhotoSharing(current, !board.photoShared))
        }, buttonParams(top = 8, bottom = 6))
        content.addView(text(
            "Aucune image réelle ni accès à l’appareil photo n’est utilisé. La photo ne remplace pas le comptage, le contrôle qualité ou les documents.",
            13f
        ).apply { alpha = 0.78f; setPadding(dp(4), dp(2), dp(4), dp(6)) })
        content.addView(actionButton("CONFIRMER LE PLAN DE LIVRAISON") {
            commit(ObjectiveDeliveryGameRules.submitChapterSevenDeliveryPlan(current))
        }.apply { isEnabled = board.deliveryChoice != null }, buttonParams(top = 8, bottom = 8))
    }

    private fun renderChapterSevenOrderCheck(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSevenState
    ) {
        content.addView(infoCard(
            "VÉRIFIER AVANT LA REMISE",
            "La commande reste partielle tant que chaque élément convenu n’est pas contrôlé. Une photo peut montrer un colis incomplet."
        ))
        fun check(label: String, checked: Boolean, update: (Boolean) -> ObjectiveDeliveryCampaign) {
            content.addView(actionButton(selectedLabel(checked, "${if (checked) "✓" else "○"}  $label")) {
                commit(update(!checked))
            }, buttonParams(bottom = 5))
        }
        check("Articles conformes au bon de commande", board.itemsChecked) {
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(current, items = it)
        }
        check("Quantités recomptées", board.quantitiesChecked) {
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(current, quantities = it)
        }
        check("Options et accessoires présents", board.optionsChecked) {
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(current, options = it)
        }
        check("État et contrôle qualité vérifiés", board.qualityChecked) {
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(current, quality = it)
        }
        check("Emballage et documents de livraison prêts", board.deliveryDocumentsChecked) {
            ObjectiveDeliveryGameRules.setChapterSevenOrderCheck(current, documents = it)
        }
        content.addView(text(
            "${listOf(board.itemsChecked, board.quantitiesChecked, board.optionsChecked, board.qualityChecked, board.deliveryDocumentsChecked).count { it }} contrôle(s) sur 5",
            14f, bold = true
        ).apply { setPadding(0, dp(8), 0, dp(4)) })
        content.addView(actionButton("VALIDER LA VÉRIFICATION DE COMMANDE") {
            commit(ObjectiveDeliveryGameRules.submitChapterSevenOrderCheck(current))
        }, buttonParams(top = 6, bottom = 8))
    }

    private fun renderChapterSevenClaim(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSevenState
    ) {
        val profile = current.companyModel.chapterSevenProfile()
        content.addView(infoCard("RETOUR DU CLIENT", profile.customerClaim))
        content.addView(text(
            "Écoute la réserve, relie-la à la commande et propose une suite vérifiable. Une réclamation est un dossier à traiter, pas une faute attribuée automatiquement à un salarié.",
            14f
        ).apply { setPadding(0, dp(10), 0, dp(6)) })
        ObjectiveDeliveryChapterSevenClaimAction.values().forEach { action ->
            content.addView(actionButton(selectedLabel(
                action == board.claimAction,
                "${action.displayName} • coût simulé ${formatEuro(action.costCents)}\n${action.description}"
            )) {
                commit(ObjectiveDeliveryGameRules.chooseChapterSevenClaimAction(current, action))
            }, buttonParams(bottom = 6))
        }
        content.addView(text(profile.complaintFollowUp, 13f).apply {
            alpha = 0.8f
            setPadding(dp(4), dp(6), dp(4), dp(4))
        })
        content.addView(actionButton("ENREGISTRER LA RÉPONSE AU CLIENT") {
            commit(ObjectiveDeliveryGameRules.submitChapterSevenClaimAction(current))
        }.apply { isEnabled = board.claimAction != null }, buttonParams(top = 8, bottom = 8))
    }

    private fun renderChapterSevenResult(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSevenState
    ) {
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterSeven(current)
        val replay = current.replaySession?.chapter == 7
        val resolved = board.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED
        val title = when (board.outcome) {
            ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE -> "COMMANDE À COMPLÉTER"
            ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED -> "RÉCLAMATION SUIVIE"
            ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED -> "RÉSERVE CLIENT ENCORE OUVERTE"
            null -> "BILAN DE LIVRAISON"
        }
        val summary = when (board.outcome) {
            ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE ->
                "La vérification est incomplète. La photo éventuelle ne permet pas de déclarer la commande complète."
            ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED ->
                "La commande a été contrôlée et une réponse datée est prévue. Le dossier peut être clôturé après confirmation du client."
            ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED ->
                "La réclamation a été écartée sans vérification. La réserve reste ouverte et la relation client est fragilisée."
            null -> ""
        }
        content.addView(infoCard(title, summary))
        content.addView(checkLine("Commande vérifiée : ${evaluation.completedChecks} / ${evaluation.totalChecks}", evaluation.orderIsComplete))
        content.addView(checkLine("Photo de suivi facultative jointe", evaluation.photoShared))
        content.addView(checkLine("Réclamation traitée avec suivi", evaluation.claimResolved))
        val deliveryChoice = board.deliveryChoice
        content.addView(infoCard(
            "COÛTS SIMULÉS",
            "Transport : ${formatEuro(evaluation.deliveryCostCents)} • action client : ${formatEuro(evaluation.complaintCostCents)}" +
                (deliveryChoice?.let { "\nMode : ${it.displayName}, ${it.arrivalDays} jour(s)" } ?: "")
        ))

        if (resolved && replay) {
            content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
                commit(ObjectiveDeliveryGameRules.keepChapterSevenReplayResult(current))
            }, buttonParams(top = 14, bottom = 8))
        }
        when (board.outcome) {
            ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE -> content.addView(
                actionButton("COMPLÉTER LA LISTE DE CONTRÔLE") {
                    commit(ObjectiveDeliveryGameRules.retryChapterSevenOrderCheck(current))
                }, buttonParams(top = 14, bottom = 8)
            )
            ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED -> content.addView(
                actionButton("REPRENDRE LA RÉCLAMATION") {
                    commit(ObjectiveDeliveryGameRules.reopenChapterSevenClaim(current))
                }, buttonParams(top = 14, bottom = 8)
            )
            else -> Unit
        }
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 7") {
            commit(ObjectiveDeliveryGameRules.replayChapterSeven(current))
        }, buttonParams(top = if (resolved && replay) 2 else 8, bottom = 8))
    }

    private fun renderChapterEight(
        current: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterEightState
    ) {
        when (board.phase) {
            ObjectiveDeliveryChapterEightPhase.WEEK_PLAN -> renderChapterEightPlan(current, board)
            ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS -> renderChapterEightEvents(current, board)
            ObjectiveDeliveryChapterEightPhase.ANNUAL_REVIEW -> renderChapterEightReview(current, board)
            ObjectiveDeliveryChapterEightPhase.RESULT -> renderChapterEightResult(current, board)
        }
    }

    private fun renderChapterEightPlan(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterEightState) {
        content.addView(infoCard(
            "OBJECTIF DU TABLEAU",
            "Prépare une semaine réaliste pour 100 commandes et 10 personnes. Les critères de prime sont annoncés avant la semaine et ne changent pas après les résultats. Tous les chiffres sont fictifs."
        ))
        ObjectiveDeliveryChapterEightStaffing.values().forEach { option ->
            content.addView(actionButton(selectedLabel(board.staffing == option, option.label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterEightStaffing(current, option))
            }, buttonParams(bottom = 5))
        }
        content.addView(text("Critères de prime hebdomadaire", 16f, bold = true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        ObjectiveDeliveryChapterEightBonusCriteria.values().forEach { option ->
            content.addView(actionButton(selectedLabel(board.bonusCriteria == option, option.label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterEightBonusCriteria(current, option))
            }, buttonParams(bottom = 5))
        }
        content.addView(text("Réponse à la demande d’heures supplémentaires du manager", 16f, bold = true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        ObjectiveDeliveryChapterEightOvertimeChoice.values().forEach { option ->
            content.addView(actionButton(selectedLabel(board.overtimeChoice == option, option.label)) {
                commit(ObjectiveDeliveryGameRules.chooseChapterEightOvertime(current, option))
            }, buttonParams(bottom = 5))
        }
        content.addView(actionButton("ANNONCER LE PLAN DE LA SEMAINE") {
            commit(ObjectiveDeliveryGameRules.submitChapterEightWeekPlan(current))
        }.apply {
            isEnabled = board.staffing != null && board.bonusCriteria != null && board.overtimeChoice != null
        }, buttonParams(top = 10, bottom = 8))
    }

    private fun renderChapterEightEvents(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterEightState) {
        content.addView(infoCard(
            "ÉVÉNEMENTS DE LA SEMAINE",
            "Une panne de transport empêche Noah d’arriver à l’heure. Une erreur est repérée sur une fiche de paie et une tension apparaît entre deux collègues. Les faits restent à vérifier ; aucune personne n’est automatiquement déclarée fautive."
        ))
        renderChapterEightOptions(
            "Absence imprévue", board.absenceResponse, ObjectiveDeliveryChapterEightAbsenceResponse.values()
        ) { ObjectiveDeliveryGameRules.chooseChapterEightAbsenceResponse(current, it) }
        renderChapterEightOptions(
            "Erreur de paie", board.payrollResponse, ObjectiveDeliveryChapterEightPayrollResponse.values()
        ) { ObjectiveDeliveryGameRules.chooseChapterEightPayrollResponse(current, it) }
        renderChapterEightOptions(
            "Tension d’équipe", board.conflictResponse, ObjectiveDeliveryChapterEightConflictResponse.values()
        ) { ObjectiveDeliveryGameRules.chooseChapterEightConflictResponse(current, it) }
        content.addView(text("Événement d’équipe facultatif • participation volontaire", 16f, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        listOf(0, 3, 6, 10).forEach { count ->
            content.addView(actionButton(selectedLabel(
                board.voluntaryEventParticipants == count,
                if (count == 0) "Ne pas organiser de sortie" else "Organiser une sortie • $count participant(s) volontaire(s)"
            )) {
                commit(ObjectiveDeliveryGameRules.setChapterEightEventParticipants(current, count))
            }, buttonParams(bottom = 4))
        }
        content.addView(actionButton("VALIDER LES RÉPONSES DE LA SEMAINE") {
            commit(ObjectiveDeliveryGameRules.submitChapterEightTeamEvents(current))
        }.apply {
            isEnabled = board.absenceResponse != null && board.payrollResponse != null && board.conflictResponse != null
        }, buttonParams(top = 10, bottom = 8))
    }

    private fun <T : Enum<T>> renderChapterEightOptions(
        title: String,
        selected: T?,
        options: Array<T>,
        label: (T) -> String = { (it as? ObjectiveDeliveryChapterEightAbsenceResponse)?.label
            ?: (it as? ObjectiveDeliveryChapterEightPayrollResponse)?.label
            ?: (it as? ObjectiveDeliveryChapterEightConflictResponse)?.label.orEmpty() },
        select: (T) -> ObjectiveDeliveryCampaign
    ) {
        content.addView(text(title, 16f, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) })
        options.forEach { option ->
            content.addView(actionButton(selectedLabel(selected == option, label(option))) {
                commit(select(option))
            }, buttonParams(bottom = 5))
        }
    }

    private fun renderChapterEightReview(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterEightState) {
        val profile = current.companyModel.chapterEightProfile()
        content.addView(infoCard(
            "REVUE ANNUELLE ET PARTAGE",
            "Résultat net simulé après les charges : ${formatEuro(profile.annualNetResultCents)}. Au-dessus de 1 M€, la règle de ce scénario crée une enveloppe collective égale à 7 % de la totalité du résultat, répartie à parts égales entre les salariés concernés. Ce chiffre est une mécanique fictive du jeu."
        ))
        (0..5).forEach { percent ->
            content.addView(actionButton(selectedLabel(
                board.raiseEnvelopePercent == percent,
                "Enveloppe d’augmentations : $percent % de la masse salariale simulée"
            )) {
                commit(ObjectiveDeliveryGameRules.setChapterEightRaiseAllocation(
                    current, percent, board.individualRaisePercent ?: 2
                ))
            }, buttonParams(bottom = 4))
        }
        content.addView(text(
            "Part attribuée à une personne • plafond du tableau : 5 % et respect de l’enveloppe",
            14f, bold = true
        ).apply { setPadding(0, dp(10), 0, dp(4)) })
        (0..6).forEach { percent ->
            content.addView(actionButton(selectedLabel(
                board.individualRaisePercent == percent,
                "$percent %${if (percent > 5) " • dépasse le plafond de 5 %" else ""}"
            )) {
                commit(ObjectiveDeliveryGameRules.setChapterEightRaiseAllocation(
                    current, board.raiseEnvelopePercent ?: 3, percent
                ))
            }, buttonParams(bottom = 4))
        }
        if (profile.profitShareEligible) {
            content.addView(actionButton(selectedLabel(
                board.equalProfitShareConfirmed,
                "${if (board.equalProfitShareConfirmed) "✓ " else "○ "}Confirmer la répartition égale de ${formatEuro(profile.collectiveProfitShareCents)}"
            )) {
                commit(ObjectiveDeliveryGameRules.setChapterEightProfitShareConfirmed(
                    current, !board.equalProfitShareConfirmed
                ))
            }, buttonParams(top = 10, bottom = 6))
        } else {
            content.addView(checkLine("Le résultat reste sous le seuil simulé de 1 M€ : pas d’enveloppe collective", true))
        }
        content.addView(actionButton("VALIDER LE BILAN DE L’ÉQUIPE") {
            commit(ObjectiveDeliveryGameRules.submitChapterEightAnnualReview(current))
        }.apply {
            isEnabled = board.raiseEnvelopePercent != null && board.individualRaisePercent != null
        }, buttonParams(top = 10, bottom = 8))
    }

    private fun renderChapterEightResult(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterEightState) {
        val result = ObjectiveDeliveryGameRules.evaluateChapterEight(current)
        val success = board.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS
        val replay = current.replaySession?.chapter == 8
        content.addView(infoCard(
            if (success) "SEMAINE ET ÉQUIPE BIEN PILOTÉES" else "CERTAINS CHOIX SONT À REVOIR",
            if (success) "Les critères étaient connus d’avance, les événements ont été traités sans représailles et les montants restent dans les limites annoncées."
            else "Le tableau montre les décisions qui fragilisent l’équipe ou la trésorerie. Tu peux recommencer les décisions et comparer les effets."
        ))
        content.addView(checkLine("Effectif cohérent avec 100 commandes", result.staffingMatchesWorkload))
        content.addView(checkLine("Critères de prime équilibrés et annoncés", result.bonusCriteriaBalanced))
        content.addView(checkLine("Heures supplémentaires ciblées ou planning réorganisé", result.overtimeWasControlled))
        content.addView(checkLine("Absence traitée sans pénaliser un événement non vérifié", result.absenceHandledFairly))
        content.addView(checkLine("Erreur de paie vérifiée et corrigée", result.payrollWasCorrected))
        content.addView(checkLine("Tension entendue et traitée", result.conflictAddressed))
        content.addView(checkLine("Augmentation sous 5 % et dans l’enveloppe", result.raisesWithinEnvelope))
        content.addView(infoCard(
            "RÉSULTAT SIMULÉ",
            "Prime hebdomadaire calculée : ${formatEuro(result.weeklyBonusCents)} • sortie facultative : ${result.voluntaryEventParticipants} participant(s) volontaire(s) • partage collectif : ${formatEuro(result.profitShareCents)}"
        ))
        if (success && replay) content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
            commit(ObjectiveDeliveryGameRules.keepChapterEightReplayResult(current))
        }, buttonParams(top = 12, bottom = 8))
        if (!success) content.addView(actionButton("CORRIGER LES DÉCISIONS DU TABLEAU") {
            commit(ObjectiveDeliveryGameRules.retryChapterEightPlan(current))
        }, buttonParams(top = 12, bottom = 8))
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 8") {
            commit(ObjectiveDeliveryGameRules.replayChapterEight(current))
        }, buttonParams(bottom = 8))
    }

    private fun renderChapterNine(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterNineState) {
        when (board.phase) {
            ObjectiveDeliveryChapterNinePhase.INSPECTION -> {
                val profile = current.companyModel.chapterNineProfile()
                content.addView(infoCard(
                    "VISITE DE SÉCURITÉ — PÉRIMÈTRE LIMITÉ",
                    "L’inspection porte sur ${profile.affectedWorkArea} : ${profile.safetyFinding}. Le poste concerné peut être isolé pendant la correction ; les autres tâches sûres peuvent continuer. Le constat et son délai sont visibles."
                ))
                ObjectiveDeliveryChapterNineInspectionResponse.values().forEach { response ->
                    content.addView(actionButton(selectedLabel(board.inspectionResponse == response, response.label)) {
                        commit(ObjectiveDeliveryGameRules.chooseChapterNineInspectionResponse(current, response))
                    }, buttonParams(bottom = 5))
                }
                content.addView(actionButton("RÉPONDRE À LA VISITE") {
                    commit(ObjectiveDeliveryGameRules.submitChapterNineInspection(current))
                }.apply { isEnabled = board.inspectionResponse != null }, buttonParams(top = 8, bottom = 8))
            }
            ObjectiveDeliveryChapterNinePhase.SAFETY_ACTION -> {
                content.addView(infoCard(
                    "PROTÉGER LES PERSONNES",
                    "Le poste signalé reste le seul périmètre arrêté par le scénario. Aucun objectif de production ne justifie de continuer une tâche dangereuse. Une personne qui alerte n’est pas pénalisée."
                ))
                ObjectiveDeliveryChapterNineSafetyResponse.values().forEach { response ->
                    content.addView(actionButton(selectedLabel(board.safetyResponse == response, response.label)) {
                        commit(ObjectiveDeliveryGameRules.chooseChapterNineSafetyResponse(current, response))
                    }, buttonParams(bottom = 5))
                }
                content.addView(text(
                    "Un personnage fictif propose d’influencer la visite. Le jeu n’affiche aucune méthode ni aucun montant : refuse et signale la tentative.",
                    14f
                ).apply { setPadding(0, dp(10), 0, dp(4)) })
                ObjectiveDeliveryChapterNineIntegrityResponse.values().forEach { response ->
                    content.addView(actionButton(selectedLabel(board.integrityResponse == response, response.label)) {
                        commit(ObjectiveDeliveryGameRules.chooseChapterNineIntegrityResponse(current, response))
                    }, buttonParams(bottom = 5))
                }
                content.addView(actionButton("VALIDER LA MISE EN SÉCURITÉ") {
                    commit(ObjectiveDeliveryGameRules.submitChapterNineSafetyAction(current))
                }.apply { isEnabled = board.safetyResponse != null && board.integrityResponse != null },
                    buttonParams(top = 8, bottom = 8))
            }
            ObjectiveDeliveryChapterNinePhase.CORRECTION -> {
                content.addView(infoCard(
                    "DÉLAI DE MISE EN CONFORMITÉ",
                    "Le poste reste temporairement fermé pendant la correction. Le scénario accorde 5 jours, puis exige une vérification avant autorisation de reprise. Si le délai judiciaire expire sans correction, cette branche se termine par une fermeture définitive."
                ))
                ObjectiveDeliveryChapterNineCorrectionChoice.values().forEach { choice ->
                    content.addView(actionButton(selectedLabel(board.correctionChoice == choice, choice.label)) {
                        commit(ObjectiveDeliveryGameRules.chooseChapterNineCorrection(current, choice))
                    }, buttonParams(bottom = 6))
                }
                content.addView(actionButton("ENREGISTRER LA DÉCISION DE CORRECTION") {
                    commit(ObjectiveDeliveryGameRules.submitChapterNineCorrection(current))
                }.apply { isEnabled = board.correctionChoice != null }, buttonParams(top = 8, bottom = 8))
            }
            ObjectiveDeliveryChapterNinePhase.RESULT -> renderChapterNineResult(current, board)
        }
    }

    private fun renderChapterNineResult(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterNineState) {
        val result = ObjectiveDeliveryGameRules.evaluateChapterNine(current)
        val success = result.outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION
        val replay = current.replaySession?.chapter == 9
        val title = when (result.outcome) {
            ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION -> "REPRISE AUTORISÉE APRÈS VÉRIFICATION"
            ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD -> "POSTE TOUJOURS EN ARRÊT TEMPORAIRE"
            ObjectiveDeliveryChapterNineOutcome.CLOSED_AFTER_DEADLINE -> "BRANCHE FERMÉE APRÈS EXPIRATION DU DÉLAI"
        }
        content.addView(infoCard(title, when (result.outcome) {
            ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION ->
                "Les faits sont consignés, le poste a été isolé, la tentative d’influence a été refusée et la correction a été vérifiée dans les 5 jours."
            ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD ->
                "L’activité concernée reste arrêtée tant que le dossier, la sécurité ou l’intégrité de la visite doit être repris. Les activités sûres restent séparées."
            ObjectiveDeliveryChapterNineOutcome.CLOSED_AFTER_DEADLINE ->
                "Le délai explicite du scénario est dépassé sans correction vérifiée. Cette branche se termine ; aucun hasard ne déclenche la fermeture."
        }))
        content.addView(checkLine("Visite coopérative et faits consignés", result.inspectionWasTransparent))
        content.addView(checkLine("Poste concerné arrêté pendant l’examen", result.affectedWorkWasStopped))
        content.addView(checkLine("Tentative d’influence refusée et signalée", result.integrityWasProtected))
        content.addView(checkLine("Correction achevée dans le délai de 5 jours", result.correctionWithinDeadline))
        if (success && replay) content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
            commit(ObjectiveDeliveryGameRules.keepChapterNineReplayResult(current))
        }, buttonParams(top = 12, bottom = 8))
        if (result.outcome == ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD) {
            content.addView(actionButton("REPRENDRE LES DÉCISIONS DE CONFORMITÉ") {
                commit(ObjectiveDeliveryGameRules.retryChapterNineCompliance(current))
            }, buttonParams(top = 12, bottom = 8))
        }
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 9") {
            commit(ObjectiveDeliveryGameRules.replayChapterNine(current))
        }, buttonParams(bottom = 8))
    }

    private fun renderChapterTen(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterTenState) {
        when (board.phase) {
            ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN -> renderChapterTenPlan(current, board)
            ObjectiveDeliveryChapterTenPhase.RESULT -> renderChapterTenResult(current, board)
        }
    }

    private fun renderChapterTenPlan(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterTenState) {
        val profile = current.companyModel.chapterTenProfile()
        content.addView(infoCard(
            "DERNIER TABLEAU — TROIS COMMANDES, PLUSIEURS CHOCS",
            "Une hausse fictive des matières, de l’énergie et du transport réduit la trésorerie disponible. Protège les engagements, revois les nouveaux devis et garde une réserve pour les échéances."
        ))
        content.addView(infoCard("COMMANDES EN COURS", profile.orders.mapIndexed { index, order -> "${index + 1}. $order" }.joinToString("\n")))
        content.addView(infoCard(
            "MATIÈRE SOUS TENSION",
            "${profile.criticalMaterial} • fournisseur habituel retardé • fournisseur alternatif qualifié disponible. Réserve de liquidité minimale du scénario : 100 000 €."
        ))
        renderChapterTenOptions("Fournisseur", board.supplierChoice, ObjectiveDeliveryChapterTenSupplierChoice.values()) {
            ObjectiveDeliveryGameRules.chooseChapterTenSupplier(current, it)
        }
        renderChapterTenOptions("Priorité entre les commandes", board.priorityChoice,
            ObjectiveDeliveryChapterTenPriorityChoice.values()) {
            ObjectiveDeliveryGameRules.chooseChapterTenPriority(current, it)
        }
        renderChapterTenOptions("Prix et engagements", board.pricingChoice, ObjectiveDeliveryChapterTenPricingChoice.values()) {
            ObjectiveDeliveryGameRules.chooseChapterTenPricing(current, it)
        }
        renderChapterTenOptions("Trésorerie", board.cashChoice, ObjectiveDeliveryChapterTenCashChoice.values()) {
            ObjectiveDeliveryGameRules.chooseChapterTenCashPlan(current, it)
        }
        val evaluation = ObjectiveDeliveryGameRules.evaluateChapterTen(current)
        content.addView(infoCard(
            "TRÉSORERIE PRÉVISIONNELLE DU TABLEAU",
            "Après paie, fournisseurs, énergie et transport : ${formatEuro(evaluation.projectedCashCents)}. Seuil de réserve : ${formatEuro(evaluation.liquidityReserveCents)}. Les valeurs sont fictives."
        ))
        content.addView(actionButton("RÉSOUDRE LE PLAN DE FIN DE CAMPAGNE") {
            commit(ObjectiveDeliveryGameRules.submitChapterTenPlan(current))
        }.apply {
            isEnabled = board.supplierChoice != null && board.priorityChoice != null && board.pricingChoice != null && board.cashChoice != null
        }, buttonParams(top = 10, bottom = 8))
    }

    private fun <T : Enum<T>> renderChapterTenOptions(
        title: String,
        selected: T?,
        options: Array<T>,
        label: (T) -> String = { (it as? ObjectiveDeliveryChapterTenSupplierChoice)?.label
            ?: (it as? ObjectiveDeliveryChapterTenPriorityChoice)?.label
            ?: (it as? ObjectiveDeliveryChapterTenPricingChoice)?.label
            ?: (it as? ObjectiveDeliveryChapterTenCashChoice)?.label.orEmpty() },
        choose: (T) -> ObjectiveDeliveryCampaign
    ) {
        content.addView(text(title, 16f, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) })
        options.forEach { option ->
            content.addView(actionButton(selectedLabel(selected == option, label(option))) {
                commit(choose(option))
            }, buttonParams(bottom = 5))
        }
    }

    private fun renderChapterTenResult(current: ObjectiveDeliveryCampaign, board: ObjectiveDeliveryChapterTenState) {
        val result = ObjectiveDeliveryGameRules.evaluateChapterTen(current)
        val success = board.outcome == ObjectiveDeliveryChapterTenOutcome.CAMPAIGN_WON
        val replay = current.replaySession?.chapter == 10
        content.addView(infoCard(
            if (success) "CAMPAGNE TERMINÉE" else "LE PLAN MET EN RISQUE LA TRÉSORERIE OU LES CLIENTS",
            if (success) "Les trois commandes sont protégées, les prix convenus sont respectés et la réserve de trésorerie reste disponible après les chocs simulés."
            else "Le bilan indique les engagements manqués, l’effet du fournisseur ou les coûts qui font passer la réserve sous le seuil. Ajuste le plan et recommence."
        ))
        content.addView(checkLine("Les trois commandes restent dans le planning", result.allOrdersProtected))
        content.addView(checkLine("Approvisionnement compatible avec les délais", result.supplierOnTime))
        content.addView(checkLine("Les commandes acceptées gardent leur prix convenu", result.pricingRespectsAgreements))
        content.addView(checkLine("Paie, fournisseurs et sécurité couverts", result.obligationsRemainCovered))
        content.addView(infoCard(
            "RÉSERVE FINALE",
            "Trésorerie projetée : ${formatEuro(result.projectedCashCents)} • réserve minimale : ${formatEuro(result.liquidityReserveCents)}"
        ))
        if (success && replay) content.addView(actionButton("GARDER CE RÉSULTAT DANS LA CAMPAGNE") {
            commit(ObjectiveDeliveryGameRules.keepChapterTenReplayResult(current))
        }, buttonParams(top = 12, bottom = 8))
        content.addView(actionButton(if (replay) "RECOMMENCER CE REJEU" else "REJOUER LE CHAPITRE 10") {
            commit(ObjectiveDeliveryGameRules.replayChapterTen(current))
        }, buttonParams(bottom = 8))
    }

    private fun accountAndSaveCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.hp_panel)
        }
        box.addView(text("SAUVEGARDE AUTOMATIQUE", 14f, bold = true))
        val status = text(saveStatus, 13f).apply {
            alpha = 0.82f
            tag = SAVE_STATUS_TAG
            setPadding(0, dp(4), 0, dp(6))
        }
        box.addView(status)
        if (FirebaseAuth.getInstance().currentUser == null) {
            box.addView(actionButton("SE CONNECTER À GOOGLE POUR SYNCHRONISER") {
                startActivity(Intent(this, FirebaseAccountActivity::class.java))
            }, buttonParams(top = 4))
        } else {
            box.addView(text("Compte Google connecté • la partie se copie dans Firebase.", 12f).apply {
                alpha = 0.8f
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        box.addView(text("La partie reste enregistrée sur le téléphone. Aucun pointage ni salaire réel n’est utilisé.", 12f).apply {
            alpha = 0.72f
            setPadding(0, dp(4), 0, 0)
        })
        return box.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
    }

    private fun updateSaveStatus() {
        val textView = content.findViewWithTag<TextView>(SAVE_STATUS_TAG)
        textView?.text = saveStatus
    }

    private fun openModel(model: ObjectiveDeliveryCompanyModel) {
        cloudSync.stop()
        store.setCurrentModel(model)
        campaign = store.load(model) ?: store.save(ObjectiveDeliveryCampaign(model))
        render()
        cloudSync.observe(model)
    }

    private fun commit(next: ObjectiveDeliveryCampaign) {
        val previous = campaign
        if (previous == next) return
        val saved = store.save(next)
        campaign = if (FirebaseAuth.getInstance().currentUser != null) {
            cloudSync.appendAfterLocalSave(saved).also(store::saveExact)
        } else {
            saved
        }
        saveStatus = if (FirebaseAuth.getInstance().currentUser == null) {
            "Partie enregistrée sur cet appareil. Connecte Google pour la copie Firebase."
        } else {
            "Partie enregistrée sur cet appareil • synchronisation Firebase en cours…"
        }
        render()
    }

    private fun showConflict(
        local: ObjectiveDeliveryCampaign?,
        tips: List<ObjectiveDeliveryCloudSnapshot>
    ) {
        if (isFinishing || isDestroyed || conflictDialog?.isShowing == true) return
        val model = local?.companyModel ?: tips.firstOrNull()?.campaign?.companyModel ?: return
        val sortedTips = tips.sortedByDescending { it.campaign.revision }
        if (local == null && sortedTips.size > 1) {
            val labels = sortedTips.mapIndexed { index, tip ->
                "Version ${index + 1} • chapitre ${tip.campaign.unlockedChapter} • révision ${tip.campaign.revision}"
            }.toTypedArray()
            conflictDialog = AlertDialog.Builder(this)
                .setTitle("Plusieurs copies Firebase")
                .setMessage("Choisis la version à reprendre. Les autres copies resteront conservées.")
                .setItems(labels) { _, which ->
                    cloudSync.resolveConflict(
                        model = model,
                        local = null,
                        cloudTips = sortedTips,
                        chooseCloud = true,
                        preferredCloudSnapshotId = sortedTips[which].id
                    )
                }
                .setOnDismissListener { conflictDialog = null }
                .show()
            return
        }
        conflictDialog = AlertDialog.Builder(this)
            .setTitle("Deux versions de la partie")
            .setMessage("Une copie locale et une copie Firebase ont avancé différemment. Les deux seront conservées pour éviter d’effacer une progression.")
            .setPositiveButton("GARDER CET APPAREIL") { _, _ ->
                cloudSync.resolveConflict(model, local, sortedTips, chooseCloud = false)
            }
            .setNegativeButton("REPRENDRE FIREBASE") { _, _ ->
                cloudSync.resolveConflict(model, local, sortedTips, chooseCloud = true)
            }
            .setOnDismissListener { conflictDialog = null }
            .show()
    }

    private fun infoCard(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.hp_panel)
        addView(text(title, 13f, bold = true).apply { alpha = 0.78f })
        addView(text(body, 15f).apply { setPadding(0, dp(4), 0, 0) })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
    }

    private fun checkLine(label: String, passed: Boolean): TextView = text(
        "${if (passed) "✓" else "•"}  $label${if (passed) "" else " — à améliorer"}",
        14f
    ).apply {
        setPadding(dp(4), dp(3), 0, dp(3))
        alpha = if (passed) 1f else 0.8f
    }

    private fun selectedLabel(selected: Boolean, value: String) =
        (if (selected) "✓  " else "") + value

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundResource(R.drawable.hp_panel)
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, bold: Boolean = false, centered: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (centered) gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun buttonParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56)
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun formatEuro(cents: Int): String =
        NumberFormat.getCurrencyInstance(Locale.FRANCE).format(cents / 100.0)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SAVE_STATUS_TAG = "objective_delivery_save_status"
    }
}
