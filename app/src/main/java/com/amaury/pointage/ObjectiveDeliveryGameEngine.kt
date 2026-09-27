package com.amaury.pointage

import java.util.Random
import kotlin.math.max
import kotlin.math.roundToInt

enum class ObjectiveCompanyType(
    val id: String,
    val title: String,
    val subtitle: String,
    val orderType: String,
    val keyRoles: String,
    val initialDifficulty: String
) {
    WORKSHOP(
        "workshop",
        "Atelier de fabrication sur commande",
        "Produits personnalisés, étude, matières, fabrication et contrôle.",
        "Commandes : produits sur mesure avec dimensions et options",
        "Métiers clés : commerce, étude, fabrication",
        "Difficulté initiale : intermédiaire"
    ),
    RETAIL(
        "retail",
        "Commerce et distribution",
        "Produits, stock, disponibilité, conseil et livraison client.",
        "Commandes : équipements et articles issus du stock",
        "Métiers clés : vente, stock, logistique",
        "Difficulté initiale : accessible"
    ),
    SERVICES(
        "services",
        "Prestations et interventions",
        "Diagnostic, planification, intervention et compte rendu client.",
        "Commandes : diagnostics et interventions planifiées",
        "Métiers clés : accueil, planification, technicien",
        "Difficulté initiale : accessible à intermédiaire"
    );

    companion object {
        fun fromId(id: String): ObjectiveCompanyType =
            entries.firstOrNull { it.id == id } ?: WORKSHOP
    }
}

enum class ObjectiveDecision {
    ANSWER_NOW,
    ASK_INFORMATION,
    LEAVE_WAITING,
    FULL_DISCOVERY,
    STANDARD_SOLUTION,
    SITE_VISIT,
    VALUE_OFFER,
    DISCOUNT_OFFER,
    FAST_PREMIUM
}

enum class ObjectiveOutcome {
    IN_PROGRESS,
    WON,
    REWORK,
    LOST
}

data class ObjectiveScenario(
    val clientTitle: String,
    val clientNeed: String,
    val baseCost: Int,
    val referencePrice: Int,
    val budgetMax: Int,
    val desiredDays: Int
)

data class ObjectiveDeliveryState(
    val schemaVersion: Int = ObjectiveDeliveryGameEngine.SCHEMA_VERSION,
    val campaignId: String,
    val companyType: ObjectiveCompanyType,
    val seed: Long,
    val currentChapter: Int = 1,
    val unlockedChapter: Int = 1,
    val step: Int = 0,
    val clientTrust: Int = 50,
    val needCompleteness: Int = 0,
    val quotedPrice: Int = 0,
    val quotedDelayDays: Int = 0,
    val marginAmount: Int = 0,
    val outcome: ObjectiveOutcome = ObjectiveOutcome.IN_PROGRESS,
    val revision: Int = 1,
    val history: List<String> = emptyList()
)

object ObjectiveDeliveryGameEngine {
    const val SCHEMA_VERSION = 1

    fun scenario(type: ObjectiveCompanyType): ObjectiveScenario = when (type) {
        ObjectiveCompanyType.WORKSHOP -> ObjectiveScenario(
            clientTitle = "Une famille veut un portail personnalisé",
            clientNeed = "Elle donne une largeur approximative et souhaite une livraison avant un événement familial.",
            baseCost = 4_200,
            referencePrice = 6_200,
            budgetMax = 7_000,
            desiredDays = 28
        )
        ObjectiveCompanyType.RETAIL -> ObjectiveScenario(
            clientTitle = "Une petite entreprise équipe un nouveau local",
            clientNeed = "Elle veut plusieurs articles compatibles, disponibles rapidement, sans avoir finalisé les quantités.",
            baseCost = 900,
            referencePrice = 1_350,
            budgetMax = 1_500,
            desiredDays = 7
        )
        ObjectiveCompanyType.SERVICES -> ObjectiveScenario(
            clientTitle = "Un client demande une intervention technique",
            clientNeed = "Il décrit un problème urgent mais le diagnostic, l'accès au site et le créneau restent à préciser.",
            baseCost = 650,
            referencePrice = 1_150,
            budgetMax = 1_300,
            desiredDays = 10
        )
    }

    fun newCampaign(type: ObjectiveCompanyType, campaignId: String, seed: Long): ObjectiveDeliveryState =
        ObjectiveDeliveryState(campaignId = campaignId, companyType = type, seed = seed)

    fun apply(state: ObjectiveDeliveryState, decision: ObjectiveDecision): ObjectiveDeliveryState {
        require(state.outcome == ObjectiveOutcome.IN_PROGRESS) { "Le chapitre est déjà terminé." }
        return when (state.step) {
            0 -> applyContact(state, decision)
            1 -> applyDiscovery(state, decision)
            2 -> applyQuote(state, decision)
            else -> state
        }
    }

