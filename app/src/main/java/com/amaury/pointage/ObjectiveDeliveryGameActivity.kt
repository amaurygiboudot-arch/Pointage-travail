package com.amaury.pointage

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class ObjectiveDeliveryGameActivity : Activity() {
    private lateinit var root: LinearLayout
    private var state: ObjectiveDeliveryState? = null
    private var practiceMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        state = ObjectiveDeliveryGameStore.loadActive(this)
        render()

        if (state == null) {
            ObjectiveDeliveryGameStore.restoreLatestFromCloud(this) { restored ->
                if (!isFinishing && state == null && restored != null) {
                    state = restored
                    render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!practiceMode) {
            state = ObjectiveDeliveryGameStore.loadActive(this) ?: state
            render()
        }
    }

    private fun render() {
        root.removeAllViews()
        addHeader()

        val current = state
        if (current == null) {
            renderCompanyChooser()
        } else {
            renderCampaign(current)
        }
    }

    private fun addHeader() {
        root.addView(TextView(this).apply {
            text = "OBJECTIF LIVRAISON"
            textSize = 25f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Du premier contact à la livraison"
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(14))
        })
    }

    private fun renderCompanyChooser() {
        addCard {
            addTitle("CHOISIS TON ENTREPRISE")
            addBody(
                "Chaque campagne est indépendante. Le premier chapitre t'apprend à qualifier " +
                    "un besoin, construire une offre simple et comprendre pourquoi une vente se gagne ou se perd."
            )
        }

        ObjectiveCompanyType.entries.forEach { type ->
            val savedCampaign = ObjectiveDeliveryGameStore.load(this, type)
            addCard {
                addTitle(type.title.uppercase())
                addBody(type.subtitle)
                if (savedCampaign != null) {
                    val status = when (savedCampaign.outcome) {
                        ObjectiveOutcome.WON ->
                            "Chapitre 1 gagné — chapitre " + savedCampaign.unlockedChapter + " débloqué"
                        ObjectiveOutcome.REWORK -> "Offre à retravailler"
                        ObjectiveOutcome.LOST -> "Vente perdue — bilan disponible"
                        ObjectiveOutcome.IN_PROGRESS ->
                            "Partie en cours — étape " + (savedCampaign.step + 1) + "/3"
                    }
                    addSmall(status)
                }
                addAction(if (savedCampaign == null) "COMMENCER" else "REPRENDRE") {
                    if (savedCampaign != null) {
                        ObjectiveDeliveryGameStore.setActive(this@ObjectiveDeliveryGameActivity, type)
                        state = savedCampaign
                    } else {
                        state = createCampaign(type).also {
                            ObjectiveDeliveryGameStore.save(this@ObjectiveDeliveryGameActivity, it)
                        }
                    }
                    practiceMode = false
                    render()
                }
            }
        }
    }

    private fun renderCampaign(current: ObjectiveDeliveryState) {
        val scenario = ObjectiveDeliveryGameEngine.scenario(current.companyType)

        addCard {
            addTitle(current.companyType.title.uppercase())
            addSmall(
                if (practiceMode) {
                    "Mode entraînement — cette tentative ne remplace pas ta campagne."
                } else {
                    ObjectiveDeliveryGameStore.syncStatus(this@ObjectiveDeliveryGameActivity).label
                }
            )
            addBody("Chapitre 1 — Premier contact et devis")
        }

        if (current.outcome == ObjectiveOutcome.IN_PROGRESS) {
            addIndicators(current)
            addRiskCard(current)
            when (current.step) {
                0 -> renderFirstContact(current, scenario)
                1 -> renderDiscovery(current, scenario)
                2 -> renderQuote(current, scenario)
                else -> renderResult(current)
            }
        } else {
            renderResult(current)
        }

        addAction("CHOISIR UNE AUTRE ENTREPRISE") {
            state = null
            practiceMode = false
            render()
        }
    }

    private fun addIndicators(current: ObjectiveDeliveryState) {
        addCard {
            addTitle("TABLEAU DE BORD")
            addBody("Étape " + (current.step + 1) + "/3")
            addGauge("Confiance client", current.clientTrust)
            addGauge("Besoin compris", current.needCompleteness)
        }
    }

    private fun addRiskCard(current: ObjectiveDeliveryState) {
        addCard {
            addTitle("RISQUES À SURVEILLER")
            ObjectiveDeliveryGameEngine.riskSummary(current).forEach { risk ->
                addBody("• " + risk)
            }
        }
    }

    private fun renderFirstContact(current: ObjectiveDeliveryState, scenario: ObjectiveScenario) {
        addCard {
            addTitle("NOUVEAU PROSPECT")
            addBody(scenario.clientTitle)
            addBody(scenario.clientNeed)
            addSmall(
                "Objectif : prendre en charge le prospect sans promettre ce que " +
                    "l'entreprise ne connaît pas encore."
            )
            addAction("RÉPONDRE MAINTENANT") {
                decide(current, ObjectiveDecision.ANSWER_NOW)
            }
            addAction("DEMANDER LES INFORMATIONS MANQUANTES") {
                decide(current, ObjectiveDecision.ASK_INFORMATION)
            }
            addAction("LAISSER EN ATTENTE") {
                decide(current, ObjectiveDecision.LEAVE_WAITING)
            }
        }
    }

    private fun renderDiscovery(current: ObjectiveDeliveryState, scenario: ObjectiveScenario) {
        addCard {
            addTitle("COMPRENDRE LE BESOIN")
            addBody(scenario.clientTitle)
            addSmall(
                "Les informations manquantes rendent le devis plus fragile et augmentent " +
                    "le risque de modification tardive."
            )
            addAction("POSER TOUTES LES QUESTIONS ESSENTIELLES") {
                decide(current, ObjectiveDecision.FULL_DISCOVERY)
            }
            addAction("PROPOSER UNE SOLUTION STANDARD") {
                decide(current, ObjectiveDecision.STANDARD_SOLUTION)
            }
            addAction("ORGANISER UNE VISITE / DIAGNOSTIC") {
                decide(current, ObjectiveDecision.SITE_VISIT)
            }
        }
    }

    private fun renderQuote(current: ObjectiveDeliveryState, scenario: ObjectiveScenario) {
        val discountPrice = (scenario.referencePrice * 0.90).toInt()
        val premiumPrice = (scenario.referencePrice * 1.12).toInt()
        val premiumDelay = max(2, scenario.desiredDays - 5)

        addCard {
            addTitle("CONSTRUIRE LE DEVIS")
            addBody(
                "Coût simulé : " + euro(scenario.baseCost) +
                    " — budget client annoncé : " + euro(scenario.budgetMax) +
                    " — délai souhaité : " + scenario.desiredDays + " jours."
            )
            addSmall(
                "Le prix le plus bas n'est pas toujours le meilleur choix : marge, " +
                    "délai et valeur perçue comptent ensemble."
            )

            addAction(
                "OFFRE ÉQUILIBRÉE\n" + euro(scenario.referencePrice) +
                    " • " + scenario.desiredDays + " jours"
            ) {
                decide(current, ObjectiveDecision.VALUE_OFFER)
            }
            addAction(
                "REMISE COMMERCIALE\n" + euro(discountPrice) +
                    " • " + (scenario.desiredDays + 2) + " jours"
            ) {
                decide(current, ObjectiveDecision.DISCOUNT_OFFER)
            }
            addAction(
                "OFFRE ACCÉLÉRÉE\n" + euro(premiumPrice) +
                    " • " + premiumDelay + " jours"
            ) {
                decide(current, ObjectiveDecision.FAST_PREMIUM)
            }
        }
    }

    private fun renderResult(current: ObjectiveDeliveryState) {
        val title = when (current.outcome) {
            ObjectiveOutcome.WON -> "COMMANDE GAGNÉE"
            ObjectiveOutcome.REWORK -> "OFFRE À RETRAVAILLER"
            ObjectiveOutcome.LOST -> "VENTE PERDUE"
            ObjectiveOutcome.IN_PROGRESS -> "PARTIE EN COURS"
        }

        addCard {
            addTitle(title)
            if (current.quotedPrice > 0) {
                addBody(
                    "Devis : " + euro(current.quotedPrice) +
                        " • délai " + current.quotedDelayDays + " jours • marge simulée " +
                        euro(current.marginAmount) + "."
                )
            }
            current.history.takeLast(4).forEach { addBody("• " + it) }

            if (current.outcome == ObjectiveOutcome.WON) {
                addBody(
                    "Le chapitre 2 est débloqué. La campagne conserve l'entreprise " +
                        "et ses conséquences."
                )
                addAction("CHAPITRE 2 — DÉBLOQUÉ") {
                    Toast.makeText(
                        this@ObjectiveDeliveryGameActivity,
                        "Le moteur du chapitre 2 est le prochain lot à brancher.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (!practiceMode) {
                addBody(
                    "Le chapitre suivant reste verrouillé tant que l'objectif annoncé " +
                        "n'est pas atteint."
                )
            }

            if (!practiceMode) {
                addAction("REJOUER LE CHAPITRE EN ENTRAÎNEMENT") {
                    val practiceId =
                        "practice-" + current.companyType.id + "-" + System.nanoTime()
                    state = ObjectiveDeliveryGameEngine.newCampaign(
                        current.companyType,
                        practiceId,
                        current.seed
                    )
                    practiceMode = true
                    render()
                }
            } else {
                addAction("REVENIR À LA CAMPAGNE") {
                    practiceMode = false
                    state = ObjectiveDeliveryGameStore.load(
                        this@ObjectiveDeliveryGameActivity,
                        current.companyType
                    )
                    render()
                }
            }
        }
    }

    private fun decide(current: ObjectiveDeliveryState, decision: ObjectiveDecision) {
        val next = runCatching { ObjectiveDeliveryGameEngine.apply(current, decision) }
            .getOrElse {
                Toast.makeText(
                    this,
                    it.message ?: "Décision impossible",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        state = next
        if (!practiceMode) ObjectiveDeliveryGameStore.save(this, next)
        render()
    }

    private fun createCampaign(type: ObjectiveCompanyType): ObjectiveDeliveryState {
        val campaignId = type.id + "-" + System.currentTimeMillis()
        val seed = campaignId.hashCode().toLong() * 31L + type.ordinal
        return ObjectiveDeliveryGameEngine.newCampaign(type, campaignId, seed)
    }

    private fun addCard(build: LinearLayout.() -> Unit) {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(28, 255, 255, 255))
                setStroke(dp(1), Color.argb(70, 214, 168, 75))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            build()
        })
    }

    private fun LinearLayout.addTitle(value: String) {
        addView(TextView(this@ObjectiveDeliveryGameActivity).apply {
            text = value
            textSize = 17f
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun LinearLayout.addBody(value: String) {
        addView(TextView(this@ObjectiveDeliveryGameActivity).apply {
            text = value
            textSize = 15f
            setPadding(0, 0, 0, dp(7))
        })
    }

    private fun LinearLayout.addSmall(value: String) {
        addView(TextView(this@ObjectiveDeliveryGameActivity).apply {
            text = value
            textSize = 13f
            alpha = 0.82f
            setPadding(0, 0, 0, dp(7))
        })
    }

    private fun LinearLayout.addGauge(label: String, value: Int) {
        addView(TextView(this@ObjectiveDeliveryGameActivity).apply {
            text = label + " : " + value + " %"
            textSize = 14f
        })
        addView(
            ProgressBar(
                this@ObjectiveDeliveryGameActivity,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = value.coerceIn(0, 100)
                contentDescription = label + ", " + value + " pour cent"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(14)
                ).apply { bottomMargin = dp(8) }
            }
        )
    }

    private fun LinearLayout.addAction(label: String, action: () -> Unit) {
        addView(Button(this@ObjectiveDeliveryGameActivity).apply {
            text = label
            isAllCaps = false
            minHeight = touchTargetPx()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
    }

    private fun addAction(label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = touchTargetPx()
            setBackgroundResource(R.drawable.hp_panel)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })
    }

    private fun touchTargetPx(): Int =
        ceil(48.0 * resources.displayMetrics.density).toInt()

    private fun dp(value: Int): Int =
        ceil(value.toDouble() * resources.displayMetrics.density).toInt()

    private fun euro(value: Int): String =
        NumberFormat.getCurrencyInstance(Locale.FRANCE).format(value)
}