    private fun applyContact(state: ObjectiveDeliveryState, decision: ObjectiveDecision): ObjectiveDeliveryState {
        val (trust, completeness, note) = when (decision) {
            ObjectiveDecision.ANSWER_NOW -> Triple(12, 0, "Prospect pris en charge immédiatement.")
            ObjectiveDecision.ASK_INFORMATION -> Triple(8, 10, "Informations essentielles demandées avant de promettre.")
            ObjectiveDecision.LEAVE_WAITING -> Triple(-22, 0, "Prospect laissé en attente sans délai clair.")
            else -> error("Décision invalide pour le premier contact.")
        }
        return state.copy(
            step = 1,
            clientTrust = (state.clientTrust + trust).coerceIn(0, 100),
            needCompleteness = (state.needCompleteness + completeness).coerceIn(0, 100),
            revision = state.revision + 1,
            history = state.history + note
        )
    }

    private fun applyDiscovery(state: ObjectiveDeliveryState, decision: ObjectiveDecision): ObjectiveDeliveryState {
        val (trust, completeness, note) = when (decision) {
            ObjectiveDecision.FULL_DISCOVERY -> Triple(8, 65, "Usage, dimensions, contraintes, budget, quantité et date vérifiés.")
            ObjectiveDecision.STANDARD_SOLUTION -> Triple(-2, 28, "Solution standard proposée avec plusieurs inconnues restantes.")
            ObjectiveDecision.SITE_VISIT -> Triple(6, 55, "Visite ou diagnostic préparé pour sécuriser la faisabilité.")
            else -> error("Décision invalide pour la découverte du besoin.")
        }
        return state.copy(
            step = 2,
            clientTrust = (state.clientTrust + trust).coerceIn(0, 100),
            needCompleteness = (state.needCompleteness + completeness).coerceIn(0, 100),
            revision = state.revision + 1,
            history = state.history + note
        )
    }

    private fun applyQuote(state: ObjectiveDeliveryState, decision: ObjectiveDecision): ObjectiveDeliveryState {
        val scenario = scenario(state.companyType)
        val (price, delay, note) = when (decision) {
            ObjectiveDecision.VALUE_OFFER -> Triple(
                scenario.referencePrice,
                scenario.desiredDays,
                "Offre équilibrée : prix, marge et délai cohérents avec la demande."
            )
            ObjectiveDecision.DISCOUNT_OFFER -> Triple(
                (scenario.referencePrice * 0.90).roundToInt(),
                scenario.desiredDays + 2,
                "Remise commerciale : prix plus attractif mais marge réduite."
            )
            ObjectiveDecision.FAST_PREMIUM -> Triple(
                (scenario.referencePrice * 1.12).roundToInt(),
                max(2, scenario.desiredDays - 5),
                "Offre accélérée : délai plus court avec prix supérieur."
            )
            else -> error("Décision invalide pour le devis.")
        }

        val margin = price - scenario.baseCost
        val priceScore = when {
            price <= scenario.budgetMax -> 10
            else -> -(((price - scenario.budgetMax).toDouble() / scenario.budgetMax) * 100.0).roundToInt()
        }
        val delayScore = if (delay <= scenario.desiredDays) 8 else -(delay - scenario.desiredDays) * 2
        val deterministicNoise = Random(state.seed + state.revision * 97L).nextInt(11) - 5
        val clientScore = 30 +
            state.clientTrust / 2 +
            state.needCompleteness / 3 +
            priceScore +
            delayScore +
            deterministicNoise

        val marginRate = margin.toDouble() / scenario.baseCost
        val outcome = when {
            state.needCompleteness < 35 && state.clientTrust < 40 -> ObjectiveOutcome.LOST
            state.needCompleteness < 45 -> ObjectiveOutcome.REWORK
            marginRate < 0.08 -> ObjectiveOutcome.REWORK
            clientScore >= 74 -> ObjectiveOutcome.WON
            clientScore >= 55 -> ObjectiveOutcome.REWORK
            else -> ObjectiveOutcome.LOST
        }
        val resultNote = when (outcome) {
            ObjectiveOutcome.WON -> "Commande gagnée : le client accepte l'offre et les engagements sont enregistrés."
            ObjectiveOutcome.REWORK -> "Offre à retravailler : le dossier reste récupérable mais un point clé doit être corrigé."
            ObjectiveOutcome.LOST -> "Vente perdue : le bilan explique les causes avant un nouveau essai."
            ObjectiveOutcome.IN_PROGRESS -> ""
        }

        return state.copy(
            step = 3,
            unlockedChapter = if (outcome == ObjectiveOutcome.WON) max(state.unlockedChapter, 2) else state.unlockedChapter,
            quotedPrice = price,
            quotedDelayDays = delay,
            marginAmount = margin,
            outcome = outcome,
            revision = state.revision + 1,
            history = state.history + note + resultNote
        )
    }

    fun riskSummary(state: ObjectiveDeliveryState): List<String> {
        val scenario = scenario(state.companyType)
        val risks = mutableListOf<String>()
        if (state.needCompleteness < 45) risks += "Besoin client encore incomplet"
        if (state.clientTrust < 45) risks += "Confiance client fragile"
        if (state.quotedPrice > 0 && state.marginAmount < (scenario.baseCost * 0.10).roundToInt()) risks += "Marge trop faible"
        if (state.quotedPrice > scenario.budgetMax) risks += "Prix au-dessus du budget annoncé"
        if (state.quotedDelayDays > scenario.desiredDays) risks += "Délai supérieur au souhait client"
        if (risks.isEmpty()) risks += "Aucun risque majeur détecté à cette étape"
        return risks.take(3)
    }
}
