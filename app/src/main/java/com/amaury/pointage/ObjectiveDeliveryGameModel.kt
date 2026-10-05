package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Trois activités fictives pour apprendre le parcours commercial sans données d'un vrai employeur. */
enum class ObjectiveDeliveryCompanyModel(
    val key: String,
    val label: String,
    val summary: String,
    val clientName: String,
    val request: String,
    val referencePriceCents: Int,
    val clientBudgetCents: Int,
    val requestedDays: Int,
    val directCostCents: Int,
    val fixedCostShareCents: Int,
    val chapterTwoClientBudgetCents: Int
) {
    WORKSHOP(
        key = "atelier",
        label = "Atelier de fabrication",
        summary = "Des produits fabriqués sur commande",
        clientName = "Camille Martin",
        request = "un comptoir d’accueil sur mesure",
        referencePriceCents = 185_000,
        clientBudgetCents = 200_000,
        requestedDays = 12,
        directCostCents = 96_000,
        fixedCostShareCents = 41_000,
        chapterTwoClientBudgetCents = 190_000
    ),
    DISTRIBUTION(
        key = "commerce",
        label = "Commerce et distribution",
        summary = "Des articles en stock livrés aux clients",
        clientName = "Alex Bernard",
        request = "un lot de rangements de bureau",
        referencePriceCents = 92_000,
        clientBudgetCents = 100_000,
        requestedDays = 4,
        directCostCents = 46_000,
        fixedCostShareCents = 19_000,
        chapterTwoClientBudgetCents = 90_000
    ),
    SERVICES(
        key = "prestations",
        label = "Prestations et interventions",
        summary = "Des interventions organisées chez le client",
        clientName = "Samira Leroy",
        request = "l’installation de rayonnages sur site",
        referencePriceCents = 68_000,
        clientBudgetCents = 75_000,
        requestedDays = 6,
        directCostCents = 33_000,
        fixedCostShareCents = 14_000,
        chapterTwoClientBudgetCents = 65_000
    );

    companion object {
        fun fromKey(key: String?): ObjectiveDeliveryCompanyModel? =
            values().firstOrNull { it.key == key }
    }

    /** Stock figures and prices are fictional teaching values for chapter 5. */
    fun chapterFiveProfile(): ObjectiveDeliveryChapterFiveProfile = when (this) {
        WORKSHOP -> ObjectiveDeliveryChapterFiveProfile(
            materialLabel = "panneaux de fabrication",
            stockOnHandUnits = 7,
            reservedUnits = 3,
            inTransitUnits = 1,
            newOrderUnits = 10,
            unitCostCents = 12_000,
            procurementDeadlineDays = 5
        )
        DISTRIBUTION -> ObjectiveDeliveryChapterFiveProfile(
            materialLabel = "articles de rangement",
            stockOnHandUnits = 5,
            reservedUnits = 2,
            inTransitUnits = 1,
            newOrderUnits = 8,
            unitCostCents = 7_500,
            procurementDeadlineDays = 4
        )
        SERVICES -> ObjectiveDeliveryChapterFiveProfile(
            materialLabel = "kits de fixation",
            stockOnHandUnits = 4,
            reservedUnits = 2,
            inTransitUnits = 0,
            newOrderUnits = 8,
            unitCostCents = 3_500,
            procurementDeadlineDays = 4
        )
    }

    /** Fictional production and quality values used by chapter 6. */
    fun chapterSixProfile(): ObjectiveDeliveryChapterSixProfile = when (this) {
        WORKSHOP -> ObjectiveDeliveryChapterSixProfile(
            inspectedItem = "la face visible du comptoir",
            acceptanceCriteria = "Finition sans rayure visible, dimensions conformes au plan, stabilité vérifiée.",
            nonconformityDescription = "Une rayure est visible sur la face avant. La structure et les dimensions sont conformes.",
            balancedProductionDays = 8,
            parallelProductionDays = 7,
            reworkCostCents = 28_000
        )
        DISTRIBUTION -> ObjectiveDeliveryChapterSixProfile(
            inspectedItem = "un carton du lot de rangements",
            acceptanceCriteria = "Référence et quantité exactes, emballage intact, étiquette lisible.",
            nonconformityDescription = "Une étiquette est décalée sur un carton. Le contenu et la quantité sont conformes.",
            balancedProductionDays = 3,
            parallelProductionDays = 2,
            reworkCostCents = 9_000
        )
        SERVICES -> ObjectiveDeliveryChapterSixProfile(
            inspectedItem = "la finition de l’installation",
            acceptanceCriteria = "Éléments fixés, alignement vérifié et zone laissée propre.",
            nonconformityDescription = "Un cache de finition est légèrement décalé. La fixation et la stabilité sont conformes.",
            balancedProductionDays = 5,
            parallelProductionDays = 4,
            reworkCostCents = 14_000
        )
    }

    /** Fictional delivery and customer-service scenarios used by chapter 7. */
    fun chapterSevenProfile(): ObjectiveDeliveryChapterSevenProfile = when (this) {
        WORKSHOP -> ObjectiveDeliveryChapterSevenProfile(
            deliveryItem = "le comptoir d’accueil",
            customerClaim = "Le carton est marqué à l’arrivée. Le client demande une vérification du comptoir avant de signer.",
            complaintFollowUp = "Vérifier l’état du produit avec le client et consigner la réserve transport."
        )
        DISTRIBUTION -> ObjectiveDeliveryChapterSevenProfile(
            deliveryItem = "le lot de rangements",
            customerClaim = "Un colis semble avoir été ouvert pendant le transport. Le client demande de recompter les articles.",
            complaintFollowUp = "Comparer les quantités avec le bordereau avec le client, puis suivre tout écart."
        )
        SERVICES -> ObjectiveDeliveryChapterSevenProfile(
            deliveryItem = "l’installation de rayonnages",
            customerClaim = "Le client réserve sur la propreté de la zone après l’intervention et demande une vérification.",
            complaintFollowUp = "Écouter le client, vérifier la zone et planifier une remise en état si nécessaire."
        )
    }

    /** Fictitious team and company figures for the three-business simulation in chapter 8. */
    fun chapterEightProfile(): ObjectiveDeliveryChapterEightProfile = when (this) {
        WORKSHOP -> ObjectiveDeliveryChapterEightProfile(annualNetResultCents = 112_000_000)
        DISTRIBUTION -> ObjectiveDeliveryChapterEightProfile(annualNetResultCents = 96_000_000)
        SERVICES -> ObjectiveDeliveryChapterEightProfile(annualNetResultCents = 1_240_000_00)
    }

    /** Fictitious safety-inspection case shared by the three business models. */
    fun chapterNineProfile(): ObjectiveDeliveryChapterNineProfile = when (this) {
        WORKSHOP -> ObjectiveDeliveryChapterNineProfile("poste de découpe", "carter de protection à vérifier")
        DISTRIBUTION -> ObjectiveDeliveryChapterNineProfile("zone de préparation", "barrière de circulation déplacée")
        SERVICES -> ObjectiveDeliveryChapterNineProfile("zone d’intervention", "balisage temporaire manquant")
    }

    /** Fictitious multi-order shock figures for chapter 10. */
    fun chapterTenProfile(): ObjectiveDeliveryChapterTenProfile = when (this) {
        WORKSHOP -> ObjectiveDeliveryChapterTenProfile(
            orders = listOf("Comptoir client", "Mobilier d’exposition", "Éléments de remplacement"),
            criticalMaterial = "panneaux de fabrication"
        )
        DISTRIBUTION -> ObjectiveDeliveryChapterTenProfile(
            orders = listOf("Lot de rangements", "Réassort • client sensible au prix", "Commande urgente"),
            criticalMaterial = "articles en stock"
        )
        SERVICES -> ObjectiveDeliveryChapterTenProfile(
            orders = listOf("Installation de rayonnages", "Intervention de suivi", "Pose complémentaire"),
            criticalMaterial = "kits de fixation"
        )
    }
}

data class ObjectiveDeliveryChapterFiveProfile(
    val materialLabel: String,
    val stockOnHandUnits: Int,
    val reservedUnits: Int,
    val inTransitUnits: Int,
    val newOrderUnits: Int,
    val unitCostCents: Int,
    val procurementDeadlineDays: Int
) {
    val purchaseShortfallUnits: Int
        get() = maxOf(0, reservedUnits + newOrderUnits - stockOnHandUnits - inTransitUnits)

    val purchaseBudgetCents: Int
        get() = (purchaseShortfallUnits * unitCostCents * 1.30).roundToInt()
}

data class ObjectiveDeliveryChapterSixProfile(
    val inspectedItem: String,
    val acceptanceCriteria: String,
    val nonconformityDescription: String,
    val balancedProductionDays: Int,
    val parallelProductionDays: Int,
    val reworkCostCents: Int
)

data class ObjectiveDeliveryChapterSevenProfile(
    val deliveryItem: String,
    val customerClaim: String,
    val complaintFollowUp: String
)

data class ObjectiveDeliveryChapterEightProfile(val annualNetResultCents: Int) {
    val profitShareEligible: Boolean get() = annualNetResultCents > 100_000_000
    val collectiveProfitShareCents: Int get() = if (profitShareEligible) (annualNetResultCents * 0.07).roundToInt() else 0
}

data class ObjectiveDeliveryChapterNineProfile(val affectedWorkArea: String, val safetyFinding: String)

data class ObjectiveDeliveryChapterTenProfile(val orders: List<String>, val criticalMaterial: String)

enum class ObjectiveDeliveryPhase { QUALIFICATION, OFFER, RESULT }
enum class ObjectiveDeliveryPriceChoice(val label: String, val multiplier: Double) {
    ATTRACTIVE("Attractif", 0.90),
    BALANCED("Équilibré", 1.00),
    AMBITIOUS("Ambitieux", 1.15)
}
enum class ObjectiveDeliveryTimelineChoice(val label: String) {
    FAST("Rapide"),
    REQUESTED("Comme demandé"),
    PRUDENT("Prudent");

    fun daysFor(requestedDays: Int): Int = when (this) {
        FAST -> ((requestedDays + 1) / 2).coerceAtLeast(1)
        REQUESTED -> requestedDays
        PRUDENT -> requestedDays * 2
    }
}
enum class ObjectiveDeliveryOutcome { ORDER_ACCEPTED, CORRECTION_REQUESTED, LOST }

enum class ObjectiveDeliveryChapterTwoPhase { QUOTE, NEGOTIATION, RESULT }
enum class ObjectiveDeliveryChapterTwoPriceChoice(val label: String, val multiplier: Double) {
    LOW_MARGIN("Marge fine", 1.10),
    BALANCED("Marge équilibrée", 1.30),
    HIGH_MARGIN("Marge renforcée", 1.50)
}
enum class ObjectiveDeliveryNegotiationChoice(val label: String, val discountPercent: Int) {
    EXPLAIN_VALUE("Expliquer le contenu du devis", 0),
    OFFER_DISCOUNT("Proposer une remise de 10 %", 10)
}
enum class ObjectiveDeliveryChapterThreePhase { REVIEW, RESULT }
enum class ObjectiveDeliveryChapterThreeOutcome { READY_TO_LAUNCH, NEEDS_CLARIFICATION, LAUNCH_BLOCKED }
enum class ObjectiveDeliveryHandoffItem {
    SIGNED_QUOTE,
    TECHNICAL_SPECIFICATION,
    CUSTOMER_OPTIONS,
    DELIVERY_CONDITIONS,
    PROMISED_DATE
}
enum class ObjectiveDeliveryChapterFourPhase { PLAN, RESULT }
enum class ObjectiveDeliveryChapterFourOutcome { TEAM_READY, PLAN_NEEDS_REVIEW }
enum class ObjectiveDeliveryTeamMember(
    val displayName: String,
    val jobTitle: String,
    val schedule: String,
    val weeklyHours: Int,
    val seniorityMonths: Int,
    val skills: String
) {
    ELISE("Élise", "Commerce et administration", "lundi au vendredi", 35, 30,
        "relation client, dossier de commande"),
    KARIM("Karim", "Production et contrôle technique", "mardi au vendredi", 28, 18,
        "fabrication, contrôle qualité"),
    NOAH("Noah", "Logistique", "jeudi au samedi", 21, 10,
        "préparation, expédition"),
    TEMPORARY_TECHNICIAN("Renfort technique", "Technicien temporaire", "mercredi", 7, 0,
        "fabrication, contrôle qualité")
}
enum class ObjectiveDeliveryChapterFourTask { CUSTOMER_FILE, PRODUCTION, DISPATCH }
enum class ObjectiveDeliveryChapterFourLeaveChoice { APPROVE_WITH_COVER, OFFER_ALTERNATIVE_DATE }
enum class ObjectiveDeliveryChapterFourHireDecision { APPROVE_TEMPORARY_ROLE, DECLINE_WITH_ALTERNATIVE }
enum class ObjectiveDeliveryChapterFourContract { CDI, CDD, INTERIM }
enum class ObjectiveDeliveryChapterFourClassification { NON_CADRE, CADRE }

enum class ObjectiveDeliveryChapterFivePhase { PROCUREMENT, RESULT }
enum class ObjectiveDeliveryChapterFiveOutcome { MATERIALS_READY, PLAN_NEEDS_REVIEW }
enum class ObjectiveDeliveryChapterFiveSupplier(
    val displayName: String,
    val arrivalDays: Int,
    val unitCostMultiplier: Double
) {
    USUAL("Fournisseur habituel • retard annoncé", 7, 1.00),
    BACKUP("Fournisseur de secours • matière conforme", 4, 1.20),
    EXPRESS("Transport express", 2, 1.45)
}
enum class ObjectiveDeliveryChapterFiveQuantityChoice(val adjustment: Int) {
    SHORT_BY_ONE(-1),
    EXACT_NEED(0),
    SAFETY_BUFFER(2)
}

enum class ObjectiveDeliveryChapterSixPhase { PLANNING, QUALITY_CONTROL, CORRECTION, RESULT }
enum class ObjectiveDeliveryChapterSixOutcome {
    READY_FOR_DISPATCH,
    READY_WITH_APPROVED_DEVIATION,
    CONTROL_INCOMPLETE,
    QUALITY_HOLD
}
enum class ObjectiveDeliveryChapterSixProductionPlan(val displayName: String, val description: String) {
    BALANCED_FLOW("Flux équilibré", "Charge régulière, opérations organisées les unes après les autres."),
    PARALLEL_PREPARATION(
        "Préparer en parallèle",
        "Réduit le délai en préparant les tâches indépendantes en même temps. La transmission entre postes doit être confirmée."
    )
}
enum class ObjectiveDeliveryChapterSixInspectionChoice(val displayName: String, val description: String) {
    QUICK_SAMPLE("Contrôle visuel rapide", "Contrôle d’un échantillon : le défaut de finition peut passer inaperçu."),
    FULL_CHECKLIST("Liste de contrôle complète", "Vérifie les critères du devis et les points critiques avant la sortie.")
}
enum class ObjectiveDeliveryChapterSixCorrectionChoice(val displayName: String, val description: String) {
    REWORK_AND_RECHECK("Reprendre puis recontrôler", "Corrige l’écart avant la sortie, avec un coût et deux jours de reprise."),
    REQUEST_CUSTOMER_DEVIATION("Demander un accord client écrit", "Présente l’écart mineur, consigne l’accord et conserve le contrôle de traçabilité."),
    DISPATCH_WITHOUT_CORRECTION("Envoyer sans corriger", "L’écart reste ouvert : la qualité bloque la sortie et le client risque une réclamation.")
}

enum class ObjectiveDeliveryChapterSevenPhase { DELIVERY_PLAN, ORDER_CHECK, CUSTOMER_CLAIM, RESULT }
enum class ObjectiveDeliveryChapterSevenOutcome {
    ORDER_CHECK_INCOMPLETE,
    CLAIM_RESOLVED,
    CLAIM_UNRESOLVED
}
enum class ObjectiveDeliveryChapterSevenDeliveryChoice(
    val displayName: String,
    val arrivalDays: Int,
    val costCents: Int,
    val description: String
) {
    STANDARD("Livraison standard", 3, 8_000, "Coût modéré, créneau de livraison large."),
    APPOINTMENT("Créneau convenu", 2, 18_000, "Créneau plus précis, suivi de transport renforcé."),
    SPECIALIST("Transport spécialisé", 1, 30_000, "Manutention adaptée, livraison plus rapide et plus coûteuse.")
}
enum class ObjectiveDeliveryChapterSevenClaimAction(
    val displayName: String,
    val costCents: Int,
    val description: String
) {
    INVESTIGATE_AND_FOLLOW_UP(
        "Vérifier et tenir le client informé", 5_000,
        "Comparer la commande, les contrôles et les éléments de transport, puis donner une suite datée."
    ),
    CORRECT_AND_FOLLOW_UP(
        "Corriger puis confirmer la résolution", 24_000,
        "Organiser une remise en état ou un remplacement adapté, puis confirmer le résultat au client."
    ),
    DISMISS_WITHOUT_REVIEW(
        "Écarter la réclamation", 0,
        "Ne pas vérifier les faits laisse la réserve ouverte et abîme la confiance."
    )
}

enum class ObjectiveDeliveryChapterEightPhase { WEEK_PLAN, TEAM_EVENTS, ANNUAL_REVIEW, RESULT }
enum class ObjectiveDeliveryChapterEightOutcome { TEAM_WEEK_SUCCESS, TEAM_PLAN_NEEDS_REVIEW }
enum class ObjectiveDeliveryChapterEightStaffing(val headcount: Int, val label: String) {
    STANDARD_TEN(10, "Planifier 10 personnes pour 100 commandes"),
    EXTRA_UNPLANNED_ELEVENTH(11, "Ajouter une 11e personne sans charge ni poste justifié")
}
enum class ObjectiveDeliveryChapterEightBonusCriteria(val label: String) {
    BALANCED("Annoncer délai, qualité, sécurité et charge maîtrisable"),
    VOLUME_ONLY("Récompenser uniquement le volume produit")
}
enum class ObjectiveDeliveryChapterEightOvertimeChoice(val label: String) {
    REPLAN_WITHIN_SCHEDULE("Réorganiser le planning sans heures supplémentaires"),
    AUTHORIZE_TARGETED("Autoriser un renfort ciblé, motivé et limité"),
    AUTHORIZE_BLANKET("Demander des heures supplémentaires à toute l’équipe")
}
enum class ObjectiveDeliveryChapterEightAbsenceResponse(val label: String) {
    REASSIGN_QUALIFIED("Réaffecter les tâches selon les compétences"),
    INFORM_AND_RESCHEDULE("Prévenir le client et ajuster l’échéance"),
    PENALIZE_PROTECTED_ABSENCE("Pénaliser l’absence imprévue sans vérifier les faits")
}
enum class ObjectiveDeliveryChapterEightPayrollResponse(val label: String) {
    VERIFY_AND_CORRECT("Vérifier la fiche, expliquer et régulariser"),
    IGNORE_ERROR("Laisser l’erreur de paie sans réponse"),
    TAKE_BACK_WITHOUT_REVIEW("Retenir un trop-perçu sans vérification ni procédure")
}
enum class ObjectiveDeliveryChapterEightConflictResponse(val label: String) {
    LISTEN_AND_MEDIATE("Écouter les personnes et proposer une médiation"),
    CLARIFY_TASKS("Vérifier les faits et clarifier la répartition du travail"),
    RETALIATE("Menacer la personne qui a signalé la tension")
}

enum class ObjectiveDeliveryChapterNinePhase { INSPECTION, SAFETY_ACTION, CORRECTION, RESULT }
enum class ObjectiveDeliveryChapterNineOutcome {
    REOPENED_AFTER_VERIFICATION,
    COMPLIANCE_HOLD,
    CLOSED_AFTER_DEADLINE
}
enum class ObjectiveDeliveryChapterNineInspectionResponse(val label: String) {
    COOPERATE_AND_RECORD("Accueillir l’inspection et consigner les faits"),
    CONCEAL_RECORDS("Cacher ou modifier des éléments du dossier")
}
enum class ObjectiveDeliveryChapterNineSafetyResponse(val label: String) {
    STOP_AFFECTED_WORK("Isoler le poste concerné et réorganiser le travail sûr"),
    KEEP_RUNNING("Continuer au poste malgré l’alerte de sécurité")
}
enum class ObjectiveDeliveryChapterNineIntegrityResponse(val label: String) {
    REFUSE_AND_REPORT("Refuser et signaler la tentative d’influence"),
    ATTEMPT_TO_INFLUENCE("Tenter d’influencer la visite — option abstraite à haut risque")
}
enum class ObjectiveDeliveryChapterNineCorrectionChoice(val label: String) {
    CORRECT_AND_REQUEST_CHECK("Corriger sous 5 jours et demander la vérification de reprise"),
    MISS_DEADLINE("Reporter la correction au-delà du délai judiciaire de 5 jours")
}

enum class ObjectiveDeliveryChapterTenPhase { PRESSURE_PLAN, RESULT }
enum class ObjectiveDeliveryChapterTenOutcome { CAMPAIGN_WON, CASH_OR_CUSTOMER_RISK }
enum class ObjectiveDeliveryChapterTenSupplierChoice(val label: String) {
    QUALIFIED_ALTERNATIVE("Commander chez le fournisseur alternatif qualifié"),
    WAIT_FOR_USUAL("Attendre le fournisseur habituel malgré le retard annoncé"),
    EXPRESS_EVERYTHING("Tout commander en express, au coût élevé")
}
enum class ObjectiveDeliveryChapterTenPriorityChoice(val label: String) {
    PROTECT_COMMITMENTS("Équilibrer la capacité entre les trois commandes engagées"),
    HIGHEST_MARGIN_FIRST("Servir d’abord la commande à la marge la plus élevée"),
    URGENT_ONLY("Traiter uniquement la commande la plus urgente")
}
enum class ObjectiveDeliveryChapterTenPricingChoice(val label: String) {
    REVISE_NEW_QUOTES_ONLY("Recalculer les nouveaux devis et respecter les prix déjà convenus"),
    SURCHARGE_ACCEPTED_ORDERS("Ajouter le surcoût aux commandes déjà acceptées"),
    DISCOUNT_ALL_ORDERS("Baisser tous les prix sans vérifier les coûts")
}
enum class ObjectiveDeliveryChapterTenCashChoice(val label: String) {
    PROTECT_OBLIGATIONS("Réserver la trésorerie pour la paie, les fournisseurs et la sécurité"),
    OWNER_WITHDRAWAL("Prélever une distribution avant les échéances"),
    CUT_SAFETY("Réduire le budget de sécurité pour préserver la marge")
}

data class ObjectiveDeliveryChapterFourState(
    val phase: ObjectiveDeliveryChapterFourPhase = ObjectiveDeliveryChapterFourPhase.PLAN,
    val customerFileAssignee: ObjectiveDeliveryTeamMember? = null,
    val productionAssignee: ObjectiveDeliveryTeamMember? = null,
    val dispatchAssignee: ObjectiveDeliveryTeamMember? = null,
    val leaveChoice: ObjectiveDeliveryChapterFourLeaveChoice? = null,
    val hireDecision: ObjectiveDeliveryChapterFourHireDecision? = null,
    val hireContract: ObjectiveDeliveryChapterFourContract? = null,
    val hireClassification: ObjectiveDeliveryChapterFourClassification? = null,
    val reviewedEmployee: ObjectiveDeliveryTeamMember? = null,
    val reviewFactsDiscussed: Boolean = false,
    val raiseEnvelopePoints: Int? = null,
    val individualRaisePoints: Int? = null,
    val outcome: ObjectiveDeliveryChapterFourOutcome? = null
)

data class ObjectiveDeliveryChapterFiveState(
    val phase: ObjectiveDeliveryChapterFivePhase = ObjectiveDeliveryChapterFivePhase.PROCUREMENT,
    val supplier: ObjectiveDeliveryChapterFiveSupplier? = null,
    val quantityChoice: ObjectiveDeliveryChapterFiveQuantityChoice? = null,
    val outcome: ObjectiveDeliveryChapterFiveOutcome? = null
)

data class ObjectiveDeliveryChapterSixState(
    val phase: ObjectiveDeliveryChapterSixPhase = ObjectiveDeliveryChapterSixPhase.PLANNING,
    val productionPlan: ObjectiveDeliveryChapterSixProductionPlan? = null,
    val inspectionChoice: ObjectiveDeliveryChapterSixInspectionChoice? = null,
    val correctionChoice: ObjectiveDeliveryChapterSixCorrectionChoice? = null,
    val outcome: ObjectiveDeliveryChapterSixOutcome? = null
)

data class ObjectiveDeliveryChapterSevenState(
    val phase: ObjectiveDeliveryChapterSevenPhase = ObjectiveDeliveryChapterSevenPhase.DELIVERY_PLAN,
    val deliveryChoice: ObjectiveDeliveryChapterSevenDeliveryChoice? = null,
    val photoShared: Boolean = false,
    val itemsChecked: Boolean = false,
    val quantitiesChecked: Boolean = false,
    val optionsChecked: Boolean = false,
    val qualityChecked: Boolean = false,
    val deliveryDocumentsChecked: Boolean = false,
    val claimAction: ObjectiveDeliveryChapterSevenClaimAction? = null,
    val outcome: ObjectiveDeliveryChapterSevenOutcome? = null
)

data class ObjectiveDeliveryChapterEightState(
    val phase: ObjectiveDeliveryChapterEightPhase = ObjectiveDeliveryChapterEightPhase.WEEK_PLAN,
    val staffing: ObjectiveDeliveryChapterEightStaffing? = null,
    val bonusCriteria: ObjectiveDeliveryChapterEightBonusCriteria? = null,
    val overtimeChoice: ObjectiveDeliveryChapterEightOvertimeChoice? = null,
    val absenceResponse: ObjectiveDeliveryChapterEightAbsenceResponse? = null,
    val payrollResponse: ObjectiveDeliveryChapterEightPayrollResponse? = null,
    val conflictResponse: ObjectiveDeliveryChapterEightConflictResponse? = null,
    val voluntaryEventParticipants: Int = 0,
    val raiseEnvelopePercent: Int? = null,
    val individualRaisePercent: Int? = null,
    val equalProfitShareConfirmed: Boolean = false,
    val outcome: ObjectiveDeliveryChapterEightOutcome? = null
)

data class ObjectiveDeliveryChapterNineState(
    val phase: ObjectiveDeliveryChapterNinePhase = ObjectiveDeliveryChapterNinePhase.INSPECTION,
    val inspectionResponse: ObjectiveDeliveryChapterNineInspectionResponse? = null,
    val safetyResponse: ObjectiveDeliveryChapterNineSafetyResponse? = null,
    val integrityResponse: ObjectiveDeliveryChapterNineIntegrityResponse? = null,
    val correctionChoice: ObjectiveDeliveryChapterNineCorrectionChoice? = null,
    val outcome: ObjectiveDeliveryChapterNineOutcome? = null
)

data class ObjectiveDeliveryChapterTenState(
    val phase: ObjectiveDeliveryChapterTenPhase = ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN,
    val supplierChoice: ObjectiveDeliveryChapterTenSupplierChoice? = null,
    val priorityChoice: ObjectiveDeliveryChapterTenPriorityChoice? = null,
    val pricingChoice: ObjectiveDeliveryChapterTenPricingChoice? = null,
    val cashChoice: ObjectiveDeliveryChapterTenCashChoice? = null,
    val outcome: ObjectiveDeliveryChapterTenOutcome? = null
)

data class ObjectiveDeliveryChapterOneState(
    val phase: ObjectiveDeliveryPhase = ObjectiveDeliveryPhase.QUALIFICATION,
    val needQualified: Boolean = false,
    val priceChoice: ObjectiveDeliveryPriceChoice? = null,
    val timelineChoice: ObjectiveDeliveryTimelineChoice? = null,
    val outcome: ObjectiveDeliveryOutcome? = null
)

data class ObjectiveDeliveryChapterTwoState(
    val phase: ObjectiveDeliveryChapterTwoPhase = ObjectiveDeliveryChapterTwoPhase.QUOTE,
    val priceChoice: ObjectiveDeliveryChapterTwoPriceChoice? = null,
    val timelineChoice: ObjectiveDeliveryTimelineChoice? = null,
    val negotiationChoice: ObjectiveDeliveryNegotiationChoice? = null,
    val outcome: ObjectiveDeliveryOutcome? = null
)

data class ObjectiveDeliveryChapterThreeState(
    val phase: ObjectiveDeliveryChapterThreePhase = ObjectiveDeliveryChapterThreePhase.REVIEW,
    val signedQuoteAttached: Boolean = false,
    val technicalSpecificationConfirmed: Boolean = false,
    val customerOptionsConfirmed: Boolean = false,
    val deliveryConditionsConfirmed: Boolean = false,
    val promisedDateConfirmed: Boolean = false,
    val outcome: ObjectiveDeliveryChapterThreeOutcome? = null
)

/** A replay board is saved apart from the campaign board, including across app restarts. */
data class ObjectiveDeliveryReplaySession(
    val chapter: Int,
    val returnChapter: Int,
    val chapterOne: ObjectiveDeliveryChapterOneState? = null,
    val chapterTwo: ObjectiveDeliveryChapterTwoState? = null,
    val chapterThree: ObjectiveDeliveryChapterThreeState? = null,
    val chapterFour: ObjectiveDeliveryChapterFourState? = null,
    val chapterFive: ObjectiveDeliveryChapterFiveState? = null,
    val chapterSix: ObjectiveDeliveryChapterSixState? = null,
    val chapterSeven: ObjectiveDeliveryChapterSevenState? = null,
    val chapterEight: ObjectiveDeliveryChapterEightState? = null,
    val chapterNine: ObjectiveDeliveryChapterNineState? = null,
    val chapterTen: ObjectiveDeliveryChapterTenState? = null
)

data class ObjectiveDeliveryCampaign(
    val companyModel: ObjectiveDeliveryCompanyModel,
    // These fields are the durable campaign state for chapter 1.
    val phase: ObjectiveDeliveryPhase = ObjectiveDeliveryPhase.QUALIFICATION,
    val unlockedChapter: Int = 1,
    val chapterOneWon: Boolean = false,
    val needQualified: Boolean = false,
    val priceChoice: ObjectiveDeliveryPriceChoice? = null,
    val timelineChoice: ObjectiveDeliveryTimelineChoice? = null,
    val outcome: ObjectiveDeliveryOutcome? = null,
    val attemptNumber: Int = 1,
    val activeChapter: Int = 1,
    val chapterTwo: ObjectiveDeliveryChapterTwoState = ObjectiveDeliveryChapterTwoState(),
    val chapterTwoAttemptNumber: Int = 1,
    val chapterThree: ObjectiveDeliveryChapterThreeState = ObjectiveDeliveryChapterThreeState(),
    val chapterThreeAttemptNumber: Int = 1,
    val chapterFour: ObjectiveDeliveryChapterFourState = ObjectiveDeliveryChapterFourState(),
    val chapterFourAttemptNumber: Int = 1,
    val chapterFive: ObjectiveDeliveryChapterFiveState = ObjectiveDeliveryChapterFiveState(),
    val chapterFiveAttemptNumber: Int = 1,
    val chapterSix: ObjectiveDeliveryChapterSixState = ObjectiveDeliveryChapterSixState(),
    val chapterSixAttemptNumber: Int = 1,
    val chapterSeven: ObjectiveDeliveryChapterSevenState = ObjectiveDeliveryChapterSevenState(),
    val chapterSevenAttemptNumber: Int = 1,
    val chapterEight: ObjectiveDeliveryChapterEightState = ObjectiveDeliveryChapterEightState(),
    val chapterEightAttemptNumber: Int = 1,
    val chapterNine: ObjectiveDeliveryChapterNineState = ObjectiveDeliveryChapterNineState(),
    val chapterNineAttemptNumber: Int = 1,
    val chapterTen: ObjectiveDeliveryChapterTenState = ObjectiveDeliveryChapterTenState(),
    val chapterTenAttemptNumber: Int = 1,
    val replaySession: ObjectiveDeliveryReplaySession? = null,
    val revision: Long = 0,
    val savedAtEpochMillis: Long = 0,
    val cloudOwnerUid: String? = null,
    val cloudHeadSnapshotId: String? = null,
    val cloudParentSnapshotIds: List<String> = emptyList()
)

data class ObjectiveDeliveryEvaluation(
    val quotedPriceCents: Int,
    val quotedDays: Int,
    val needQualified: Boolean,
    val priceWithinBudget: Boolean,
    val deadlineMet: Boolean,
    val outcome: ObjectiveDeliveryOutcome
)

data class ObjectiveDeliveryChapterTwoEvaluation(
    val baseQuoteCents: Int,
    val finalPriceCents: Int,
    val totalCostCents: Int,
    val marginCents: Int,
    val quotedDays: Int,
    val priceWithinBudget: Boolean,
    val deadlineMet: Boolean,
    val aboveCost: Boolean,
    val outcome: ObjectiveDeliveryOutcome
) {
    val marginPercent: Int
        get() = if (totalCostCents == 0) 0 else (marginCents * 100.0 / totalCostCents).roundToInt()
}

data class ObjectiveDeliveryChapterThreeEvaluation(
    val missingDocuments: Int,
    val outcome: ObjectiveDeliveryChapterThreeOutcome
)

data class ObjectiveDeliveryChapterFourEvaluation(
    val tasksCovered: Boolean,
    val leaveCovered: Boolean,
    val hiringDecisionCoherent: Boolean,
    val annualReviewComplete: Boolean,
    val raisesWithinEnvelope: Boolean,
    val outcome: ObjectiveDeliveryChapterFourOutcome
)

data class ObjectiveDeliveryChapterFiveEvaluation(
    val neededUnits: Int,
    val orderedUnits: Int,
    val orderCostCents: Int,
    val stockCoversDemand: Boolean,
    val supplierOnTime: Boolean,
    val purchaseWithinBudget: Boolean,
    val outcome: ObjectiveDeliveryChapterFiveOutcome
)

data class ObjectiveDeliveryChapterSixEvaluation(
    val plannedProductionDays: Int,
    val correctionDays: Int,
    val estimatedFinishDays: Int,
    val promisedDays: Int,
    val deadlineMet: Boolean,
    val correctionCostCents: Int,
    val defectDetected: Boolean,
    val dispositionApproved: Boolean,
    val outcome: ObjectiveDeliveryChapterSixOutcome
) {
    val readyForDispatch: Boolean
        get() = outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
            outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION
}

data class ObjectiveDeliveryChapterSevenEvaluation(
    val completedChecks: Int,
    val totalChecks: Int,
    val orderIsComplete: Boolean,
    val deliveryCostCents: Int,
    val complaintCostCents: Int,
    val photoShared: Boolean,
    val claimResolved: Boolean,
    val outcome: ObjectiveDeliveryChapterSevenOutcome
)

data class ObjectiveDeliveryChapterEightEvaluation(
    val staffingMatchesWorkload: Boolean,
    val bonusCriteriaBalanced: Boolean,
    val overtimeWasControlled: Boolean,
    val absenceHandledFairly: Boolean,
    val payrollWasCorrected: Boolean,
    val conflictAddressed: Boolean,
    val raisesWithinEnvelope: Boolean,
    val profitShareCents: Int,
    val profitShareConfirmed: Boolean,
    val weeklyBonusCents: Int,
    val voluntaryEventParticipants: Int,
    val outcome: ObjectiveDeliveryChapterEightOutcome
)

data class ObjectiveDeliveryChapterNineEvaluation(
    val inspectionWasTransparent: Boolean,
    val affectedWorkWasStopped: Boolean,
    val integrityWasProtected: Boolean,
    val correctionWithinDeadline: Boolean,
    val reopeningAuthorized: Boolean,
    val outcome: ObjectiveDeliveryChapterNineOutcome
)

data class ObjectiveDeliveryChapterTenEvaluation(
    val allOrdersProtected: Boolean,
    val supplierOnTime: Boolean,
    val pricingRespectsAgreements: Boolean,
    val obligationsRemainCovered: Boolean,
    val projectedCashCents: Int,
    val liquidityReserveCents: Int,
    val outcome: ObjectiveDeliveryChapterTenOutcome
)

/** Deterministic rules keep the campaign tutorials easy to understand and explain. */
object ObjectiveDeliveryGameRules {
    private val initialChapterOne = ObjectiveDeliveryChapterOneState()
    private val initialChapterTwo = ObjectiveDeliveryChapterTwoState()
    private val initialChapterThree = ObjectiveDeliveryChapterThreeState()
    private val initialChapterFour = ObjectiveDeliveryChapterFourState()
    private val initialChapterFive = ObjectiveDeliveryChapterFiveState()
    private val initialChapterSix = ObjectiveDeliveryChapterSixState()
    private val initialChapterSeven = ObjectiveDeliveryChapterSevenState()
    private val initialChapterEight = ObjectiveDeliveryChapterEightState()
    private val initialChapterNine = ObjectiveDeliveryChapterNineState()
    private val initialChapterTen = ObjectiveDeliveryChapterTenState()

    fun isPristine(campaign: ObjectiveDeliveryCampaign): Boolean =
        chapterOneState(campaign) == initialChapterOne &&
            campaign.unlockedChapter == 1 &&
            !campaign.chapterOneWon &&
            campaign.attemptNumber == 1 &&
            campaign.activeChapter == 1 &&
            campaign.chapterTwo == initialChapterTwo &&
            campaign.chapterTwoAttemptNumber == 1 &&
            campaign.chapterThree == initialChapterThree &&
            campaign.chapterThreeAttemptNumber == 1 &&
            campaign.chapterFour == initialChapterFour &&
            campaign.chapterFourAttemptNumber == 1 &&
            campaign.chapterFive == initialChapterFive &&
            campaign.chapterFiveAttemptNumber == 1 &&
            campaign.chapterSix == initialChapterSix &&
            campaign.chapterSixAttemptNumber == 1 &&
            campaign.chapterSeven == initialChapterSeven &&
            campaign.chapterSevenAttemptNumber == 1 &&
            campaign.chapterEight == initialChapterEight &&
            campaign.chapterEightAttemptNumber == 1 &&
            campaign.chapterNine == initialChapterNine &&
            campaign.chapterNineAttemptNumber == 1 &&
            campaign.chapterTen == initialChapterTen &&
            campaign.chapterTenAttemptNumber == 1 &&
            campaign.replaySession == null

    fun chapterOneState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterOneState =
        campaign.replaySession?.takeIf { it.chapter == 1 }?.chapterOne ?:
            ObjectiveDeliveryChapterOneState(
                phase = campaign.phase,
                needQualified = campaign.needQualified,
                priceChoice = campaign.priceChoice,
                timelineChoice = campaign.timelineChoice,
                outcome = campaign.outcome
            )

    fun chapterTwoState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterTwoState =
        campaign.replaySession?.takeIf { it.chapter == 2 }?.chapterTwo ?: campaign.chapterTwo

    fun chapterThreeState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterThreeState =
        campaign.replaySession?.takeIf { it.chapter == 3 }?.chapterThree ?: campaign.chapterThree

    fun chapterFourState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterFourState =
        campaign.replaySession?.takeIf { it.chapter == 4 }?.chapterFour ?: campaign.chapterFour

    fun chapterFiveState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterFiveState =
        campaign.replaySession?.takeIf { it.chapter == 5 }?.chapterFive ?: campaign.chapterFive

    fun chapterSixState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterSixState =
        campaign.replaySession?.takeIf { it.chapter == 6 }?.chapterSix ?: campaign.chapterSix

    fun chapterSevenState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterSevenState =
        campaign.replaySession?.takeIf { it.chapter == 7 }?.chapterSeven ?: campaign.chapterSeven

    fun chapterEightState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterEightState =
        campaign.replaySession?.takeIf { it.chapter == 8 }?.chapterEight ?: campaign.chapterEight

    fun chapterNineState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterNineState =
        campaign.replaySession?.takeIf { it.chapter == 9 }?.chapterNine ?: campaign.chapterNine

    fun chapterTenState(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterTenState =
        campaign.replaySession?.takeIf { it.chapter == 10 }?.chapterTen ?: campaign.chapterTen

    fun qualifyNeed(
        campaign: ObjectiveDeliveryCampaign,
        askedQuestions: Boolean
    ): ObjectiveDeliveryCampaign {
        val current = chapterOneState(campaign)
        require(current.phase == ObjectiveDeliveryPhase.QUALIFICATION)
        return updateChapterOne(campaign, current.copy(
            phase = ObjectiveDeliveryPhase.OFFER,
            needQualified = askedQuestions,
            priceChoice = null,
            timelineChoice = null,
            outcome = null
        ))
    }

    fun choosePrice(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryPriceChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterOneState(campaign)
        require(current.phase == ObjectiveDeliveryPhase.OFFER)
        return updateChapterOne(campaign, current.copy(priceChoice = choice))
    }

    fun chooseTimeline(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryTimelineChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterOneState(campaign)
        require(current.phase == ObjectiveDeliveryPhase.OFFER)
        return updateChapterOne(campaign, current.copy(timelineChoice = choice))
    }

    fun priceFor(
        model: ObjectiveDeliveryCompanyModel,
        choice: ObjectiveDeliveryPriceChoice
    ): Int = (model.referencePriceCents * choice.multiplier).roundToInt()

    fun evaluate(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryEvaluation {
        val board = chapterOneState(campaign)
        require(board.phase == ObjectiveDeliveryPhase.OFFER || board.phase == ObjectiveDeliveryPhase.RESULT)
        val priceChoice = requireNotNull(board.priceChoice) { "Un prix doit être choisi" }
        val timelineChoice = requireNotNull(board.timelineChoice) { "Un délai doit être choisi" }
        val model = campaign.companyModel
        val quotedPrice = priceFor(model, priceChoice)
        val quotedDays = timelineChoice.daysFor(model.requestedDays)
        val withinBudget = quotedPrice <= model.clientBudgetCents
        val deadlineMet = quotedDays <= model.requestedDays
        val failedConditions = listOf(!board.needQualified, !withinBudget, !deadlineMet).count { it }
        val outcome = when {
            failedConditions == 0 -> ObjectiveDeliveryOutcome.ORDER_ACCEPTED
            failedConditions == 1 -> ObjectiveDeliveryOutcome.CORRECTION_REQUESTED
            else -> ObjectiveDeliveryOutcome.LOST
        }
        return ObjectiveDeliveryEvaluation(
            quotedPriceCents = quotedPrice,
            quotedDays = quotedDays,
            needQualified = board.needQualified,
            priceWithinBudget = withinBudget,
            deadlineMet = deadlineMet,
            outcome = outcome
        )
    }

    fun submitOffer(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val evaluation = evaluate(campaign)
        val won = evaluation.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED
        val current = chapterOneState(campaign)
        val updated = updateChapterOne(campaign, current.copy(
            phase = ObjectiveDeliveryPhase.RESULT,
            outcome = evaluation.outcome
        ))
        return updated.copy(
            chapterOneWon = campaign.chapterOneWon || won,
            unlockedChapter = if (won) maxOf(campaign.unlockedChapter, 2) else campaign.unlockedChapter
        )
    }

    /** Starts an isolated chapter-one replay and remembers where the campaign should resume. */
    fun replayChapterOne(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.unlockedChapter >= 1)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        val nextAttempt = campaign.attemptNumber + 1
        return campaign.copy(
            activeChapter = 1,
            attemptNumber = nextAttempt,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 1,
                returnChapter = returnChapter,
                chapterOne = initialChapterOne
            )
        )
    }

    fun returnFromReplay(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val session = requireNotNull(campaign.replaySession)
        return campaign.copy(
            activeChapter = session.returnChapter,
            replaySession = null
        )
    }

    fun continueToChapterTwo(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.chapterOneWon && campaign.unlockedChapter >= 2)
        val replayBoard = campaign.replaySession?.takeIf { it.chapter == 1 }?.chapterOne
        val promoted = if (replayBoard?.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED) {
            campaign.copy(
                phase = replayBoard.phase,
                needQualified = replayBoard.needQualified,
                priceChoice = replayBoard.priceChoice,
                timelineChoice = replayBoard.timelineChoice,
                outcome = replayBoard.outcome
            )
        } else campaign
        return promoted.copy(activeChapter = 2, replaySession = null)
    }

    fun chooseChapterTwoPrice(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterTwoPriceChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterTwoState(campaign)
        require(campaign.activeChapter == 2 && current.phase == ObjectiveDeliveryChapterTwoPhase.QUOTE)
        return updateChapterTwo(campaign, current.copy(priceChoice = choice))
    }

    fun chooseChapterTwoTimeline(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryTimelineChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterTwoState(campaign)
        require(campaign.activeChapter == 2 && current.phase == ObjectiveDeliveryChapterTwoPhase.QUOTE)
        return updateChapterTwo(campaign, current.copy(timelineChoice = choice))
    }

    fun chapterTwoQuoteFor(
        model: ObjectiveDeliveryCompanyModel,
        choice: ObjectiveDeliveryChapterTwoPriceChoice
    ): Int {
        val totalCost = model.directCostCents + model.fixedCostShareCents
        return (totalCost * choice.multiplier).roundToInt()
    }

    fun submitChapterTwoQuote(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterTwoState(campaign)
        require(campaign.activeChapter == 2 && current.phase == ObjectiveDeliveryChapterTwoPhase.QUOTE)
        require(current.priceChoice != null && current.timelineChoice != null)
        return updateChapterTwo(campaign, current.copy(phase = ObjectiveDeliveryChapterTwoPhase.NEGOTIATION))
    }

    fun chooseNegotiation(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryNegotiationChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterTwoState(campaign)
        require(campaign.activeChapter == 2 && current.phase == ObjectiveDeliveryChapterTwoPhase.NEGOTIATION)
        return updateChapterTwo(campaign, current.copy(negotiationChoice = choice))
    }

    fun evaluateChapterTwo(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterTwoEvaluation {
        val board = chapterTwoState(campaign)
        require(board.phase == ObjectiveDeliveryChapterTwoPhase.NEGOTIATION ||
            board.phase == ObjectiveDeliveryChapterTwoPhase.RESULT)
        val priceChoice = requireNotNull(board.priceChoice) { "Un prix doit être choisi" }
        val timelineChoice = requireNotNull(board.timelineChoice) { "Un délai doit être choisi" }
        val negotiation = requireNotNull(board.negotiationChoice) { "Une réponse au client doit être choisie" }
        val model = campaign.companyModel
        val totalCost = model.directCostCents + model.fixedCostShareCents
        val baseQuote = chapterTwoQuoteFor(model, priceChoice)
        val finalPrice = (baseQuote * (100 - negotiation.discountPercent) / 100.0).roundToInt()
        val margin = finalPrice - totalCost
        val days = timelineChoice.daysFor(model.requestedDays)
        val withinBudget = finalPrice <= model.chapterTwoClientBudgetCents
        val deadlineMet = days <= model.requestedDays
        val aboveCost = margin > 0
        val failures = listOf(!withinBudget, !deadlineMet, !aboveCost).count { it }
        val outcome = when (failures) {
            0 -> ObjectiveDeliveryOutcome.ORDER_ACCEPTED
            1 -> ObjectiveDeliveryOutcome.CORRECTION_REQUESTED
            else -> ObjectiveDeliveryOutcome.LOST
        }
        return ObjectiveDeliveryChapterTwoEvaluation(
            baseQuoteCents = baseQuote,
            finalPriceCents = finalPrice,
            totalCostCents = totalCost,
            marginCents = margin,
            quotedDays = days,
            priceWithinBudget = withinBudget,
            deadlineMet = deadlineMet,
            aboveCost = aboveCost,
            outcome = outcome
        )
    }

    fun submitChapterTwoNegotiation(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val evaluation = evaluateChapterTwo(campaign)
        val current = chapterTwoState(campaign)
        val updated = updateChapterTwo(campaign, current.copy(
            phase = ObjectiveDeliveryChapterTwoPhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (campaign.replaySession?.chapter != 2 &&
            evaluation.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED
        ) {
            updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 3))
        } else updated
    }

    /** Keeps a chapter-two replay separate until the player chooses to keep its result. */
    fun replayChapterTwo(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 2 && campaign.unlockedChapter >= 2 && campaign.chapterOneWon)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        val currentAttempt = campaign.chapterTwoAttemptNumber + 1
        return campaign.copy(
            activeChapter = 2,
            chapterTwoAttemptNumber = currentAttempt,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 2,
                returnChapter = returnChapter,
                chapterTwo = initialChapterTwo
            )
        )
    }

    fun keepChapterTwoReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 2 })
        require(replay.chapterTwo?.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED)
        return campaign.copy(
            chapterTwo = replay.chapterTwo,
            unlockedChapter = maxOf(campaign.unlockedChapter, 3),
            replaySession = null
        )
    }

    fun continueToChapterThree(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 2 && campaign.unlockedChapter >= 3)
        require(campaign.chapterTwo.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED)
        return campaign.copy(activeChapter = 3, replaySession = null)
    }

    fun setHandoffItem(
        campaign: ObjectiveDeliveryCampaign,
        item: ObjectiveDeliveryHandoffItem,
        included: Boolean
    ): ObjectiveDeliveryCampaign {
        val current = chapterThreeState(campaign)
        require(campaign.activeChapter == 3 && current.phase == ObjectiveDeliveryChapterThreePhase.REVIEW)
        val updated = when (item) {
            ObjectiveDeliveryHandoffItem.SIGNED_QUOTE -> current.copy(signedQuoteAttached = included)
            ObjectiveDeliveryHandoffItem.TECHNICAL_SPECIFICATION -> current.copy(
                technicalSpecificationConfirmed = included
            )
            ObjectiveDeliveryHandoffItem.CUSTOMER_OPTIONS -> current.copy(customerOptionsConfirmed = included)
            ObjectiveDeliveryHandoffItem.DELIVERY_CONDITIONS -> current.copy(deliveryConditionsConfirmed = included)
            ObjectiveDeliveryHandoffItem.PROMISED_DATE -> current.copy(promisedDateConfirmed = included)
        }
        return updateChapterThree(campaign, updated)
    }

    fun evaluateChapterThree(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterThreeEvaluation {
        val board = chapterThreeState(campaign)
        require(board.phase == ObjectiveDeliveryChapterThreePhase.REVIEW ||
            board.phase == ObjectiveDeliveryChapterThreePhase.RESULT)
        val missing = listOf(
            !board.signedQuoteAttached,
            !board.technicalSpecificationConfirmed,
            !board.customerOptionsConfirmed,
            !board.deliveryConditionsConfirmed,
            !board.promisedDateConfirmed
        ).count { it }
        val outcome = when {
            missing == 0 -> ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH
            missing == 1 -> ObjectiveDeliveryChapterThreeOutcome.NEEDS_CLARIFICATION
            else -> ObjectiveDeliveryChapterThreeOutcome.LAUNCH_BLOCKED
        }
        return ObjectiveDeliveryChapterThreeEvaluation(missing, outcome)
    }

    fun submitChapterThreeReview(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterThreeState(campaign)
        require(campaign.activeChapter == 3 && current.phase == ObjectiveDeliveryChapterThreePhase.REVIEW)
        val evaluation = evaluateChapterThree(campaign)
        val updated = updateChapterThree(campaign, current.copy(
            phase = ObjectiveDeliveryChapterThreePhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (evaluation.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH) {
            updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 4))
        } else updated
    }

    fun correctChapterThreeReview(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterThreeState(campaign)
        require(campaign.activeChapter == 3 && current.phase == ObjectiveDeliveryChapterThreePhase.RESULT)
        require(current.outcome != ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH)
        return updateChapterThree(campaign, current.copy(
            phase = ObjectiveDeliveryChapterThreePhase.REVIEW,
            outcome = null
        ))
    }

    /** Starts an isolated handoff replay and leaves the saved campaign board untouched. */
    fun replayChapterThree(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 3 && campaign.unlockedChapter >= 3)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 3,
            chapterThreeAttemptNumber = campaign.chapterThreeAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 3,
                returnChapter = returnChapter,
                chapterThree = initialChapterThree
            )
        )
    }

    fun keepChapterThreeReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 3 })
        require(replay.chapterThree?.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH)
        return campaign.copy(
            chapterThree = replay.chapterThree,
            unlockedChapter = maxOf(campaign.unlockedChapter, 4),
            replaySession = null
        )
    }

    fun continueToChapterFour(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 3 && campaign.unlockedChapter >= 4)
        require(campaign.chapterThree.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH)
        return campaign.copy(activeChapter = 4, replaySession = null)
    }

    fun assignChapterFourTask(
        campaign: ObjectiveDeliveryCampaign,
        task: ObjectiveDeliveryChapterFourTask,
        employee: ObjectiveDeliveryTeamMember
    ): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        val updated = when (task) {
            ObjectiveDeliveryChapterFourTask.CUSTOMER_FILE -> current.copy(customerFileAssignee = employee)
            ObjectiveDeliveryChapterFourTask.PRODUCTION -> current.copy(productionAssignee = employee)
            ObjectiveDeliveryChapterFourTask.DISPATCH -> current.copy(dispatchAssignee = employee)
        }
        return updateChapterFour(campaign, updated)
    }

    fun chooseChapterFourLeave(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterFourLeaveChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        return updateChapterFour(campaign, current.copy(leaveChoice = choice))
    }

    fun chooseChapterFourHire(
        campaign: ObjectiveDeliveryCampaign,
        decision: ObjectiveDeliveryChapterFourHireDecision
    ): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        val updated = current.copy(
            hireDecision = decision,
            hireContract = if (decision == ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE) {
                current.hireContract
            } else null,
            hireClassification = if (decision == ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE) {
                current.hireClassification
            } else null
        )
        return updateChapterFour(campaign, updated)
    }

    fun setChapterFourHireTerms(
        campaign: ObjectiveDeliveryCampaign,
        contract: ObjectiveDeliveryChapterFourContract?,
        classification: ObjectiveDeliveryChapterFourClassification?
    ): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        require(current.hireDecision == ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE)
        return updateChapterFour(campaign, current.copy(
            hireContract = contract,
            hireClassification = classification
        ))
    }

    fun recordChapterFourAnnualReview(
        campaign: ObjectiveDeliveryCampaign,
        employee: ObjectiveDeliveryTeamMember,
        factsDiscussed: Boolean
    ): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        require(employee != ObjectiveDeliveryTeamMember.TEMPORARY_TECHNICIAN)
        return updateChapterFour(campaign, current.copy(
            reviewedEmployee = employee,
            reviewFactsDiscussed = factsDiscussed
        ))
    }

    fun setChapterFourRaiseEnvelope(campaign: ObjectiveDeliveryCampaign, points: Int): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        require(points in 0..5)
        return updateChapterFour(campaign, current.copy(raiseEnvelopePoints = points))
    }

    fun setChapterFourIndividualRaise(campaign: ObjectiveDeliveryCampaign, points: Int): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        require(points in 0..5)
        return updateChapterFour(campaign, current.copy(individualRaisePoints = points))
    }

    fun evaluateChapterFour(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterFourEvaluation {
        val state = chapterFourState(campaign)
        require(state.phase == ObjectiveDeliveryChapterFourPhase.PLAN ||
            state.phase == ObjectiveDeliveryChapterFourPhase.RESULT)
        return evaluateChapterFourState(state)
    }

    fun evaluateChapterFourState(state: ObjectiveDeliveryChapterFourState): ObjectiveDeliveryChapterFourEvaluation {
        val tasksCovered = state.customerFileAssignee == ObjectiveDeliveryTeamMember.ELISE &&
            state.dispatchAssignee == ObjectiveDeliveryTeamMember.NOAH &&
            (state.productionAssignee == ObjectiveDeliveryTeamMember.KARIM ||
                state.productionAssignee == ObjectiveDeliveryTeamMember.TEMPORARY_TECHNICIAN)
        val approvedLeave = state.leaveChoice == ObjectiveDeliveryChapterFourLeaveChoice.APPROVE_WITH_COVER
        val leaveCovered = when (state.leaveChoice) {
            ObjectiveDeliveryChapterFourLeaveChoice.APPROVE_WITH_COVER ->
                state.productionAssignee == ObjectiveDeliveryTeamMember.TEMPORARY_TECHNICIAN
            ObjectiveDeliveryChapterFourLeaveChoice.OFFER_ALTERNATIVE_DATE ->
                state.productionAssignee == ObjectiveDeliveryTeamMember.KARIM
            null -> false
        }
        val hiringDecisionCoherent = when (state.hireDecision) {
            ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE ->
                approvedLeave &&
                    state.hireContract in setOf(
                        ObjectiveDeliveryChapterFourContract.CDD,
                        ObjectiveDeliveryChapterFourContract.INTERIM
                    ) &&
                    state.hireClassification == ObjectiveDeliveryChapterFourClassification.NON_CADRE
            ObjectiveDeliveryChapterFourHireDecision.DECLINE_WITH_ALTERNATIVE ->
                !approvedLeave && state.hireContract == null && state.hireClassification == null
            null -> false
        }
        val annualReviewComplete = state.reviewedEmployee != null && state.reviewFactsDiscussed
        val raisePoints = state.individualRaisePoints
        val envelopePoints = state.raiseEnvelopePoints
        val raisesWithinEnvelope = envelopePoints != null && raisePoints != null &&
            raisePoints in 0..5 && raisePoints <= envelopePoints
        val outcome = if (tasksCovered && leaveCovered && hiringDecisionCoherent &&
            annualReviewComplete && raisesWithinEnvelope
        ) ObjectiveDeliveryChapterFourOutcome.TEAM_READY
        else ObjectiveDeliveryChapterFourOutcome.PLAN_NEEDS_REVIEW
        return ObjectiveDeliveryChapterFourEvaluation(
            tasksCovered, leaveCovered, hiringDecisionCoherent, annualReviewComplete, raisesWithinEnvelope, outcome
        )
    }

    fun submitChapterFourPlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.PLAN)
        val evaluation = evaluateChapterFour(campaign)
        val updated = updateChapterFour(campaign, current.copy(
            phase = ObjectiveDeliveryChapterFourPhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (evaluation.outcome == ObjectiveDeliveryChapterFourOutcome.TEAM_READY) {
            updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 5))
        } else updated
    }

    fun correctChapterFourPlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterFourState(campaign)
        require(campaign.activeChapter == 4 && current.phase == ObjectiveDeliveryChapterFourPhase.RESULT)
        require(current.outcome != ObjectiveDeliveryChapterFourOutcome.TEAM_READY)
        return updateChapterFour(campaign, current.copy(
            phase = ObjectiveDeliveryChapterFourPhase.PLAN,
            outcome = null
        ))
    }

    fun replayChapterFour(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 4 && campaign.unlockedChapter >= 4)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 4,
            chapterFourAttemptNumber = campaign.chapterFourAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 4,
                returnChapter = returnChapter,
                chapterFour = initialChapterFour
            )
        )
    }

    fun keepChapterFourReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 4 })
        require(replay.chapterFour?.outcome == ObjectiveDeliveryChapterFourOutcome.TEAM_READY)
        return campaign.copy(chapterFour = replay.chapterFour, unlockedChapter = maxOf(campaign.unlockedChapter, 5),
            activeChapter = replay.returnChapter,
            replaySession = null)
    }

    fun continueToChapterFive(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 4 && campaign.replaySession == null)
        require(campaign.unlockedChapter >= 5)
        require(campaign.chapterFour.outcome == ObjectiveDeliveryChapterFourOutcome.TEAM_READY)
        return campaign.copy(activeChapter = 5)
    }

    fun chooseChapterFiveSupplier(
        campaign: ObjectiveDeliveryCampaign,
        supplier: ObjectiveDeliveryChapterFiveSupplier
    ): ObjectiveDeliveryCampaign {
        val current = chapterFiveState(campaign)
        require(campaign.activeChapter == 5 && current.phase == ObjectiveDeliveryChapterFivePhase.PROCUREMENT)
        return updateChapterFive(campaign, current.copy(supplier = supplier))
    }

    fun chooseChapterFiveQuantity(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterFiveQuantityChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterFiveState(campaign)
        require(campaign.activeChapter == 5 && current.phase == ObjectiveDeliveryChapterFivePhase.PROCUREMENT)
        return updateChapterFive(campaign, current.copy(quantityChoice = choice))
    }

    fun chapterFiveOrderUnits(
        companyModel: ObjectiveDeliveryCompanyModel,
        choice: ObjectiveDeliveryChapterFiveQuantityChoice
    ): Int = (companyModel.chapterFiveProfile().purchaseShortfallUnits + choice.adjustment).coerceAtLeast(0)

    fun chapterFiveOrderCostCents(
        companyModel: ObjectiveDeliveryCompanyModel,
        supplier: ObjectiveDeliveryChapterFiveSupplier,
        choice: ObjectiveDeliveryChapterFiveQuantityChoice
    ): Int {
        val profile = companyModel.chapterFiveProfile()
        return (chapterFiveOrderUnits(companyModel, choice) * profile.unitCostCents *
            supplier.unitCostMultiplier).roundToInt()
    }

    fun evaluateChapterFive(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterFiveEvaluation {
        require(campaign.activeChapter == 5)
        return evaluateChapterFiveState(campaign.companyModel, chapterFiveState(campaign))
    }

    fun evaluateChapterFiveState(
        companyModel: ObjectiveDeliveryCompanyModel,
        state: ObjectiveDeliveryChapterFiveState
    ): ObjectiveDeliveryChapterFiveEvaluation {
        val profile = companyModel.chapterFiveProfile()
        val supplier = state.supplier
        val quantityChoice = state.quantityChoice
        val orderedUnits = quantityChoice?.let { chapterFiveOrderUnits(companyModel, it) } ?: 0
        val orderCost = if (supplier != null && quantityChoice != null) {
            chapterFiveOrderCostCents(companyModel, supplier, quantityChoice)
        } else 0
        val stockCoversDemand = supplier != null && quantityChoice != null &&
            orderedUnits >= profile.purchaseShortfallUnits
        val supplierOnTime = supplier != null && supplier.arrivalDays <= profile.procurementDeadlineDays
        val purchaseWithinBudget = supplier != null && quantityChoice != null &&
            orderCost <= profile.purchaseBudgetCents
        val outcome = if (stockCoversDemand && supplierOnTime && purchaseWithinBudget) {
            ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY
        } else ObjectiveDeliveryChapterFiveOutcome.PLAN_NEEDS_REVIEW
        return ObjectiveDeliveryChapterFiveEvaluation(
            neededUnits = profile.purchaseShortfallUnits,
            orderedUnits = orderedUnits,
            orderCostCents = orderCost,
            stockCoversDemand = stockCoversDemand,
            supplierOnTime = supplierOnTime,
            purchaseWithinBudget = purchaseWithinBudget,
            outcome = outcome
        )
    }

    fun submitChapterFivePlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterFiveState(campaign)
        require(campaign.activeChapter == 5 && current.phase == ObjectiveDeliveryChapterFivePhase.PROCUREMENT)
        require(current.supplier != null && current.quantityChoice != null)
        val evaluation = evaluateChapterFive(campaign)
        val updated = updateChapterFive(campaign, current.copy(
            phase = ObjectiveDeliveryChapterFivePhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (evaluation.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY &&
            campaign.replaySession?.chapter != 5
        ) {
            updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 6))
        } else updated
    }

    fun correctChapterFivePlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterFiveState(campaign)
        require(campaign.activeChapter == 5 && current.phase == ObjectiveDeliveryChapterFivePhase.RESULT)
        require(current.outcome != ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY)
        return updateChapterFive(campaign, current.copy(
            phase = ObjectiveDeliveryChapterFivePhase.PROCUREMENT,
            outcome = null
        ))
    }

    fun replayChapterFive(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 5 && campaign.unlockedChapter >= 5)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 5,
            chapterFiveAttemptNumber = campaign.chapterFiveAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 5,
                returnChapter = returnChapter,
                chapterFive = initialChapterFive
            )
        )
    }

    fun keepChapterFiveReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 5 })
        require(replay.chapterFive?.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY)
        return campaign.copy(
            chapterFive = replay.chapterFive,
            unlockedChapter = maxOf(campaign.unlockedChapter, 6),
            activeChapter = replay.returnChapter,
            replaySession = null
        )
    }

    fun continueToChapterSix(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 5 && campaign.replaySession == null)
        require(campaign.unlockedChapter >= 6)
        require(campaign.chapterFive.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY)
        return campaign.copy(activeChapter = 6)
    }

    fun chooseChapterSixProductionPlan(
        campaign: ObjectiveDeliveryCampaign,
        plan: ObjectiveDeliveryChapterSixProductionPlan
    ): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.PLANNING)
        return updateChapterSix(campaign, current.copy(productionPlan = plan))
    }

    fun submitChapterSixPlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.PLANNING)
        require(current.productionPlan != null)
        return updateChapterSix(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSixPhase.QUALITY_CONTROL,
            inspectionChoice = null,
            correctionChoice = null,
            outcome = null
        ))
    }

    fun chooseChapterSixInspection(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterSixInspectionChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.QUALITY_CONTROL)
        return updateChapterSix(campaign, current.copy(inspectionChoice = choice))
    }

    fun submitChapterSixInspection(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.QUALITY_CONTROL)
        val choice = requireNotNull(current.inspectionChoice)
        return if (choice == ObjectiveDeliveryChapterSixInspectionChoice.FULL_CHECKLIST) {
            updateChapterSix(campaign, current.copy(
                phase = ObjectiveDeliveryChapterSixPhase.CORRECTION,
                correctionChoice = null,
                outcome = null
            ))
        } else {
            updateChapterSix(campaign, current.copy(
                phase = ObjectiveDeliveryChapterSixPhase.RESULT,
                correctionChoice = null,
                outcome = ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE
            ))
        }
    }

    fun retryChapterSixInspection(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.RESULT)
        require(current.outcome == ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE)
        return updateChapterSix(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSixPhase.QUALITY_CONTROL,
            inspectionChoice = null,
            outcome = null
        ))
    }

    fun chooseChapterSixCorrection(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterSixCorrectionChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.CORRECTION)
        return updateChapterSix(campaign, current.copy(correctionChoice = choice))
    }

    fun evaluateChapterSix(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterSixEvaluation {
        require(campaign.activeChapter == 6)
        return evaluateChapterSixState(campaign.companyModel, chapterSixState(campaign))
    }

    fun evaluateChapterSixState(
        companyModel: ObjectiveDeliveryCompanyModel,
        state: ObjectiveDeliveryChapterSixState
    ): ObjectiveDeliveryChapterSixEvaluation {
        val profile = companyModel.chapterSixProfile()
        val plan = state.productionPlan
        val plannedDays = when (plan) {
            ObjectiveDeliveryChapterSixProductionPlan.BALANCED_FLOW -> profile.balancedProductionDays
            ObjectiveDeliveryChapterSixProductionPlan.PARALLEL_PREPARATION -> profile.parallelProductionDays
            null -> 0
        }
        val correction = state.correctionChoice
        val correctionDays = if (correction == ObjectiveDeliveryChapterSixCorrectionChoice.REWORK_AND_RECHECK) 2 else 0
        val defectDetected = state.inspectionChoice == ObjectiveDeliveryChapterSixInspectionChoice.FULL_CHECKLIST
        val outcome = when {
            !defectDetected -> ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE
            correction == ObjectiveDeliveryChapterSixCorrectionChoice.REWORK_AND_RECHECK ->
                ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH
            correction == ObjectiveDeliveryChapterSixCorrectionChoice.REQUEST_CUSTOMER_DEVIATION ->
                ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION
            else -> ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD
        }
        val finishDays = plannedDays + correctionDays
        return ObjectiveDeliveryChapterSixEvaluation(
            plannedProductionDays = plannedDays,
            correctionDays = correctionDays,
            estimatedFinishDays = finishDays,
            promisedDays = companyModel.requestedDays,
            deadlineMet = plan != null && finishDays <= companyModel.requestedDays,
            correctionCostCents = if (correction == ObjectiveDeliveryChapterSixCorrectionChoice.REWORK_AND_RECHECK) {
                profile.reworkCostCents
            } else 0,
            defectDetected = defectDetected,
            dispositionApproved = outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION,
            outcome = outcome
        )
    }

    fun submitChapterSixCorrection(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.CORRECTION)
        require(current.correctionChoice != null)
        val evaluation = evaluateChapterSix(campaign)
        val updated = updateChapterSix(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSixPhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (evaluation.readyForDispatch && campaign.replaySession?.chapter != 6) {
            updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 7))
        } else updated
    }

    fun reopenChapterSixCorrection(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSixState(campaign)
        require(campaign.activeChapter == 6 && current.phase == ObjectiveDeliveryChapterSixPhase.RESULT)
        require(current.outcome == ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD)
        return updateChapterSix(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSixPhase.CORRECTION,
            correctionChoice = null,
            outcome = null
        ))
    }

    fun replayChapterSix(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 6 && campaign.unlockedChapter >= 6)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 6,
            chapterSixAttemptNumber = campaign.chapterSixAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 6,
                returnChapter = returnChapter,
                chapterSix = initialChapterSix
            )
        )
    }

    fun keepChapterSixReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 6 })
        val replayBoard = requireNotNull(replay.chapterSix)
        require(replayBoard.outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
            replayBoard.outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION)
        return campaign.copy(
            chapterSix = replayBoard,
            unlockedChapter = maxOf(campaign.unlockedChapter, 7),
            activeChapter = replay.returnChapter,
            replaySession = null
        )
    }

    fun continueToChapterSeven(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 6 && campaign.replaySession == null)
        require(campaign.unlockedChapter >= 7)
        require(campaign.chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
            campaign.chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION)
        return campaign.copy(activeChapter = 7)
    }

    fun chooseChapterSevenDelivery(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterSevenDeliveryChoice
    ): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.DELIVERY_PLAN)
        return updateChapterSeven(campaign, current.copy(deliveryChoice = choice))
    }

    fun setChapterSevenPhotoSharing(
        campaign: ObjectiveDeliveryCampaign,
        shared: Boolean
    ): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.DELIVERY_PLAN)
        return updateChapterSeven(campaign, current.copy(photoShared = shared))
    }

    fun submitChapterSevenDeliveryPlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.DELIVERY_PLAN)
        require(current.deliveryChoice != null)
        return updateChapterSeven(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSevenPhase.ORDER_CHECK,
            outcome = null
        ))
    }

    fun setChapterSevenOrderCheck(
        campaign: ObjectiveDeliveryCampaign,
        items: Boolean = chapterSevenState(campaign).itemsChecked,
        quantities: Boolean = chapterSevenState(campaign).quantitiesChecked,
        options: Boolean = chapterSevenState(campaign).optionsChecked,
        quality: Boolean = chapterSevenState(campaign).qualityChecked,
        documents: Boolean = chapterSevenState(campaign).deliveryDocumentsChecked
    ): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.ORDER_CHECK)
        return updateChapterSeven(campaign, current.copy(
            itemsChecked = items,
            quantitiesChecked = quantities,
            optionsChecked = options,
            qualityChecked = quality,
            deliveryDocumentsChecked = documents
        ))
    }

    fun submitChapterSevenOrderCheck(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.ORDER_CHECK)
        val evaluation = evaluateChapterSevenState(campaign.companyModel, current)
        return if (evaluation.orderIsComplete) {
            updateChapterSeven(campaign, current.copy(
                phase = ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM,
                outcome = null
            ))
        } else {
            updateChapterSeven(campaign, current.copy(
                phase = ObjectiveDeliveryChapterSevenPhase.RESULT,
                outcome = ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE
            ))
        }
    }

    fun retryChapterSevenOrderCheck(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.RESULT)
        require(current.outcome == ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE)
        return updateChapterSeven(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSevenPhase.ORDER_CHECK,
            outcome = null
        ))
    }

    fun chooseChapterSevenClaimAction(
        campaign: ObjectiveDeliveryCampaign,
        action: ObjectiveDeliveryChapterSevenClaimAction
    ): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM)
        return updateChapterSeven(campaign, current.copy(claimAction = action))
    }

    fun evaluateChapterSeven(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterSevenEvaluation {
        require(campaign.activeChapter == 7)
        return evaluateChapterSevenState(campaign.companyModel, chapterSevenState(campaign))
    }

    fun evaluateChapterSevenState(
        companyModel: ObjectiveDeliveryCompanyModel,
        state: ObjectiveDeliveryChapterSevenState
    ): ObjectiveDeliveryChapterSevenEvaluation {
        val completedChecks = listOf(
            state.itemsChecked,
            state.quantitiesChecked,
            state.optionsChecked,
            state.qualityChecked,
            state.deliveryDocumentsChecked
        ).count { it }
        val orderIsComplete = completedChecks == 5
        val action = state.claimAction
        val outcome = when {
            !orderIsComplete -> ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE
            action == ObjectiveDeliveryChapterSevenClaimAction.INVESTIGATE_AND_FOLLOW_UP ||
                action == ObjectiveDeliveryChapterSevenClaimAction.CORRECT_AND_FOLLOW_UP ->
                ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED
            else -> ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED
        }
        return ObjectiveDeliveryChapterSevenEvaluation(
            completedChecks = completedChecks,
            totalChecks = 5,
            orderIsComplete = orderIsComplete,
            deliveryCostCents = state.deliveryChoice?.costCents ?: 0,
            complaintCostCents = action?.costCents ?: 0,
            photoShared = state.photoShared,
            claimResolved = outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED,
            outcome = outcome
        )
    }

    fun submitChapterSevenClaimAction(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM)
        require(current.claimAction != null)
        val evaluation = evaluateChapterSeven(campaign)
        val updated = updateChapterSeven(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSevenPhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (evaluation.claimResolved && campaign.replaySession?.chapter != 7) {
            updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 8))
        } else updated
    }

    fun reopenChapterSevenClaim(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val current = chapterSevenState(campaign)
        require(campaign.activeChapter == 7 && current.phase == ObjectiveDeliveryChapterSevenPhase.RESULT)
        require(current.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED)
        return updateChapterSeven(campaign, current.copy(
            phase = ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM,
            claimAction = null,
            outcome = null
        ))
    }

    fun replayChapterSeven(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 7 && campaign.unlockedChapter >= 7)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 7,
            chapterSevenAttemptNumber = campaign.chapterSevenAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 7,
                returnChapter = returnChapter,
                chapterSeven = initialChapterSeven
            )
        )
    }

    fun keepChapterSevenReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 7 })
        val replayBoard = requireNotNull(replay.chapterSeven)
        require(replayBoard.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED)
        return campaign.copy(
            chapterSeven = replayBoard,
            unlockedChapter = maxOf(campaign.unlockedChapter, 8),
            activeChapter = replay.returnChapter,
            replaySession = null
        )
    }

    fun continueToChapterEight(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 7 && campaign.replaySession == null)
        require(campaign.unlockedChapter >= 8)
        require(campaign.chapterSeven.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED)
        return campaign.copy(activeChapter = 8)
    }

    fun chooseChapterEightStaffing(
        campaign: ObjectiveDeliveryCampaign,
        staffing: ObjectiveDeliveryChapterEightStaffing
    ): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.WEEK_PLAN)
        return updateChapterEight(campaign, state.copy(staffing = staffing))
    }

    fun chooseChapterEightBonusCriteria(
        campaign: ObjectiveDeliveryCampaign,
        criteria: ObjectiveDeliveryChapterEightBonusCriteria
    ): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.WEEK_PLAN)
        return updateChapterEight(campaign, state.copy(bonusCriteria = criteria))
    }

    fun chooseChapterEightOvertime(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterEightOvertimeChoice
    ): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.WEEK_PLAN)
        return updateChapterEight(campaign, state.copy(overtimeChoice = choice))
    }

    fun submitChapterEightWeekPlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.WEEK_PLAN)
        require(state.staffing != null && state.bonusCriteria != null && state.overtimeChoice != null)
        return updateChapterEight(campaign, state.copy(
            phase = ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS,
            outcome = null
        ))
    }

    fun chooseChapterEightAbsenceResponse(
        campaign: ObjectiveDeliveryCampaign,
        response: ObjectiveDeliveryChapterEightAbsenceResponse
    ): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS)
        return updateChapterEight(campaign, state.copy(absenceResponse = response))
    }

    fun chooseChapterEightPayrollResponse(
        campaign: ObjectiveDeliveryCampaign,
        response: ObjectiveDeliveryChapterEightPayrollResponse
    ): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS)
        return updateChapterEight(campaign, state.copy(payrollResponse = response))
    }

    fun chooseChapterEightConflictResponse(
        campaign: ObjectiveDeliveryCampaign,
        response: ObjectiveDeliveryChapterEightConflictResponse
    ): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS)
        return updateChapterEight(campaign, state.copy(conflictResponse = response))
    }

    fun setChapterEightEventParticipants(campaign: ObjectiveDeliveryCampaign, count: Int): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS)
        require(count in 0..10)
        return updateChapterEight(campaign, state.copy(voluntaryEventParticipants = count))
    }

    fun submitChapterEightTeamEvents(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS)
        require(state.absenceResponse != null && state.payrollResponse != null && state.conflictResponse != null)
        return updateChapterEight(campaign, state.copy(
            phase = ObjectiveDeliveryChapterEightPhase.ANNUAL_REVIEW,
            outcome = null
        ))
    }

    fun setChapterEightRaiseAllocation(
        campaign: ObjectiveDeliveryCampaign,
        envelopePercent: Int,
        individualPercent: Int
    ): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.ANNUAL_REVIEW)
        require(envelopePercent in 0..5 && individualPercent in 0..6)
        return updateChapterEight(campaign, state.copy(
            raiseEnvelopePercent = envelopePercent,
            individualRaisePercent = individualPercent
        ))
    }

    fun setChapterEightProfitShareConfirmed(campaign: ObjectiveDeliveryCampaign, confirmed: Boolean): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.ANNUAL_REVIEW)
        return updateChapterEight(campaign, state.copy(equalProfitShareConfirmed = confirmed))
    }

    fun evaluateChapterEight(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterEightEvaluation {
        require(campaign.activeChapter == 8)
        return evaluateChapterEightState(campaign.companyModel, chapterEightState(campaign))
    }

    fun evaluateChapterEightState(
        companyModel: ObjectiveDeliveryCompanyModel,
        state: ObjectiveDeliveryChapterEightState
    ): ObjectiveDeliveryChapterEightEvaluation {
        val profile = companyModel.chapterEightProfile()
        val staffingMatches = state.staffing == ObjectiveDeliveryChapterEightStaffing.STANDARD_TEN
        val bonusBalanced = state.bonusCriteria == ObjectiveDeliveryChapterEightBonusCriteria.BALANCED
        val overtimeControlled = state.overtimeChoice == ObjectiveDeliveryChapterEightOvertimeChoice.REPLAN_WITHIN_SCHEDULE ||
            state.overtimeChoice == ObjectiveDeliveryChapterEightOvertimeChoice.AUTHORIZE_TARGETED
        val absenceFair = state.absenceResponse == ObjectiveDeliveryChapterEightAbsenceResponse.REASSIGN_QUALIFIED ||
            state.absenceResponse == ObjectiveDeliveryChapterEightAbsenceResponse.INFORM_AND_RESCHEDULE
        val payrollCorrected = state.payrollResponse == ObjectiveDeliveryChapterEightPayrollResponse.VERIFY_AND_CORRECT
        val conflictAddressed = state.conflictResponse == ObjectiveDeliveryChapterEightConflictResponse.LISTEN_AND_MEDIATE ||
            state.conflictResponse == ObjectiveDeliveryChapterEightConflictResponse.CLARIFY_TASKS
        val raisesWithinEnvelope = state.raiseEnvelopePercent != null && state.individualRaisePercent != null &&
            state.individualRaisePercent <= 5 && state.individualRaisePercent <= state.raiseEnvelopePercent
        val shareConfirmed = !profile.profitShareEligible || state.equalProfitShareConfirmed
        val successful = staffingMatches && bonusBalanced && overtimeControlled && absenceFair && payrollCorrected &&
            conflictAddressed && raisesWithinEnvelope && shareConfirmed
        return ObjectiveDeliveryChapterEightEvaluation(
            staffingMatchesWorkload = staffingMatches,
            bonusCriteriaBalanced = bonusBalanced,
            overtimeWasControlled = overtimeControlled,
            absenceHandledFairly = absenceFair,
            payrollWasCorrected = payrollCorrected,
            conflictAddressed = conflictAddressed,
            raisesWithinEnvelope = raisesWithinEnvelope,
            profitShareCents = profile.collectiveProfitShareCents,
            profitShareConfirmed = shareConfirmed,
            weeklyBonusCents = if (staffingMatches && bonusBalanced && overtimeControlled) 40_000 else 0,
            voluntaryEventParticipants = state.voluntaryEventParticipants,
            outcome = if (successful) ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS
            else ObjectiveDeliveryChapterEightOutcome.TEAM_PLAN_NEEDS_REVIEW
        )
    }

    fun submitChapterEightAnnualReview(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.ANNUAL_REVIEW)
        require(state.raiseEnvelopePercent != null && state.individualRaisePercent != null)
        val evaluation = evaluateChapterEight(campaign)
        val updated = updateChapterEight(campaign, state.copy(
            phase = ObjectiveDeliveryChapterEightPhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (evaluation.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS &&
            campaign.replaySession?.chapter != 8
        ) updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 9)) else updated
    }

    fun retryChapterEightPlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterEightState(campaign)
        require(campaign.activeChapter == 8 && state.phase == ObjectiveDeliveryChapterEightPhase.RESULT)
        require(state.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_PLAN_NEEDS_REVIEW)
        return updateChapterEight(campaign, initialChapterEight)
    }

    fun replayChapterEight(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 8 && campaign.unlockedChapter >= 8)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 8,
            chapterEightAttemptNumber = campaign.chapterEightAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 8, returnChapter = returnChapter, chapterEight = initialChapterEight
            )
        )
    }

    fun keepChapterEightReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 8 })
        val board = requireNotNull(replay.chapterEight)
        require(board.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS)
        return campaign.copy(
            chapterEight = board,
            unlockedChapter = maxOf(campaign.unlockedChapter, 9),
            activeChapter = replay.returnChapter,
            replaySession = null
        )
    }

    fun continueToChapterNine(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 8 && campaign.replaySession == null)
        require(campaign.unlockedChapter >= 9)
        require(campaign.chapterEight.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS)
        return campaign.copy(activeChapter = 9)
    }

    fun chooseChapterNineInspectionResponse(
        campaign: ObjectiveDeliveryCampaign,
        response: ObjectiveDeliveryChapterNineInspectionResponse
    ): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.INSPECTION)
        return updateChapterNine(campaign, state.copy(inspectionResponse = response))
    }

    fun submitChapterNineInspection(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.INSPECTION)
        require(state.inspectionResponse != null)
        return updateChapterNine(campaign, state.copy(
            phase = ObjectiveDeliveryChapterNinePhase.SAFETY_ACTION,
            outcome = null
        ))
    }

    fun chooseChapterNineSafetyResponse(
        campaign: ObjectiveDeliveryCampaign,
        response: ObjectiveDeliveryChapterNineSafetyResponse
    ): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.SAFETY_ACTION)
        return updateChapterNine(campaign, state.copy(safetyResponse = response))
    }

    fun chooseChapterNineIntegrityResponse(
        campaign: ObjectiveDeliveryCampaign,
        response: ObjectiveDeliveryChapterNineIntegrityResponse
    ): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.SAFETY_ACTION)
        return updateChapterNine(campaign, state.copy(integrityResponse = response))
    }

    fun submitChapterNineSafetyAction(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.SAFETY_ACTION)
        require(state.safetyResponse != null && state.integrityResponse != null)
        return updateChapterNine(campaign, state.copy(
            phase = ObjectiveDeliveryChapterNinePhase.CORRECTION,
            outcome = null
        ))
    }

    fun chooseChapterNineCorrection(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterNineCorrectionChoice
    ): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.CORRECTION)
        return updateChapterNine(campaign, state.copy(correctionChoice = choice))
    }

    fun evaluateChapterNine(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterNineEvaluation {
        require(campaign.activeChapter == 9)
        return evaluateChapterNineState(chapterNineState(campaign))
    }

    fun evaluateChapterNineState(state: ObjectiveDeliveryChapterNineState): ObjectiveDeliveryChapterNineEvaluation {
        val transparent = state.inspectionResponse == ObjectiveDeliveryChapterNineInspectionResponse.COOPERATE_AND_RECORD
        val stopped = state.safetyResponse == ObjectiveDeliveryChapterNineSafetyResponse.STOP_AFFECTED_WORK
        val honest = state.integrityResponse == ObjectiveDeliveryChapterNineIntegrityResponse.REFUSE_AND_REPORT
        val onTime = state.correctionChoice == ObjectiveDeliveryChapterNineCorrectionChoice.CORRECT_AND_REQUEST_CHECK
        val outcome = when {
            state.correctionChoice == ObjectiveDeliveryChapterNineCorrectionChoice.MISS_DEADLINE ->
                ObjectiveDeliveryChapterNineOutcome.CLOSED_AFTER_DEADLINE
            transparent && stopped && honest && onTime ->
                ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION
            else -> ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD
        }
        return ObjectiveDeliveryChapterNineEvaluation(
            inspectionWasTransparent = transparent,
            affectedWorkWasStopped = stopped,
            integrityWasProtected = honest,
            correctionWithinDeadline = onTime,
            reopeningAuthorized = outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION,
            outcome = outcome
        )
    }

    fun submitChapterNineCorrection(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.CORRECTION)
        require(state.correctionChoice != null)
        val evaluation = evaluateChapterNine(campaign)
        val updated = updateChapterNine(campaign, state.copy(
            phase = ObjectiveDeliveryChapterNinePhase.RESULT,
            outcome = evaluation.outcome
        ))
        return if (evaluation.reopeningAuthorized && campaign.replaySession?.chapter != 9) {
            updated.copy(unlockedChapter = maxOf(updated.unlockedChapter, 10))
        } else updated
    }

    fun retryChapterNineCompliance(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterNineState(campaign)
        require(campaign.activeChapter == 9 && state.phase == ObjectiveDeliveryChapterNinePhase.RESULT)
        require(state.outcome == ObjectiveDeliveryChapterNineOutcome.COMPLIANCE_HOLD)
        return updateChapterNine(campaign, initialChapterNine)
    }

    fun replayChapterNine(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 9 && campaign.unlockedChapter >= 9)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 9,
            chapterNineAttemptNumber = campaign.chapterNineAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 9, returnChapter = returnChapter, chapterNine = initialChapterNine
            )
        )
    }

    fun keepChapterNineReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 9 })
        val board = requireNotNull(replay.chapterNine)
        require(board.outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION)
        return campaign.copy(
            chapterNine = board,
            unlockedChapter = maxOf(campaign.unlockedChapter, 10),
            activeChapter = replay.returnChapter,
            replaySession = null
        )
    }

    fun continueToChapterTen(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter == 9 && campaign.replaySession == null)
        require(campaign.unlockedChapter >= 10)
        require(campaign.chapterNine.outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION)
        return campaign.copy(activeChapter = 10)
    }

    fun chooseChapterTenSupplier(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterTenSupplierChoice
    ): ObjectiveDeliveryCampaign {
        val state = chapterTenState(campaign)
        require(campaign.activeChapter == 10 && state.phase == ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN)
        return updateChapterTen(campaign, state.copy(supplierChoice = choice))
    }

    fun chooseChapterTenPriority(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterTenPriorityChoice
    ): ObjectiveDeliveryCampaign {
        val state = chapterTenState(campaign)
        require(campaign.activeChapter == 10 && state.phase == ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN)
        return updateChapterTen(campaign, state.copy(priorityChoice = choice))
    }

    fun chooseChapterTenPricing(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterTenPricingChoice
    ): ObjectiveDeliveryCampaign {
        val state = chapterTenState(campaign)
        require(campaign.activeChapter == 10 && state.phase == ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN)
        return updateChapterTen(campaign, state.copy(pricingChoice = choice))
    }

    fun chooseChapterTenCashPlan(
        campaign: ObjectiveDeliveryCampaign,
        choice: ObjectiveDeliveryChapterTenCashChoice
    ): ObjectiveDeliveryCampaign {
        val state = chapterTenState(campaign)
        require(campaign.activeChapter == 10 && state.phase == ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN)
        return updateChapterTen(campaign, state.copy(cashChoice = choice))
    }

    fun evaluateChapterTen(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryChapterTenEvaluation {
        require(campaign.activeChapter == 10)
        return evaluateChapterTenState(campaign.companyModel, chapterTenState(campaign))
    }

    fun evaluateChapterTenState(
        companyModel: ObjectiveDeliveryCompanyModel,
        state: ObjectiveDeliveryChapterTenState
    ): ObjectiveDeliveryChapterTenEvaluation {
        val allOrders = companyModel.chapterTenProfile().orders.size == 3
        val supplier = state.supplierChoice
        val priority = state.priorityChoice == ObjectiveDeliveryChapterTenPriorityChoice.PROTECT_COMMITMENTS
        val pricing = state.pricingChoice == ObjectiveDeliveryChapterTenPricingChoice.REVISE_NEW_QUOTES_ONLY
        val obligationsProtected = state.cashChoice == ObjectiveDeliveryChapterTenCashChoice.PROTECT_OBLIGATIONS
        val projectedCash = 60_000_000 - 24_000_000 - 15_000_000 - 3_000_000 -
            when (supplier) {
                ObjectiveDeliveryChapterTenSupplierChoice.QUALIFIED_ALTERNATIVE -> 2_000_000 + 3_000_000
                ObjectiveDeliveryChapterTenSupplierChoice.WAIT_FOR_USUAL -> 0
                ObjectiveDeliveryChapterTenSupplierChoice.EXPRESS_EVERYTHING -> 5_000_000 + 7_500_000
                null -> 0
            }
        val reserve = 10_000_000
        val supplierOnTime = supplier == ObjectiveDeliveryChapterTenSupplierChoice.QUALIFIED_ALTERNATIVE ||
            supplier == ObjectiveDeliveryChapterTenSupplierChoice.EXPRESS_EVERYTHING
        val enoughCash = projectedCash >= reserve
        val won = allOrders && supplier == ObjectiveDeliveryChapterTenSupplierChoice.QUALIFIED_ALTERNATIVE &&
            priority && pricing && obligationsProtected && enoughCash
        return ObjectiveDeliveryChapterTenEvaluation(
            allOrdersProtected = priority && allOrders,
            supplierOnTime = supplierOnTime,
            pricingRespectsAgreements = pricing,
            obligationsRemainCovered = obligationsProtected && enoughCash,
            projectedCashCents = projectedCash,
            liquidityReserveCents = reserve,
            outcome = if (won) ObjectiveDeliveryChapterTenOutcome.CAMPAIGN_WON
            else ObjectiveDeliveryChapterTenOutcome.CASH_OR_CUSTOMER_RISK
        )
    }

    fun submitChapterTenPlan(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val state = chapterTenState(campaign)
        require(campaign.activeChapter == 10 && state.phase == ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN)
        require(state.supplierChoice != null && state.priorityChoice != null && state.pricingChoice != null && state.cashChoice != null)
        val evaluation = evaluateChapterTen(campaign)
        return updateChapterTen(campaign, state.copy(
            phase = ObjectiveDeliveryChapterTenPhase.RESULT,
            outcome = evaluation.outcome
        ))
    }

    fun replayChapterTen(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        require(campaign.activeChapter >= 10 && campaign.unlockedChapter >= 10)
        val returnChapter = campaign.replaySession?.returnChapter ?: campaign.activeChapter
        return campaign.copy(
            activeChapter = 10,
            chapterTenAttemptNumber = campaign.chapterTenAttemptNumber + 1,
            replaySession = ObjectiveDeliveryReplaySession(
                chapter = 10, returnChapter = returnChapter, chapterTen = initialChapterTen
            )
        )
    }

    fun keepChapterTenReplayResult(campaign: ObjectiveDeliveryCampaign): ObjectiveDeliveryCampaign {
        val replay = requireNotNull(campaign.replaySession?.takeIf { it.chapter == 10 })
        val board = requireNotNull(replay.chapterTen)
        require(board.outcome == ObjectiveDeliveryChapterTenOutcome.CAMPAIGN_WON)
        return campaign.copy(
            chapterTen = board,
            activeChapter = replay.returnChapter,
            replaySession = null
        )
    }

    private fun updateChapterOne(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterOneState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 1) {
            campaign.copy(replaySession = replay.copy(chapterOne = board))
        } else {
            campaign.copy(
                phase = board.phase,
                needQualified = board.needQualified,
                priceChoice = board.priceChoice,
                timelineChoice = board.timelineChoice,
                outcome = board.outcome
            )
        }
    }

    private fun updateChapterTwo(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterTwoState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 2) {
            campaign.copy(replaySession = replay.copy(chapterTwo = board))
        } else {
            campaign.copy(chapterTwo = board)
        }
    }

    private fun updateChapterThree(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterThreeState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 3) {
            campaign.copy(replaySession = replay.copy(chapterThree = board))
        } else {
            campaign.copy(chapterThree = board)
        }
    }

    private fun updateChapterFour(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFourState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 4) {
            campaign.copy(replaySession = replay.copy(chapterFour = board))
        } else campaign.copy(chapterFour = board)
    }

    private fun updateChapterFive(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterFiveState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 5) {
            campaign.copy(replaySession = replay.copy(chapterFive = board))
        } else campaign.copy(chapterFive = board)
    }

    private fun updateChapterSix(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSixState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 6) {
            campaign.copy(replaySession = replay.copy(chapterSix = board))
        } else campaign.copy(chapterSix = board)
    }

    private fun updateChapterSeven(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterSevenState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 7) {
            campaign.copy(replaySession = replay.copy(chapterSeven = board))
        } else campaign.copy(chapterSeven = board)
    }

    private fun updateChapterEight(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterEightState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 8) campaign.copy(replaySession = replay.copy(chapterEight = board))
        else campaign.copy(chapterEight = board)
    }

    private fun updateChapterNine(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterNineState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 9) campaign.copy(replaySession = replay.copy(chapterNine = board))
        else campaign.copy(chapterNine = board)
    }

    private fun updateChapterTen(
        campaign: ObjectiveDeliveryCampaign,
        board: ObjectiveDeliveryChapterTenState
    ): ObjectiveDeliveryCampaign {
        val replay = campaign.replaySession
        return if (replay?.chapter == 10) campaign.copy(replaySession = replay.copy(chapterTen = board))
        else campaign.copy(chapterTen = board)
    }

    fun stampForSave(
        next: ObjectiveDeliveryCampaign,
        previous: ObjectiveDeliveryCampaign?,
        nowEpochMillis: Long
    ): ObjectiveDeliveryCampaign = next.copy(
        revision = maxOf(next.revision, previous?.revision ?: 0L) + 1L,
        savedAtEpochMillis = nowEpochMillis
    )
}

/** Versioned JSON representation shared by local save files and the optional cloud copy. */
object ObjectiveDeliveryCampaignCodec {
    private const val SCHEMA_VERSION = 10
    private const val VERSION_NINE_SCHEMA_VERSION = 9
    private const val VERSION_EIGHT_SCHEMA_VERSION = 8
    private const val VERSION_SEVEN_SCHEMA_VERSION = 7
    private const val VERSION_SIX_SCHEMA_VERSION = 6
    private const val VERSION_FIVE_SCHEMA_VERSION = 5
    private const val VERSION_FOUR_SCHEMA_VERSION = 4
    private const val VERSION_THREE_SCHEMA_VERSION = 3
    private const val PREVIOUS_SCHEMA_VERSION = 2
    private const val LEGACY_SCHEMA_VERSION = 1

    fun encode(campaign: ObjectiveDeliveryCampaign): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("companyModel", campaign.companyModel.key)
        .put("phase", campaign.phase.name)
        .put("unlockedChapter", campaign.unlockedChapter)
        .put("chapterOneWon", campaign.chapterOneWon)
        .put("needQualified", campaign.needQualified)
        .put("priceChoice", campaign.priceChoice?.name ?: JSONObject.NULL)
        .put("timelineChoice", campaign.timelineChoice?.name ?: JSONObject.NULL)
        .put("outcome", campaign.outcome?.name ?: JSONObject.NULL)
        .put("attemptNumber", campaign.attemptNumber)
        .put("activeChapter", campaign.activeChapter)
        .put("chapterTwoAttemptNumber", campaign.chapterTwoAttemptNumber)
        .put("chapterTwo", encodeChapterTwo(campaign.chapterTwo))
        .put("chapterThreeAttemptNumber", campaign.chapterThreeAttemptNumber)
        .put("chapterThree", encodeChapterThree(campaign.chapterThree))
        .put("chapterFourAttemptNumber", campaign.chapterFourAttemptNumber)
        .put("chapterFour", encodeChapterFour(campaign.chapterFour))
        .put("chapterFiveAttemptNumber", campaign.chapterFiveAttemptNumber)
        .put("chapterFive", encodeChapterFive(campaign.chapterFive))
        .put("chapterSixAttemptNumber", campaign.chapterSixAttemptNumber)
        .put("chapterSix", encodeChapterSix(campaign.chapterSix))
        .put("chapterSevenAttemptNumber", campaign.chapterSevenAttemptNumber)
        .put("chapterSeven", encodeChapterSeven(campaign.chapterSeven))
        .put("chapterEightAttemptNumber", campaign.chapterEightAttemptNumber)
        .put("chapterEight", encodeChapterEight(campaign.chapterEight))
        .put("chapterNineAttemptNumber", campaign.chapterNineAttemptNumber)
        .put("chapterNine", encodeChapterNine(campaign.chapterNine))
        .put("chapterTenAttemptNumber", campaign.chapterTenAttemptNumber)
        .put("chapterTen", encodeChapterTen(campaign.chapterTen))
        .put("replaySession", campaign.replaySession?.let(::encodeReplay) ?: JSONObject.NULL)
        .put("revision", campaign.revision)
        .put("savedAtEpochMillis", campaign.savedAtEpochMillis)
        .put("cloudOwnerUid", campaign.cloudOwnerUid ?: JSONObject.NULL)
        .put("cloudHeadSnapshotId", campaign.cloudHeadSnapshotId ?: JSONObject.NULL)
        .put("cloudParentSnapshotIds", JSONArray(campaign.cloudParentSnapshotIds))
        .toString()

    fun decode(payload: String): ObjectiveDeliveryCampaign? = runCatching {
        val json = JSONObject(payload)
        when (json.getInt("schemaVersion")) {
            LEGACY_SCHEMA_VERSION -> decodeVersionOne(json)
            PREVIOUS_SCHEMA_VERSION -> decodeVersionTwo(json)
            VERSION_THREE_SCHEMA_VERSION -> decodeVersionThree(json)?.let { campaign ->
                if (campaign.chapterThree.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH) {
                    campaign.copy(unlockedChapter = maxOf(campaign.unlockedChapter, 4))
                } else campaign
            }
            VERSION_FOUR_SCHEMA_VERSION -> decodeVersionFour(json)
            VERSION_FIVE_SCHEMA_VERSION -> decodeVersionFive(json)
            VERSION_SIX_SCHEMA_VERSION -> decodeVersionSix(json)
            VERSION_SEVEN_SCHEMA_VERSION -> decodeVersionSeven(json)
            VERSION_EIGHT_SCHEMA_VERSION -> decodeVersionEight(json)
            VERSION_NINE_SCHEMA_VERSION -> decodeVersionNine(json)
            SCHEMA_VERSION -> decodeVersionTen(json)
            else -> null
        }
    }.getOrNull()

    private fun decodeVersionTwo(json: JSONObject): ObjectiveDeliveryCampaign {
        require(json.getInt("schemaVersion") == PREVIOUS_SCHEMA_VERSION)
        val allowedFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "activeChapter", "chapterTwoAttemptNumber", "chapterTwo", "replaySession", "revision",
            "savedAtEpochMillis", "cloudOwnerUid", "cloudHeadSnapshotId", "cloudParentSnapshotIds"
        )
        require(json.keys().asSequence().toSet() == allowedFields)
        val model = requireNotNull(ObjectiveDeliveryCompanyModel.fromKey(json.getString("companyModel")))
        val phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryPhase>(json.getString("phase")))
        val priceName = json.optNullableString("priceChoice")
        val timelineName = json.optNullableString("timelineChoice")
        val outcomeName = json.optNullableString("outcome")
        val price = enumValueOrNull<ObjectiveDeliveryPriceChoice>(priceName)
        val timeline = enumValueOrNull<ObjectiveDeliveryTimelineChoice>(timelineName)
        val outcome = enumValueOrNull<ObjectiveDeliveryOutcome>(outcomeName)
        require(priceName == null || price != null)
        require(timelineName == null || timeline != null)
        require(outcomeName == null || outcome != null)
        val declaredUnlocked = json.getInt("unlockedChapter")
        val attempts = json.getInt("attemptNumber")
        val activeChapter = json.getInt("activeChapter")
        val chapterTwoAttempts = json.getInt("chapterTwoAttemptNumber")
        val revision = json.getLong("revision")
        val savedAt = json.getLong("savedAtEpochMillis")
        val parents = decodeParents(json)
        require(declaredUnlocked in 1..2)
        require(attempts >= 1 && chapterTwoAttempts >= 1 && revision >= 0 && savedAt >= 0)
        val chapterOneWon = json.getBoolean("chapterOneWon")
        require(chapterOneWon == (declaredUnlocked >= 2))
        require(outcome != ObjectiveDeliveryOutcome.ORDER_ACCEPTED || chapterOneWon)
        validateChapterOne(ObjectiveDeliveryChapterOneState(phase, json.getBoolean("needQualified"), price, timeline, outcome))
        val chapterTwo = decodeChapterTwo(json.getJSONObject("chapterTwo"))
        val unlocked = if (chapterTwo.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED) {
            maxOf(declaredUnlocked, 3)
        } else declaredUnlocked
        val replaySession = if (json.isNull("replaySession")) null else {
            decodeReplayVersionTwo(json.getJSONObject("replaySession"))
        }
        require(activeChapter in 1..unlocked)
        require(activeChapter != 2 || chapterOneWon)
        if (chapterTwo != ObjectiveDeliveryChapterTwoState()) require(chapterOneWon)
        if (replaySession != null) {
            require(replaySession.chapter == activeChapter)
            require(replaySession.returnChapter in 1..unlocked)
            require((replaySession.chapter == 1) == (replaySession.chapterOne != null))
            require((replaySession.chapter == 2) == (replaySession.chapterTwo != null))
            replaySession.chapterOne?.let(::validateChapterOne)
            replaySession.chapterTwo?.let(::validateChapterTwo)
        }
        return ObjectiveDeliveryCampaign(
            companyModel = model,
            phase = phase,
            unlockedChapter = unlocked,
            chapterOneWon = chapterOneWon,
            needQualified = json.getBoolean("needQualified"),
            priceChoice = price,
            timelineChoice = timeline,
            outcome = outcome,
            attemptNumber = attempts,
            activeChapter = activeChapter,
            chapterTwo = chapterTwo,
            chapterTwoAttemptNumber = chapterTwoAttempts,
            replaySession = replaySession,
            revision = revision,
            savedAtEpochMillis = savedAt,
            cloudOwnerUid = json.strictNullableString("cloudOwnerUid", maxLength = 128),
            cloudHeadSnapshotId = json.strictNullableString("cloudHeadSnapshotId", maxLength = 128),
            cloudParentSnapshotIds = parents
        )
    }

    /** Adds the handoff board while preserving version-one and version-two campaign saves. */
    private fun decodeVersionThree(json: JSONObject): ObjectiveDeliveryCampaign {
        val allowedFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "activeChapter", "chapterTwoAttemptNumber", "chapterTwo", "chapterThreeAttemptNumber",
            "chapterThree", "replaySession", "revision", "savedAtEpochMillis", "cloudOwnerUid",
            "cloudHeadSnapshotId", "cloudParentSnapshotIds"
        )
        require(json.keys().asSequence().toSet() == allowedFields)
        require(json.getInt("schemaVersion") == VERSION_THREE_SCHEMA_VERSION)
        val declaredUnlocked = json.getInt("unlockedChapter")
        val activeChapter = json.getInt("activeChapter")
        val chapterThreeAttempts = json.getInt("chapterThreeAttemptNumber")
        require(declaredUnlocked in 1..3 && activeChapter in 1..declaredUnlocked)
        require(chapterThreeAttempts >= 1)
        val chapterThree = decodeChapterThree(json.getJSONObject("chapterThree"))
        val replaySession = if (json.isNull("replaySession")) null else {
            decodeReplayVersionThree(json.getJSONObject("replaySession"))
        }

        val legacyPayload = JSONObject(json.toString()).apply {
            put("schemaVersion", PREVIOUS_SCHEMA_VERSION)
            put("unlockedChapter", minOf(declaredUnlocked, 2))
            put("activeChapter", minOf(activeChapter, 2))
            remove("chapterThreeAttemptNumber")
            remove("chapterThree")
            put("replaySession", JSONObject.NULL)
        }
        val base = requireNotNull(decodeVersionTwo(legacyPayload))
        require(base.unlockedChapter == declaredUnlocked)
        require(base.activeChapter == minOf(activeChapter, 2))
        require(base.chapterTwo.outcome != ObjectiveDeliveryOutcome.ORDER_ACCEPTED || declaredUnlocked >= 3)
        if (declaredUnlocked >= 3) {
            require(base.chapterTwo.outcome == ObjectiveDeliveryOutcome.ORDER_ACCEPTED)
        }
        if (chapterThree != ObjectiveDeliveryChapterThreeState()) require(declaredUnlocked >= 3)
        if (replaySession != null) {
            require(replaySession.chapter == activeChapter)
            require(replaySession.returnChapter in 1..declaredUnlocked)
            require((replaySession.chapter == 1) == (replaySession.chapterOne != null))
            require((replaySession.chapter == 2) == (replaySession.chapterTwo != null))
            require((replaySession.chapter == 3) == (replaySession.chapterThree != null))
            require(replaySession.chapter < 3 || declaredUnlocked >= 3)
            replaySession.chapterOne?.let(::validateChapterOne)
            replaySession.chapterTwo?.let(::validateChapterTwo)
            replaySession.chapterThree?.let(::validateChapterThree)
        }
        return base.copy(
            activeChapter = activeChapter,
            chapterThree = chapterThree,
            chapterThreeAttemptNumber = chapterThreeAttempts,
            replaySession = replaySession
        )
    }

    /** Adds team planning and the annual review without discarding earlier campaign progress. */
    private fun decodeVersionFour(json: JSONObject): ObjectiveDeliveryCampaign {
        val allowedFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "activeChapter", "chapterTwoAttemptNumber", "chapterTwo", "chapterThreeAttemptNumber",
            "chapterThree", "chapterFourAttemptNumber", "chapterFour", "replaySession", "revision",
            "savedAtEpochMillis", "cloudOwnerUid", "cloudHeadSnapshotId", "cloudParentSnapshotIds"
        )
        require(json.keys().asSequence().toSet() == allowedFields)
        require(json.getInt("schemaVersion") == VERSION_FOUR_SCHEMA_VERSION)
        val declaredUnlocked = json.getInt("unlockedChapter")
        val activeChapter = json.getInt("activeChapter")
        val chapterFourAttempts = json.getInt("chapterFourAttemptNumber")
        require(declaredUnlocked in 1..5 && activeChapter in 1..4 && activeChapter <= declaredUnlocked)
        require(chapterFourAttempts >= 1)
        val chapterFour = decodeChapterFour(json.getJSONObject("chapterFour"))
        val replaySession = if (json.isNull("replaySession")) null else {
            decodeReplayVersionFour(json.getJSONObject("replaySession"))
        }

        val legacyPayload = JSONObject(json.toString()).apply {
            put("schemaVersion", 3)
            put("unlockedChapter", minOf(declaredUnlocked, 3))
            put("activeChapter", minOf(activeChapter, 3))
            remove("chapterFourAttemptNumber")
            remove("chapterFour")
            put("replaySession", JSONObject.NULL)
        }
        val base = requireNotNull(decodeVersionThree(legacyPayload))
        require(base.unlockedChapter == minOf(declaredUnlocked, 3))
        require(base.activeChapter == minOf(activeChapter, 3))
        if (declaredUnlocked >= 4) {
            require(base.chapterThree.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH)
        }
        if (declaredUnlocked >= 5) {
            require(chapterFour.outcome == ObjectiveDeliveryChapterFourOutcome.TEAM_READY)
        }
        if (chapterFour != ObjectiveDeliveryChapterFourState()) require(declaredUnlocked >= 4)
        if (replaySession != null) {
            require(replaySession.chapter == activeChapter)
            require(replaySession.returnChapter in 1..4)
            require((replaySession.chapter == 1) == (replaySession.chapterOne != null))
            require((replaySession.chapter == 2) == (replaySession.chapterTwo != null))
            require((replaySession.chapter == 3) == (replaySession.chapterThree != null))
            require((replaySession.chapter == 4) == (replaySession.chapterFour != null))
            replaySession.chapterOne?.let(::validateChapterOne)
            replaySession.chapterTwo?.let(::validateChapterTwo)
            replaySession.chapterThree?.let(::validateChapterThree)
            replaySession.chapterFour?.let(::validateChapterFour)
        }
        return base.copy(
            unlockedChapter = if (base.chapterThree.outcome == ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH) {
                maxOf(declaredUnlocked, 4)
            } else declaredUnlocked,
            activeChapter = activeChapter,
            chapterFour = chapterFour,
            chapterFourAttemptNumber = chapterFourAttempts,
            replaySession = replaySession
        )
    }

    /** Adds the first procurement board while retaining every earlier campaign save. */
    private fun decodeVersionFive(json: JSONObject): ObjectiveDeliveryCampaign {
        val allowedFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "activeChapter", "chapterTwoAttemptNumber", "chapterTwo", "chapterThreeAttemptNumber",
            "chapterThree", "chapterFourAttemptNumber", "chapterFour", "chapterFiveAttemptNumber",
            "chapterFive", "replaySession", "revision", "savedAtEpochMillis", "cloudOwnerUid",
            "cloudHeadSnapshotId", "cloudParentSnapshotIds"
        )
        require(json.keys().asSequence().toSet() == allowedFields)
        require(json.getInt("schemaVersion") == VERSION_FIVE_SCHEMA_VERSION)
        val declaredUnlocked = json.getInt("unlockedChapter")
        val activeChapter = json.getInt("activeChapter")
        val chapterFiveAttempts = json.getInt("chapterFiveAttemptNumber")
        require(declaredUnlocked in 1..6 && activeChapter in 1..5 && activeChapter <= declaredUnlocked)
        require(chapterFiveAttempts >= 1)
        val chapterFive = decodeChapterFive(json.getJSONObject("chapterFive"))
        val replaySession = if (json.isNull("replaySession")) null else {
            decodeReplayVersionFive(json.getJSONObject("replaySession"))
        }

        val legacyPayload = JSONObject(json.toString()).apply {
            put("schemaVersion", VERSION_FOUR_SCHEMA_VERSION)
            put("unlockedChapter", minOf(declaredUnlocked, 5))
            put("activeChapter", minOf(activeChapter, 4))
            remove("chapterFiveAttemptNumber")
            remove("chapterFive")
            put("replaySession", JSONObject.NULL)
        }
        val base = requireNotNull(decodeVersionFour(legacyPayload))
        val model = base.companyModel
        val replayIsReady = replaySession?.chapterFive?.let {
            validateChapterFive(model, it)
            it.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY
        } ?: false
        validateChapterFive(model, chapterFive)
        if (chapterFive != ObjectiveDeliveryChapterFiveState()) require(declaredUnlocked >= 5)
        if (chapterFive.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY) {
            require(declaredUnlocked >= 6)
        }
        if (declaredUnlocked >= 6) {
            require(chapterFive.outcome == ObjectiveDeliveryChapterFiveOutcome.MATERIALS_READY || replayIsReady)
        }
        if (replaySession != null) {
            require(replaySession.chapter == activeChapter)
            require(replaySession.returnChapter in 1..5)
            require((replaySession.chapter == 1) == (replaySession.chapterOne != null))
            require((replaySession.chapter == 2) == (replaySession.chapterTwo != null))
            require((replaySession.chapter == 3) == (replaySession.chapterThree != null))
            require((replaySession.chapter == 4) == (replaySession.chapterFour != null))
            require((replaySession.chapter == 5) == (replaySession.chapterFive != null))
            replaySession.chapterOne?.let(::validateChapterOne)
            replaySession.chapterTwo?.let(::validateChapterTwo)
            replaySession.chapterThree?.let(::validateChapterThree)
            replaySession.chapterFour?.let(::validateChapterFour)
        }
        return base.copy(
            unlockedChapter = declaredUnlocked,
            activeChapter = activeChapter,
            chapterFive = chapterFive,
            chapterFiveAttemptNumber = chapterFiveAttempts,
            replaySession = replaySession
        )
    }

    /** Adds the production and quality board while retaining version-five campaign progress. */
    private fun decodeVersionSix(json: JSONObject): ObjectiveDeliveryCampaign {
        val allowedFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "activeChapter", "chapterTwoAttemptNumber", "chapterTwo", "chapterThreeAttemptNumber",
            "chapterThree", "chapterFourAttemptNumber", "chapterFour", "chapterFiveAttemptNumber",
            "chapterFive", "chapterSixAttemptNumber", "chapterSix", "replaySession", "revision",
            "savedAtEpochMillis", "cloudOwnerUid", "cloudHeadSnapshotId", "cloudParentSnapshotIds"
        )
        require(json.keys().asSequence().toSet() == allowedFields)
        require(json.getInt("schemaVersion") == VERSION_SIX_SCHEMA_VERSION)
        val declaredUnlocked = json.getInt("unlockedChapter")
        val activeChapter = json.getInt("activeChapter")
        val chapterSixAttempts = json.getInt("chapterSixAttemptNumber")
        require(declaredUnlocked in 1..7 && activeChapter in 1..6 && activeChapter <= declaredUnlocked)
        require(chapterSixAttempts >= 1)
        val chapterSix = decodeChapterSix(json.getJSONObject("chapterSix"))
        val replaySession = if (json.isNull("replaySession")) null else {
            decodeReplayVersionSix(json.getJSONObject("replaySession"))
        }

        val legacyPayload = JSONObject(json.toString()).apply {
            put("schemaVersion", VERSION_FIVE_SCHEMA_VERSION)
            put("unlockedChapter", minOf(declaredUnlocked, 6))
            put("activeChapter", minOf(activeChapter, 5))
            remove("chapterSixAttemptNumber")
            remove("chapterSix")
            put("replaySession", JSONObject.NULL)
        }
        val base = requireNotNull(decodeVersionFive(legacyPayload))
        val replayIsReady = replaySession?.chapterSix?.let(::validateChapterSixAndCheckReady) ?: false
        validateChapterSix(chapterSix)
        if (chapterSix != ObjectiveDeliveryChapterSixState()) require(declaredUnlocked >= 6)
        if (chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
            chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION
        ) require(declaredUnlocked >= 7)
        if (declaredUnlocked >= 7) {
            require(chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
                chapterSix.outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION || replayIsReady)
        }
        if (replaySession != null) {
            require(replaySession.chapter == activeChapter)
            require(replaySession.returnChapter in 1..declaredUnlocked)
            require((replaySession.chapter == 1) == (replaySession.chapterOne != null))
            require((replaySession.chapter == 2) == (replaySession.chapterTwo != null))
            require((replaySession.chapter == 3) == (replaySession.chapterThree != null))
            require((replaySession.chapter == 4) == (replaySession.chapterFour != null))
            require((replaySession.chapter == 5) == (replaySession.chapterFive != null))
            require((replaySession.chapter == 6) == (replaySession.chapterSix != null))
            replaySession.chapterOne?.let(::validateChapterOne)
            replaySession.chapterTwo?.let(::validateChapterTwo)
            replaySession.chapterThree?.let(::validateChapterThree)
            replaySession.chapterFour?.let(::validateChapterFour)
            replaySession.chapterFive?.let { validateChapterFive(base.companyModel, it) }
            replaySession.chapterSix?.let(::validateChapterSix)
        }
        return base.copy(
            unlockedChapter = declaredUnlocked,
            activeChapter = activeChapter,
            chapterSix = chapterSix,
            chapterSixAttemptNumber = chapterSixAttempts,
            replaySession = replaySession
        )
    }

    private fun validateChapterSixAndCheckReady(state: ObjectiveDeliveryChapterSixState): Boolean {
        validateChapterSix(state)
        return state.outcome == ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH ||
            state.outcome == ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION
    }

    /** Adds delivery and customer follow-up while retaining version-six campaign progress. */
    private fun decodeVersionSeven(json: JSONObject): ObjectiveDeliveryCampaign {
        val allowedFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "activeChapter", "chapterTwoAttemptNumber", "chapterTwo", "chapterThreeAttemptNumber",
            "chapterThree", "chapterFourAttemptNumber", "chapterFour", "chapterFiveAttemptNumber",
            "chapterFive", "chapterSixAttemptNumber", "chapterSix", "chapterSevenAttemptNumber",
            "chapterSeven", "replaySession", "revision", "savedAtEpochMillis", "cloudOwnerUid",
            "cloudHeadSnapshotId", "cloudParentSnapshotIds"
        )
        require(json.keys().asSequence().toSet() == allowedFields)
        require(json.getInt("schemaVersion") == VERSION_SEVEN_SCHEMA_VERSION)
        val declaredUnlocked = json.getInt("unlockedChapter")
        val activeChapter = json.getInt("activeChapter")
        val chapterSevenAttempts = json.getInt("chapterSevenAttemptNumber")
        require(declaredUnlocked in 1..8 && activeChapter in 1..7 && activeChapter <= declaredUnlocked)
        require(chapterSevenAttempts >= 1)
        val chapterSeven = decodeChapterSeven(json.getJSONObject("chapterSeven"))
        val replaySession = if (json.isNull("replaySession")) null else {
            decodeReplayVersionSeven(json.getJSONObject("replaySession"))
        }

        val legacyPayload = JSONObject(json.toString()).apply {
            put("schemaVersion", VERSION_SIX_SCHEMA_VERSION)
            put("unlockedChapter", minOf(declaredUnlocked, 7))
            put("activeChapter", minOf(activeChapter, 6))
            remove("chapterSevenAttemptNumber")
            remove("chapterSeven")
            put("replaySession", JSONObject.NULL)
        }
        val base = requireNotNull(decodeVersionSix(legacyPayload))
        val replayIsResolved = replaySession?.chapterSeven?.let(::validateChapterSevenAndCheckResolved) ?: false
        validateChapterSeven(chapterSeven)
        if (chapterSeven != ObjectiveDeliveryChapterSevenState()) require(declaredUnlocked >= 7)
        if (chapterSeven.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED) {
            require(declaredUnlocked >= 8)
        }
        if (declaredUnlocked >= 8) {
            require(chapterSeven.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED || replayIsResolved)
        }
        if (replaySession != null) {
            require(replaySession.chapter == activeChapter)
            require(replaySession.returnChapter in 1..declaredUnlocked)
            require((replaySession.chapter == 1) == (replaySession.chapterOne != null))
            require((replaySession.chapter == 2) == (replaySession.chapterTwo != null))
            require((replaySession.chapter == 3) == (replaySession.chapterThree != null))
            require((replaySession.chapter == 4) == (replaySession.chapterFour != null))
            require((replaySession.chapter == 5) == (replaySession.chapterFive != null))
            require((replaySession.chapter == 6) == (replaySession.chapterSix != null))
            require((replaySession.chapter == 7) == (replaySession.chapterSeven != null))
            replaySession.chapterOne?.let(::validateChapterOne)
            replaySession.chapterTwo?.let(::validateChapterTwo)
            replaySession.chapterThree?.let(::validateChapterThree)
            replaySession.chapterFour?.let(::validateChapterFour)
            replaySession.chapterFive?.let { validateChapterFive(base.companyModel, it) }
            replaySession.chapterSix?.let(::validateChapterSix)
            replaySession.chapterSeven?.let(::validateChapterSeven)
        }
        return base.copy(
            unlockedChapter = declaredUnlocked,
            activeChapter = activeChapter,
            chapterSeven = chapterSeven,
            chapterSevenAttemptNumber = chapterSevenAttempts,
            replaySession = replaySession
        )
    }

    private fun validateChapterSevenAndCheckResolved(state: ObjectiveDeliveryChapterSevenState): Boolean {
        validateChapterSeven(state)
        return state.outcome == ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED
    }

    private fun decodeVersionEight(json: JSONObject): ObjectiveDeliveryCampaign = decodeAdvancedCampaign(json, 8)

    private fun decodeVersionNine(json: JSONObject): ObjectiveDeliveryCampaign = decodeAdvancedCampaign(json, 9)

    private fun decodeVersionTen(json: JSONObject): ObjectiveDeliveryCampaign = decodeAdvancedCampaign(json, 10)

    /** Adds the remaining three boards and migrates the common campaign through its validated v7 shape. */
    private fun decodeAdvancedCampaign(json: JSONObject, maxChapter: Int): ObjectiveDeliveryCampaign {
        val expectedSchema = maxChapter
        val commonFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "activeChapter", "chapterTwoAttemptNumber", "chapterTwo", "chapterThreeAttemptNumber",
            "chapterThree", "chapterFourAttemptNumber", "chapterFour", "chapterFiveAttemptNumber",
            "chapterFive", "chapterSixAttemptNumber", "chapterSix", "chapterSevenAttemptNumber",
            "chapterSeven", "replaySession", "revision", "savedAtEpochMillis", "cloudOwnerUid",
            "cloudHeadSnapshotId", "cloudParentSnapshotIds"
        )
        val advancedFields = buildSet {
            if (maxChapter >= 8) addAll(listOf("chapterEightAttemptNumber", "chapterEight"))
            if (maxChapter >= 9) addAll(listOf("chapterNineAttemptNumber", "chapterNine"))
            if (maxChapter >= 10) addAll(listOf("chapterTenAttemptNumber", "chapterTen"))
        }
        require(json.keys().asSequence().toSet() == commonFields + advancedFields)
        require(json.getInt("schemaVersion") == expectedSchema)
        val declaredUnlocked = json.getInt("unlockedChapter")
        val activeChapter = json.getInt("activeChapter")
        require(declaredUnlocked in 1..(maxChapter + 1).coerceAtMost(10))
        require(activeChapter in 1..maxChapter && activeChapter <= declaredUnlocked)

        val chapterEightAttempts = if (maxChapter >= 8) json.getInt("chapterEightAttemptNumber") else 1
        val chapterNineAttempts = if (maxChapter >= 9) json.getInt("chapterNineAttemptNumber") else 1
        val chapterTenAttempts = if (maxChapter >= 10) json.getInt("chapterTenAttemptNumber") else 1
        require(chapterEightAttempts >= 1 && chapterNineAttempts >= 1 && chapterTenAttempts >= 1)
        val chapterEight = if (maxChapter >= 8) decodeChapterEight(json.getJSONObject("chapterEight"))
        else ObjectiveDeliveryChapterEightState()
        val chapterNine = if (maxChapter >= 9) decodeChapterNine(json.getJSONObject("chapterNine"))
        else ObjectiveDeliveryChapterNineState()
        val chapterTen = if (maxChapter >= 10) decodeChapterTen(json.getJSONObject("chapterTen"))
        else ObjectiveDeliveryChapterTenState()
        val replaySession = if (json.isNull("replaySession")) null else decodeReplayVersion(json.getJSONObject("replaySession"), maxChapter)

        val legacyPayload = JSONObject(json.toString()).apply {
            put("schemaVersion", VERSION_SEVEN_SCHEMA_VERSION)
            put("unlockedChapter", minOf(declaredUnlocked, 8))
            put("activeChapter", minOf(activeChapter, 7))
            remove("chapterEightAttemptNumber")
            remove("chapterEight")
            remove("chapterNineAttemptNumber")
            remove("chapterNine")
            remove("chapterTenAttemptNumber")
            remove("chapterTen")
            put("replaySession", JSONObject.NULL)
        }
        val base = requireNotNull(decodeVersionSeven(legacyPayload))
        val company = base.companyModel
        validateChapterEight(company, chapterEight)
        validateChapterNine(chapterNine)
        validateChapterTen(company, chapterTen)

        val replayEightSuccess = replaySession?.chapterEight?.let { validateChapterEight(company, it); it.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS } ?: false
        val replayNineSuccess = replaySession?.chapterNine?.let { validateChapterNine(it); it.outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION } ?: false
        if (maxChapter >= 8) {
            if (chapterEight != ObjectiveDeliveryChapterEightState()) require(declaredUnlocked >= 8)
            if (chapterEight.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS) require(declaredUnlocked >= 9)
            if (declaredUnlocked >= 9) require(
                chapterEight.outcome == ObjectiveDeliveryChapterEightOutcome.TEAM_WEEK_SUCCESS || replayEightSuccess
            )
        }
        if (maxChapter >= 9) {
            if (chapterNine != ObjectiveDeliveryChapterNineState()) require(declaredUnlocked >= 9)
            if (chapterNine.outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION) require(declaredUnlocked >= 10)
            if (declaredUnlocked >= 10) require(
                chapterNine.outcome == ObjectiveDeliveryChapterNineOutcome.REOPENED_AFTER_VERIFICATION || replayNineSuccess
            )
        }
        if (maxChapter >= 10 && chapterTen.outcome == ObjectiveDeliveryChapterTenOutcome.CAMPAIGN_WON) {
            require(declaredUnlocked == 10)
        }
        if (activeChapter >= 9) require(declaredUnlocked >= 9)
        if (activeChapter >= 10) require(declaredUnlocked >= 10)

        if (replaySession != null) {
            require(replaySession.chapter == activeChapter)
            require(replaySession.returnChapter in 1..declaredUnlocked)
            require((replaySession.chapter == 1) == (replaySession.chapterOne != null))
            require((replaySession.chapter == 2) == (replaySession.chapterTwo != null))
            require((replaySession.chapter == 3) == (replaySession.chapterThree != null))
            require((replaySession.chapter == 4) == (replaySession.chapterFour != null))
            require((replaySession.chapter == 5) == (replaySession.chapterFive != null))
            require((replaySession.chapter == 6) == (replaySession.chapterSix != null))
            require((replaySession.chapter == 7) == (replaySession.chapterSeven != null))
            require((replaySession.chapter == 8) == (replaySession.chapterEight != null))
            require((replaySession.chapter == 9) == (replaySession.chapterNine != null))
            require((replaySession.chapter == 10) == (replaySession.chapterTen != null))
            replaySession.chapterOne?.let(::validateChapterOne)
            replaySession.chapterTwo?.let(::validateChapterTwo)
            replaySession.chapterThree?.let(::validateChapterThree)
            replaySession.chapterFour?.let(::validateChapterFour)
            replaySession.chapterFive?.let { validateChapterFive(company, it) }
            replaySession.chapterSix?.let(::validateChapterSix)
            replaySession.chapterSeven?.let(::validateChapterSeven)
            replaySession.chapterEight?.let { validateChapterEight(company, it) }
            replaySession.chapterNine?.let(::validateChapterNine)
            replaySession.chapterTen?.let { validateChapterTen(company, it) }
        }
        return base.copy(
            unlockedChapter = declaredUnlocked,
            activeChapter = activeChapter,
            chapterEight = chapterEight,
            chapterEightAttemptNumber = chapterEightAttempts,
            chapterNine = chapterNine,
            chapterNineAttemptNumber = chapterNineAttempts,
            chapterTen = chapterTen,
            chapterTenAttemptNumber = chapterTenAttempts,
            replaySession = replaySession
        )
    }

    /** Migrate the first local/cloud save shape without touching its chapter-one progress. */
    private fun decodeVersionOne(json: JSONObject): ObjectiveDeliveryCampaign {
        require(json.getInt("schemaVersion") == LEGACY_SCHEMA_VERSION)
        val allowedFields = setOf(
            "schemaVersion", "companyModel", "phase", "unlockedChapter", "chapterOneWon",
            "needQualified", "priceChoice", "timelineChoice", "outcome", "attemptNumber",
            "revision", "savedAtEpochMillis", "cloudOwnerUid", "cloudHeadSnapshotId",
            "cloudParentSnapshotIds"
        )
        require(json.keys().asSequence().toSet() == allowedFields)
        val model = requireNotNull(ObjectiveDeliveryCompanyModel.fromKey(json.getString("companyModel")))
        val phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryPhase>(json.getString("phase")))
        val priceName = json.optNullableString("priceChoice")
        val timelineName = json.optNullableString("timelineChoice")
        val outcomeName = json.optNullableString("outcome")
        val price = enumValueOrNull<ObjectiveDeliveryPriceChoice>(priceName)
        val timeline = enumValueOrNull<ObjectiveDeliveryTimelineChoice>(timelineName)
        val outcome = enumValueOrNull<ObjectiveDeliveryOutcome>(outcomeName)
        require(priceName == null || price != null)
        require(timelineName == null || timeline != null)
        require(outcomeName == null || outcome != null)
        val unlocked = json.getInt("unlockedChapter")
        val attempts = json.getInt("attemptNumber")
        val revision = json.getLong("revision")
        val savedAt = json.getLong("savedAtEpochMillis")
        val parents = decodeParents(json)
        require(unlocked in 1..10 && attempts >= 1 && revision >= 0 && savedAt >= 0)
        val chapterOneWon = json.getBoolean("chapterOneWon")
        require(chapterOneWon == (unlocked >= 2))
        require(outcome != ObjectiveDeliveryOutcome.ORDER_ACCEPTED || chapterOneWon)
        validateChapterOne(ObjectiveDeliveryChapterOneState(phase, json.getBoolean("needQualified"), price, timeline, outcome))
        return ObjectiveDeliveryCampaign(
            companyModel = model,
            phase = phase,
            unlockedChapter = unlocked,
            chapterOneWon = chapterOneWon,
            needQualified = json.getBoolean("needQualified"),
            priceChoice = price,
            timelineChoice = timeline,
            outcome = outcome,
            attemptNumber = attempts,
            revision = revision,
            savedAtEpochMillis = savedAt,
            cloudOwnerUid = json.strictNullableString("cloudOwnerUid", maxLength = 128),
            cloudHeadSnapshotId = json.strictNullableString("cloudHeadSnapshotId", maxLength = 128),
            cloudParentSnapshotIds = parents
        )
    }

    private fun decodeParents(json: JSONObject): List<String> {
        val parentsJson = json.getJSONArray("cloudParentSnapshotIds")
        val parents = buildList {
            for (index in 0 until parentsJson.length()) {
                val id = parentsJson.get(index) as? String ?: error("Invalid parent snapshot ID")
                require(id.isNotBlank() && id.length <= 128)
                add(id)
            }
        }
        require(parents.size <= 8 && parents.distinct().size == parents.size)
        return parents
    }

    private fun encodeChapterOne(state: ObjectiveDeliveryChapterOneState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("needQualified", state.needQualified)
        .put("priceChoice", state.priceChoice?.name ?: JSONObject.NULL)
        .put("timelineChoice", state.timelineChoice?.name ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterOne(json: JSONObject): ObjectiveDeliveryChapterOneState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "needQualified", "priceChoice", "timelineChoice", "outcome"
        ))
        val phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryPhase>(json.getString("phase")))
        val price = json.optNullableString("priceChoice")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryPriceChoice>(it))
        }
        val timeline = json.optNullableString("timelineChoice")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryTimelineChoice>(it))
        }
        val outcome = json.optNullableString("outcome")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryOutcome>(it))
        }
        return ObjectiveDeliveryChapterOneState(phase, json.getBoolean("needQualified"), price, timeline, outcome)
            .also(::validateChapterOne)
    }

    private fun encodeChapterTwo(state: ObjectiveDeliveryChapterTwoState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("priceChoice", state.priceChoice?.name ?: JSONObject.NULL)
        .put("timelineChoice", state.timelineChoice?.name ?: JSONObject.NULL)
        .put("negotiationChoice", state.negotiationChoice?.name ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterTwo(json: JSONObject): ObjectiveDeliveryChapterTwoState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "priceChoice", "timelineChoice", "negotiationChoice", "outcome"
        ))
        val phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterTwoPhase>(json.getString("phase")))
        val price = json.optNullableString("priceChoice")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterTwoPriceChoice>(it))
        }
        val timeline = json.optNullableString("timelineChoice")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryTimelineChoice>(it))
        }
        val negotiation = json.optNullableString("negotiationChoice")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryNegotiationChoice>(it))
        }
        val outcome = json.optNullableString("outcome")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryOutcome>(it))
        }
        return ObjectiveDeliveryChapterTwoState(phase, price, timeline, negotiation, outcome)
            .also(::validateChapterTwo)
    }

    private fun encodeChapterThree(state: ObjectiveDeliveryChapterThreeState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("signedQuoteAttached", state.signedQuoteAttached)
        .put("technicalSpecificationConfirmed", state.technicalSpecificationConfirmed)
        .put("customerOptionsConfirmed", state.customerOptionsConfirmed)
        .put("deliveryConditionsConfirmed", state.deliveryConditionsConfirmed)
        .put("promisedDateConfirmed", state.promisedDateConfirmed)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterThree(json: JSONObject): ObjectiveDeliveryChapterThreeState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "signedQuoteAttached", "technicalSpecificationConfirmed",
            "customerOptionsConfirmed", "deliveryConditionsConfirmed", "promisedDateConfirmed", "outcome"
        ))
        val phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterThreePhase>(json.getString("phase")))
        val outcome = json.optNullableString("outcome")?.let {
            requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterThreeOutcome>(it))
        }
        return ObjectiveDeliveryChapterThreeState(
            phase = phase,
            signedQuoteAttached = json.getBoolean("signedQuoteAttached"),
            technicalSpecificationConfirmed = json.getBoolean("technicalSpecificationConfirmed"),
            customerOptionsConfirmed = json.getBoolean("customerOptionsConfirmed"),
            deliveryConditionsConfirmed = json.getBoolean("deliveryConditionsConfirmed"),
            promisedDateConfirmed = json.getBoolean("promisedDateConfirmed"),
            outcome = outcome
        ).also(::validateChapterThree)
    }

    private fun encodeChapterFour(state: ObjectiveDeliveryChapterFourState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("customerFileAssignee", state.customerFileAssignee?.name ?: JSONObject.NULL)
        .put("productionAssignee", state.productionAssignee?.name ?: JSONObject.NULL)
        .put("dispatchAssignee", state.dispatchAssignee?.name ?: JSONObject.NULL)
        .put("leaveChoice", state.leaveChoice?.name ?: JSONObject.NULL)
        .put("hireDecision", state.hireDecision?.name ?: JSONObject.NULL)
        .put("hireContract", state.hireContract?.name ?: JSONObject.NULL)
        .put("hireClassification", state.hireClassification?.name ?: JSONObject.NULL)
        .put("reviewedEmployee", state.reviewedEmployee?.name ?: JSONObject.NULL)
        .put("reviewFactsDiscussed", state.reviewFactsDiscussed)
        .put("raiseEnvelopePoints", state.raiseEnvelopePoints ?: JSONObject.NULL)
        .put("individualRaisePoints", state.individualRaisePoints ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterFour(json: JSONObject): ObjectiveDeliveryChapterFourState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "customerFileAssignee", "productionAssignee", "dispatchAssignee", "leaveChoice",
            "hireDecision", "hireContract", "hireClassification", "reviewedEmployee", "reviewFactsDiscussed",
            "raiseEnvelopePoints", "individualRaisePoints", "outcome"
        ))
        fun <T : Enum<T>> readEnum(name: String, values: Array<T>): T? {
            val value = json.optNullableString(name) ?: return null
            return values.firstOrNull { it.name == value } ?: error("Valeur invalide: $name")
        }
        fun readPoints(name: String): Int? = if (json.isNull(name)) null else json.getInt(name)
        return ObjectiveDeliveryChapterFourState(
            phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterFourPhase>(json.getString("phase"))),
            customerFileAssignee = readEnum("customerFileAssignee", ObjectiveDeliveryTeamMember.values()),
            productionAssignee = readEnum("productionAssignee", ObjectiveDeliveryTeamMember.values()),
            dispatchAssignee = readEnum("dispatchAssignee", ObjectiveDeliveryTeamMember.values()),
            leaveChoice = readEnum("leaveChoice", ObjectiveDeliveryChapterFourLeaveChoice.values()),
            hireDecision = readEnum("hireDecision", ObjectiveDeliveryChapterFourHireDecision.values()),
            hireContract = readEnum("hireContract", ObjectiveDeliveryChapterFourContract.values()),
            hireClassification = readEnum("hireClassification", ObjectiveDeliveryChapterFourClassification.values()),
            reviewedEmployee = readEnum("reviewedEmployee", ObjectiveDeliveryTeamMember.values()),
            reviewFactsDiscussed = json.getBoolean("reviewFactsDiscussed"),
            raiseEnvelopePoints = readPoints("raiseEnvelopePoints"),
            individualRaisePoints = readPoints("individualRaisePoints"),
            outcome = readEnum("outcome", ObjectiveDeliveryChapterFourOutcome.values())
        ).also(::validateChapterFour)
    }

    private fun encodeChapterFive(state: ObjectiveDeliveryChapterFiveState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("supplier", state.supplier?.name ?: JSONObject.NULL)
        .put("quantityChoice", state.quantityChoice?.name ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterFive(json: JSONObject): ObjectiveDeliveryChapterFiveState {
        require(json.keys().asSequence().toSet() == setOf("phase", "supplier", "quantityChoice", "outcome"))
        fun <T : Enum<T>> readEnum(name: String, values: Array<T>): T? {
            val value = json.optNullableString(name) ?: return null
            return values.firstOrNull { it.name == value } ?: error("Valeur invalide: $name")
        }
        return ObjectiveDeliveryChapterFiveState(
            phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterFivePhase>(json.getString("phase"))),
            supplier = readEnum("supplier", ObjectiveDeliveryChapterFiveSupplier.values()),
            quantityChoice = readEnum("quantityChoice", ObjectiveDeliveryChapterFiveQuantityChoice.values()),
            outcome = readEnum("outcome", ObjectiveDeliveryChapterFiveOutcome.values())
        )
    }

    private fun encodeChapterSix(state: ObjectiveDeliveryChapterSixState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("productionPlan", state.productionPlan?.name ?: JSONObject.NULL)
        .put("inspectionChoice", state.inspectionChoice?.name ?: JSONObject.NULL)
        .put("correctionChoice", state.correctionChoice?.name ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterSix(json: JSONObject): ObjectiveDeliveryChapterSixState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "productionPlan", "inspectionChoice", "correctionChoice", "outcome"
        ))
        fun <T : Enum<T>> readEnum(name: String, values: Array<T>): T? {
            val value = json.optNullableString(name) ?: return null
            return values.firstOrNull { it.name == value } ?: error("Valeur invalide: $name")
        }
        return ObjectiveDeliveryChapterSixState(
            phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterSixPhase>(json.getString("phase"))),
            productionPlan = readEnum("productionPlan", ObjectiveDeliveryChapterSixProductionPlan.values()),
            inspectionChoice = readEnum("inspectionChoice", ObjectiveDeliveryChapterSixInspectionChoice.values()),
            correctionChoice = readEnum("correctionChoice", ObjectiveDeliveryChapterSixCorrectionChoice.values()),
            outcome = readEnum("outcome", ObjectiveDeliveryChapterSixOutcome.values())
        ).also(::validateChapterSix)
    }

    private fun encodeChapterSeven(state: ObjectiveDeliveryChapterSevenState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("deliveryChoice", state.deliveryChoice?.name ?: JSONObject.NULL)
        .put("photoShared", state.photoShared)
        .put("itemsChecked", state.itemsChecked)
        .put("quantitiesChecked", state.quantitiesChecked)
        .put("optionsChecked", state.optionsChecked)
        .put("qualityChecked", state.qualityChecked)
        .put("deliveryDocumentsChecked", state.deliveryDocumentsChecked)
        .put("claimAction", state.claimAction?.name ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterSeven(json: JSONObject): ObjectiveDeliveryChapterSevenState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "deliveryChoice", "photoShared", "itemsChecked", "quantitiesChecked",
            "optionsChecked", "qualityChecked", "deliveryDocumentsChecked", "claimAction", "outcome"
        ))
        fun <T : Enum<T>> readEnum(name: String, values: Array<T>): T? {
            val value = json.optNullableString(name) ?: return null
            return values.firstOrNull { it.name == value } ?: error("Valeur invalide: $name")
        }
        return ObjectiveDeliveryChapterSevenState(
            phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterSevenPhase>(json.getString("phase"))),
            deliveryChoice = readEnum("deliveryChoice", ObjectiveDeliveryChapterSevenDeliveryChoice.values()),
            photoShared = json.getBoolean("photoShared"),
            itemsChecked = json.getBoolean("itemsChecked"),
            quantitiesChecked = json.getBoolean("quantitiesChecked"),
            optionsChecked = json.getBoolean("optionsChecked"),
            qualityChecked = json.getBoolean("qualityChecked"),
            deliveryDocumentsChecked = json.getBoolean("deliveryDocumentsChecked"),
            claimAction = readEnum("claimAction", ObjectiveDeliveryChapterSevenClaimAction.values()),
            outcome = readEnum("outcome", ObjectiveDeliveryChapterSevenOutcome.values())
        ).also(::validateChapterSeven)
    }

    private fun encodeChapterEight(state: ObjectiveDeliveryChapterEightState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("staffing", state.staffing?.name ?: JSONObject.NULL)
        .put("bonusCriteria", state.bonusCriteria?.name ?: JSONObject.NULL)
        .put("overtimeChoice", state.overtimeChoice?.name ?: JSONObject.NULL)
        .put("absenceResponse", state.absenceResponse?.name ?: JSONObject.NULL)
        .put("payrollResponse", state.payrollResponse?.name ?: JSONObject.NULL)
        .put("conflictResponse", state.conflictResponse?.name ?: JSONObject.NULL)
        .put("voluntaryEventParticipants", state.voluntaryEventParticipants)
        .put("raiseEnvelopePercent", state.raiseEnvelopePercent ?: JSONObject.NULL)
        .put("individualRaisePercent", state.individualRaisePercent ?: JSONObject.NULL)
        .put("equalProfitShareConfirmed", state.equalProfitShareConfirmed)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterEight(json: JSONObject): ObjectiveDeliveryChapterEightState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "staffing", "bonusCriteria", "overtimeChoice", "absenceResponse", "payrollResponse",
            "conflictResponse", "voluntaryEventParticipants", "raiseEnvelopePercent", "individualRaisePercent",
            "equalProfitShareConfirmed", "outcome"
        ))
        return ObjectiveDeliveryChapterEightState(
            phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterEightPhase>(json.getString("phase"))),
            staffing = json.readEnumOrNull<ObjectiveDeliveryChapterEightStaffing>("staffing"),
            bonusCriteria = json.readEnumOrNull<ObjectiveDeliveryChapterEightBonusCriteria>("bonusCriteria"),
            overtimeChoice = json.readEnumOrNull<ObjectiveDeliveryChapterEightOvertimeChoice>("overtimeChoice"),
            absenceResponse = json.readEnumOrNull<ObjectiveDeliveryChapterEightAbsenceResponse>("absenceResponse"),
            payrollResponse = json.readEnumOrNull<ObjectiveDeliveryChapterEightPayrollResponse>("payrollResponse"),
            conflictResponse = json.readEnumOrNull<ObjectiveDeliveryChapterEightConflictResponse>("conflictResponse"),
            voluntaryEventParticipants = json.getInt("voluntaryEventParticipants"),
            raiseEnvelopePercent = json.optNullableInt("raiseEnvelopePercent"),
            individualRaisePercent = json.optNullableInt("individualRaisePercent"),
            equalProfitShareConfirmed = json.getBoolean("equalProfitShareConfirmed"),
            outcome = json.readEnumOrNull<ObjectiveDeliveryChapterEightOutcome>("outcome")
        )
    }

    private fun encodeChapterNine(state: ObjectiveDeliveryChapterNineState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("inspectionResponse", state.inspectionResponse?.name ?: JSONObject.NULL)
        .put("safetyResponse", state.safetyResponse?.name ?: JSONObject.NULL)
        .put("integrityResponse", state.integrityResponse?.name ?: JSONObject.NULL)
        .put("correctionChoice", state.correctionChoice?.name ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterNine(json: JSONObject): ObjectiveDeliveryChapterNineState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "inspectionResponse", "safetyResponse", "integrityResponse", "correctionChoice", "outcome"
        ))
        return ObjectiveDeliveryChapterNineState(
            phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterNinePhase>(json.getString("phase"))),
            inspectionResponse = json.readEnumOrNull<ObjectiveDeliveryChapterNineInspectionResponse>("inspectionResponse"),
            safetyResponse = json.readEnumOrNull<ObjectiveDeliveryChapterNineSafetyResponse>("safetyResponse"),
            integrityResponse = json.readEnumOrNull<ObjectiveDeliveryChapterNineIntegrityResponse>("integrityResponse"),
            correctionChoice = json.readEnumOrNull<ObjectiveDeliveryChapterNineCorrectionChoice>("correctionChoice"),
            outcome = json.readEnumOrNull<ObjectiveDeliveryChapterNineOutcome>("outcome")
        )
    }

    private fun encodeChapterTen(state: ObjectiveDeliveryChapterTenState): JSONObject = JSONObject()
        .put("phase", state.phase.name)
        .put("supplierChoice", state.supplierChoice?.name ?: JSONObject.NULL)
        .put("priorityChoice", state.priorityChoice?.name ?: JSONObject.NULL)
        .put("pricingChoice", state.pricingChoice?.name ?: JSONObject.NULL)
        .put("cashChoice", state.cashChoice?.name ?: JSONObject.NULL)
        .put("outcome", state.outcome?.name ?: JSONObject.NULL)

    private fun decodeChapterTen(json: JSONObject): ObjectiveDeliveryChapterTenState {
        require(json.keys().asSequence().toSet() == setOf(
            "phase", "supplierChoice", "priorityChoice", "pricingChoice", "cashChoice", "outcome"
        ))
        return ObjectiveDeliveryChapterTenState(
            phase = requireNotNull(enumValueOrNull<ObjectiveDeliveryChapterTenPhase>(json.getString("phase"))),
            supplierChoice = json.readEnumOrNull<ObjectiveDeliveryChapterTenSupplierChoice>("supplierChoice"),
            priorityChoice = json.readEnumOrNull<ObjectiveDeliveryChapterTenPriorityChoice>("priorityChoice"),
            pricingChoice = json.readEnumOrNull<ObjectiveDeliveryChapterTenPricingChoice>("pricingChoice"),
            cashChoice = json.readEnumOrNull<ObjectiveDeliveryChapterTenCashChoice>("cashChoice"),
            outcome = json.readEnumOrNull<ObjectiveDeliveryChapterTenOutcome>("outcome")
        )
    }

    private fun encodeReplay(replay: ObjectiveDeliveryReplaySession): JSONObject = JSONObject()
        .put("chapter", replay.chapter)
        .put("returnChapter", replay.returnChapter)
        .put("chapterOne", replay.chapterOne?.let(::encodeChapterOne) ?: JSONObject.NULL)
        .put("chapterTwo", replay.chapterTwo?.let(::encodeChapterTwo) ?: JSONObject.NULL)
        .put("chapterThree", replay.chapterThree?.let(::encodeChapterThree) ?: JSONObject.NULL)
        .put("chapterFour", replay.chapterFour?.let(::encodeChapterFour) ?: JSONObject.NULL)
        .put("chapterFive", replay.chapterFive?.let(::encodeChapterFive) ?: JSONObject.NULL)
        .put("chapterSix", replay.chapterSix?.let(::encodeChapterSix) ?: JSONObject.NULL)
        .put("chapterSeven", replay.chapterSeven?.let(::encodeChapterSeven) ?: JSONObject.NULL)
        .put("chapterEight", replay.chapterEight?.let(::encodeChapterEight) ?: JSONObject.NULL)
        .put("chapterNine", replay.chapterNine?.let(::encodeChapterNine) ?: JSONObject.NULL)
        .put("chapterTen", replay.chapterTen?.let(::encodeChapterTen) ?: JSONObject.NULL)

    private fun decodeReplayVersionThree(json: JSONObject): ObjectiveDeliveryReplaySession {
        require(json.keys().asSequence().toSet() == setOf(
            "chapter", "returnChapter", "chapterOne", "chapterTwo", "chapterThree"
        ))
        val chapter = json.getInt("chapter")
        val returnChapter = json.getInt("returnChapter")
        require(chapter in 1..3 && returnChapter in 1..3)
        val chapterOne = if (json.isNull("chapterOne")) null else decodeChapterOne(json.getJSONObject("chapterOne"))
        val chapterTwo = if (json.isNull("chapterTwo")) null else decodeChapterTwo(json.getJSONObject("chapterTwo"))
        val chapterThree = if (json.isNull("chapterThree")) null else decodeChapterThree(json.getJSONObject("chapterThree"))
        return ObjectiveDeliveryReplaySession(chapter, returnChapter, chapterOne, chapterTwo, chapterThree)
    }

    private fun decodeReplayVersionFour(json: JSONObject): ObjectiveDeliveryReplaySession {
        require(json.keys().asSequence().toSet() == setOf(
            "chapter", "returnChapter", "chapterOne", "chapterTwo", "chapterThree", "chapterFour"
        ))
        val chapter = json.getInt("chapter")
        val returnChapter = json.getInt("returnChapter")
        require(chapter in 1..4 && returnChapter in 1..4)
        val chapterOne = if (json.isNull("chapterOne")) null else decodeChapterOne(json.getJSONObject("chapterOne"))
        val chapterTwo = if (json.isNull("chapterTwo")) null else decodeChapterTwo(json.getJSONObject("chapterTwo"))
        val chapterThree = if (json.isNull("chapterThree")) null else decodeChapterThree(json.getJSONObject("chapterThree"))
        val chapterFour = if (json.isNull("chapterFour")) null else decodeChapterFour(json.getJSONObject("chapterFour"))
        return ObjectiveDeliveryReplaySession(chapter, returnChapter, chapterOne, chapterTwo, chapterThree, chapterFour)
    }

    private fun decodeReplayVersionFive(json: JSONObject): ObjectiveDeliveryReplaySession {
        require(json.keys().asSequence().toSet() == setOf(
            "chapter", "returnChapter", "chapterOne", "chapterTwo", "chapterThree", "chapterFour", "chapterFive"
        ))
        val chapter = json.getInt("chapter")
        val returnChapter = json.getInt("returnChapter")
        require(chapter in 1..5 && returnChapter in 1..5)
        val chapterOne = if (json.isNull("chapterOne")) null else decodeChapterOne(json.getJSONObject("chapterOne"))
        val chapterTwo = if (json.isNull("chapterTwo")) null else decodeChapterTwo(json.getJSONObject("chapterTwo"))
        val chapterThree = if (json.isNull("chapterThree")) null else decodeChapterThree(json.getJSONObject("chapterThree"))
        val chapterFour = if (json.isNull("chapterFour")) null else decodeChapterFour(json.getJSONObject("chapterFour"))
        val chapterFive = if (json.isNull("chapterFive")) null else decodeChapterFive(json.getJSONObject("chapterFive"))
        return ObjectiveDeliveryReplaySession(
            chapter, returnChapter, chapterOne, chapterTwo, chapterThree, chapterFour, chapterFive
        )
    }

    private fun decodeReplayVersionSix(json: JSONObject): ObjectiveDeliveryReplaySession {
        require(json.keys().asSequence().toSet() == setOf(
            "chapter", "returnChapter", "chapterOne", "chapterTwo", "chapterThree", "chapterFour",
            "chapterFive", "chapterSix"
        ))
        val chapter = json.getInt("chapter")
        val returnChapter = json.getInt("returnChapter")
        require(chapter in 1..6 && returnChapter in 1..7)
        val chapterOne = if (json.isNull("chapterOne")) null else decodeChapterOne(json.getJSONObject("chapterOne"))
        val chapterTwo = if (json.isNull("chapterTwo")) null else decodeChapterTwo(json.getJSONObject("chapterTwo"))
        val chapterThree = if (json.isNull("chapterThree")) null else decodeChapterThree(json.getJSONObject("chapterThree"))
        val chapterFour = if (json.isNull("chapterFour")) null else decodeChapterFour(json.getJSONObject("chapterFour"))
        val chapterFive = if (json.isNull("chapterFive")) null else decodeChapterFive(json.getJSONObject("chapterFive"))
        val chapterSix = if (json.isNull("chapterSix")) null else decodeChapterSix(json.getJSONObject("chapterSix"))
        return ObjectiveDeliveryReplaySession(
            chapter, returnChapter, chapterOne, chapterTwo, chapterThree, chapterFour, chapterFive, chapterSix
        )
    }

    private fun decodeReplayVersionSeven(json: JSONObject): ObjectiveDeliveryReplaySession {
        require(json.keys().asSequence().toSet() == setOf(
            "chapter", "returnChapter", "chapterOne", "chapterTwo", "chapterThree", "chapterFour",
            "chapterFive", "chapterSix", "chapterSeven"
        ))
        val chapter = json.getInt("chapter")
        val returnChapter = json.getInt("returnChapter")
        require(chapter in 1..7 && returnChapter in 1..8)
        val chapterOne = if (json.isNull("chapterOne")) null else decodeChapterOne(json.getJSONObject("chapterOne"))
        val chapterTwo = if (json.isNull("chapterTwo")) null else decodeChapterTwo(json.getJSONObject("chapterTwo"))
        val chapterThree = if (json.isNull("chapterThree")) null else decodeChapterThree(json.getJSONObject("chapterThree"))
        val chapterFour = if (json.isNull("chapterFour")) null else decodeChapterFour(json.getJSONObject("chapterFour"))
        val chapterFive = if (json.isNull("chapterFive")) null else decodeChapterFive(json.getJSONObject("chapterFive"))
        val chapterSix = if (json.isNull("chapterSix")) null else decodeChapterSix(json.getJSONObject("chapterSix"))
        val chapterSeven = if (json.isNull("chapterSeven")) null else decodeChapterSeven(json.getJSONObject("chapterSeven"))
        return ObjectiveDeliveryReplaySession(
            chapter, returnChapter, chapterOne, chapterTwo, chapterThree, chapterFour,
            chapterFive, chapterSix, chapterSeven
        )
    }

    private fun decodeReplayVersionEight(json: JSONObject): ObjectiveDeliveryReplaySession = decodeReplayVersion(json, 8)

    private fun decodeReplayVersionNine(json: JSONObject): ObjectiveDeliveryReplaySession = decodeReplayVersion(json, 9)

    private fun decodeReplayVersionTen(json: JSONObject): ObjectiveDeliveryReplaySession = decodeReplayVersion(json, 10)

    private fun decodeReplayVersion(json: JSONObject, maxChapter: Int): ObjectiveDeliveryReplaySession {
        val chapterFields = listOf(
            "chapterOne", "chapterTwo", "chapterThree", "chapterFour", "chapterFive",
            "chapterSix", "chapterSeven", "chapterEight", "chapterNine", "chapterTen"
        ).take(maxChapter)
        val allowedFields = setOf("chapter", "returnChapter") + chapterFields
        require(json.keys().asSequence().toSet() == allowedFields)
        val chapter = json.getInt("chapter")
        val returnChapter = json.getInt("returnChapter")
        val maxReturnChapter = if (maxChapter == 10) 10 else maxChapter + 1
        require(chapter in 1..maxChapter && returnChapter in 1..maxReturnChapter)
        return ObjectiveDeliveryReplaySession(
            chapter = chapter,
            returnChapter = returnChapter,
            chapterOne = if (json.isNull("chapterOne")) null else decodeChapterOne(json.getJSONObject("chapterOne")),
            chapterTwo = if (json.isNull("chapterTwo")) null else decodeChapterTwo(json.getJSONObject("chapterTwo")),
            chapterThree = if (json.isNull("chapterThree")) null else decodeChapterThree(json.getJSONObject("chapterThree")),
            chapterFour = if (json.isNull("chapterFour")) null else decodeChapterFour(json.getJSONObject("chapterFour")),
            chapterFive = if (json.isNull("chapterFive")) null else decodeChapterFive(json.getJSONObject("chapterFive")),
            chapterSix = if (json.isNull("chapterSix")) null else decodeChapterSix(json.getJSONObject("chapterSix")),
            chapterSeven = if (json.isNull("chapterSeven")) null else decodeChapterSeven(json.getJSONObject("chapterSeven")),
            chapterEight = if (maxChapter >= 8 && !json.isNull("chapterEight")) decodeChapterEight(json.getJSONObject("chapterEight")) else null,
            chapterNine = if (maxChapter >= 9 && !json.isNull("chapterNine")) decodeChapterNine(json.getJSONObject("chapterNine")) else null,
            chapterTen = if (maxChapter >= 10 && !json.isNull("chapterTen")) decodeChapterTen(json.getJSONObject("chapterTen")) else null
        )
    }

    private fun decodeReplayVersionTwo(json: JSONObject): ObjectiveDeliveryReplaySession {
        require(json.keys().asSequence().toSet() == setOf("chapter", "returnChapter", "chapterOne", "chapterTwo"))
        val chapter = json.getInt("chapter")
        val returnChapter = json.getInt("returnChapter")
        require(chapter in 1..2 && returnChapter in 1..2)
        val chapterOne = if (json.isNull("chapterOne")) null else decodeChapterOne(json.getJSONObject("chapterOne"))
        val chapterTwo = if (json.isNull("chapterTwo")) null else decodeChapterTwo(json.getJSONObject("chapterTwo"))
        return ObjectiveDeliveryReplaySession(chapter, returnChapter, chapterOne, chapterTwo)
    }

    private fun validateChapterOne(state: ObjectiveDeliveryChapterOneState) {
        when (state.phase) {
            ObjectiveDeliveryPhase.QUALIFICATION -> require(
                state.priceChoice == null && state.timelineChoice == null && state.outcome == null
            )
            ObjectiveDeliveryPhase.OFFER -> require(state.outcome == null)
            ObjectiveDeliveryPhase.RESULT -> require(
                state.priceChoice != null && state.timelineChoice != null && state.outcome != null
            )
        }
    }

    private fun validateChapterTwo(state: ObjectiveDeliveryChapterTwoState) {
        when (state.phase) {
            ObjectiveDeliveryChapterTwoPhase.QUOTE -> require(
                state.negotiationChoice == null && state.outcome == null
            )
            ObjectiveDeliveryChapterTwoPhase.NEGOTIATION -> require(state.outcome == null)
            ObjectiveDeliveryChapterTwoPhase.RESULT -> require(
                state.priceChoice != null && state.timelineChoice != null &&
                    state.negotiationChoice != null && state.outcome != null
            )
        }
        require(state.phase == ObjectiveDeliveryChapterTwoPhase.QUOTE ||
            (state.priceChoice != null && state.timelineChoice != null))
        require(state.phase != ObjectiveDeliveryChapterTwoPhase.RESULT || state.negotiationChoice != null)
    }

    private fun validateChapterThree(state: ObjectiveDeliveryChapterThreeState) {
        when (state.phase) {
            ObjectiveDeliveryChapterThreePhase.REVIEW -> require(state.outcome == null)
            ObjectiveDeliveryChapterThreePhase.RESULT -> {
                val missing = listOf(
                    !state.signedQuoteAttached,
                    !state.technicalSpecificationConfirmed,
                    !state.customerOptionsConfirmed,
                    !state.deliveryConditionsConfirmed,
                    !state.promisedDateConfirmed
                ).count { it }
                val expected = when {
                    missing == 0 -> ObjectiveDeliveryChapterThreeOutcome.READY_TO_LAUNCH
                    missing == 1 -> ObjectiveDeliveryChapterThreeOutcome.NEEDS_CLARIFICATION
                    else -> ObjectiveDeliveryChapterThreeOutcome.LAUNCH_BLOCKED
                }
                require(state.outcome == expected)
            }
        }
    }

    private fun validateChapterFour(state: ObjectiveDeliveryChapterFourState) {
        require(state.raiseEnvelopePoints == null || state.raiseEnvelopePoints in 0..5)
        require(state.individualRaisePoints == null || state.individualRaisePoints in 0..5)
        require(state.reviewedEmployee != ObjectiveDeliveryTeamMember.TEMPORARY_TECHNICIAN)
        if (state.hireDecision != ObjectiveDeliveryChapterFourHireDecision.APPROVE_TEMPORARY_ROLE) {
            require(state.hireContract == null && state.hireClassification == null)
        }
        when (state.phase) {
            ObjectiveDeliveryChapterFourPhase.PLAN -> require(state.outcome == null)
            ObjectiveDeliveryChapterFourPhase.RESULT -> require(
                state.outcome == ObjectiveDeliveryGameRules.evaluateChapterFourState(state).outcome
            )
        }
    }

    private fun validateChapterFive(model: ObjectiveDeliveryCompanyModel, state: ObjectiveDeliveryChapterFiveState) {
        when (state.phase) {
            ObjectiveDeliveryChapterFivePhase.PROCUREMENT -> require(state.outcome == null)
            ObjectiveDeliveryChapterFivePhase.RESULT -> {
                require(state.supplier != null && state.quantityChoice != null)
                require(state.outcome == ObjectiveDeliveryGameRules.evaluateChapterFiveState(model, state).outcome)
            }
        }
    }

    private fun validateChapterSix(state: ObjectiveDeliveryChapterSixState) {
        when (state.phase) {
            ObjectiveDeliveryChapterSixPhase.PLANNING -> require(
                state.inspectionChoice == null && state.correctionChoice == null && state.outcome == null
            )
            ObjectiveDeliveryChapterSixPhase.QUALITY_CONTROL -> require(
                state.productionPlan != null && state.correctionChoice == null && state.outcome == null
            )
            ObjectiveDeliveryChapterSixPhase.CORRECTION -> require(
                state.productionPlan != null &&
                    state.inspectionChoice == ObjectiveDeliveryChapterSixInspectionChoice.FULL_CHECKLIST &&
                    state.correctionChoice == null && state.outcome == null
            )
            ObjectiveDeliveryChapterSixPhase.RESULT -> {
                require(state.productionPlan != null && state.outcome != null)
                if (state.outcome == ObjectiveDeliveryChapterSixOutcome.CONTROL_INCOMPLETE) {
                    require(state.inspectionChoice == ObjectiveDeliveryChapterSixInspectionChoice.QUICK_SAMPLE)
                    require(state.correctionChoice == null)
                } else {
                    require(state.inspectionChoice == ObjectiveDeliveryChapterSixInspectionChoice.FULL_CHECKLIST)
                    require(state.correctionChoice != null)
                    val expected = when (state.correctionChoice) {
                        ObjectiveDeliveryChapterSixCorrectionChoice.REWORK_AND_RECHECK ->
                            ObjectiveDeliveryChapterSixOutcome.READY_FOR_DISPATCH
                        ObjectiveDeliveryChapterSixCorrectionChoice.REQUEST_CUSTOMER_DEVIATION ->
                            ObjectiveDeliveryChapterSixOutcome.READY_WITH_APPROVED_DEVIATION
                        ObjectiveDeliveryChapterSixCorrectionChoice.DISPATCH_WITHOUT_CORRECTION ->
                            ObjectiveDeliveryChapterSixOutcome.QUALITY_HOLD
                        null -> error("Une action qualité est requise")
                    }
                    require(state.outcome == expected)
                }
            }
        }
    }

    private fun validateChapterSeven(state: ObjectiveDeliveryChapterSevenState) {
        when (state.phase) {
            ObjectiveDeliveryChapterSevenPhase.DELIVERY_PLAN -> require(
                state.itemsChecked.not() && state.quantitiesChecked.not() && state.optionsChecked.not() &&
                    state.qualityChecked.not() && state.deliveryDocumentsChecked.not() &&
                    state.claimAction == null && state.outcome == null
            )
            ObjectiveDeliveryChapterSevenPhase.ORDER_CHECK -> require(
                state.deliveryChoice != null && state.claimAction == null && state.outcome == null
            )
            ObjectiveDeliveryChapterSevenPhase.CUSTOMER_CLAIM -> require(
                state.deliveryChoice != null && state.itemsChecked && state.quantitiesChecked &&
                    state.optionsChecked && state.qualityChecked && state.deliveryDocumentsChecked &&
                    state.outcome == null
            )
            ObjectiveDeliveryChapterSevenPhase.RESULT -> {
                require(state.deliveryChoice != null && state.outcome != null)
                when (state.outcome) {
                    ObjectiveDeliveryChapterSevenOutcome.ORDER_CHECK_INCOMPLETE -> {
                        val checked = listOf(
                            state.itemsChecked, state.quantitiesChecked, state.optionsChecked,
                            state.qualityChecked, state.deliveryDocumentsChecked
                        ).count { it }
                        require(checked < 5 && state.claimAction == null)
                    }
                    ObjectiveDeliveryChapterSevenOutcome.CLAIM_RESOLVED -> require(
                        state.itemsChecked && state.quantitiesChecked && state.optionsChecked &&
                            state.qualityChecked && state.deliveryDocumentsChecked &&
                            (state.claimAction == ObjectiveDeliveryChapterSevenClaimAction.INVESTIGATE_AND_FOLLOW_UP ||
                                state.claimAction == ObjectiveDeliveryChapterSevenClaimAction.CORRECT_AND_FOLLOW_UP)
                    )
                    ObjectiveDeliveryChapterSevenOutcome.CLAIM_UNRESOLVED -> require(
                        state.itemsChecked && state.quantitiesChecked && state.optionsChecked &&
                            state.qualityChecked && state.deliveryDocumentsChecked &&
                            state.claimAction == ObjectiveDeliveryChapterSevenClaimAction.DISMISS_WITHOUT_REVIEW
                    )
                }
            }
        }
    }

    private fun validateChapterEight(companyModel: ObjectiveDeliveryCompanyModel, state: ObjectiveDeliveryChapterEightState) {
        require(state.voluntaryEventParticipants in 0..10)
        require(state.raiseEnvelopePercent == null || state.raiseEnvelopePercent in 0..5)
        require(state.individualRaisePercent == null || state.individualRaisePercent in 0..6)
        when (state.phase) {
            ObjectiveDeliveryChapterEightPhase.WEEK_PLAN -> require(
                state.absenceResponse == null && state.payrollResponse == null && state.conflictResponse == null &&
                    state.raiseEnvelopePercent == null && state.individualRaisePercent == null && state.outcome == null
            )
            ObjectiveDeliveryChapterEightPhase.TEAM_EVENTS -> require(
                state.staffing != null && state.bonusCriteria != null && state.overtimeChoice != null &&
                    state.absenceResponse == null && state.payrollResponse == null && state.conflictResponse == null &&
                    state.raiseEnvelopePercent == null && state.individualRaisePercent == null && state.outcome == null
            )
            ObjectiveDeliveryChapterEightPhase.ANNUAL_REVIEW -> require(
                state.staffing != null && state.bonusCriteria != null && state.overtimeChoice != null &&
                    state.absenceResponse != null && state.payrollResponse != null && state.conflictResponse != null &&
                    state.outcome == null
            )
            ObjectiveDeliveryChapterEightPhase.RESULT -> {
                require(
                    state.staffing != null && state.bonusCriteria != null && state.overtimeChoice != null &&
                        state.absenceResponse != null && state.payrollResponse != null && state.conflictResponse != null &&
                        state.raiseEnvelopePercent != null && state.individualRaisePercent != null && state.outcome != null
                )
                require(state.outcome == ObjectiveDeliveryGameRules.evaluateChapterEightState(companyModel, state).outcome)
            }
        }
    }

    private fun validateChapterNine(state: ObjectiveDeliveryChapterNineState) {
        when (state.phase) {
            ObjectiveDeliveryChapterNinePhase.INSPECTION -> require(
                state.safetyResponse == null && state.integrityResponse == null && state.correctionChoice == null && state.outcome == null
            )
            ObjectiveDeliveryChapterNinePhase.SAFETY_ACTION -> require(
                state.inspectionResponse != null && state.safetyResponse == null && state.integrityResponse == null &&
                    state.correctionChoice == null && state.outcome == null
            )
            ObjectiveDeliveryChapterNinePhase.CORRECTION -> require(
                state.inspectionResponse != null && state.safetyResponse != null && state.integrityResponse != null &&
                    state.correctionChoice == null && state.outcome == null
            )
            ObjectiveDeliveryChapterNinePhase.RESULT -> {
                require(
                    state.inspectionResponse != null && state.safetyResponse != null && state.integrityResponse != null &&
                        state.correctionChoice != null && state.outcome != null
                )
                require(state.outcome == ObjectiveDeliveryGameRules.evaluateChapterNineState(state).outcome)
            }
        }
    }

    private fun validateChapterTen(companyModel: ObjectiveDeliveryCompanyModel, state: ObjectiveDeliveryChapterTenState) {
        when (state.phase) {
            ObjectiveDeliveryChapterTenPhase.PRESSURE_PLAN -> require(state.outcome == null)
            ObjectiveDeliveryChapterTenPhase.RESULT -> {
                require(
                    state.supplierChoice != null && state.priorityChoice != null && state.pricingChoice != null &&
                        state.cashChoice != null && state.outcome != null
                )
                require(state.outcome == ObjectiveDeliveryGameRules.evaluateChapterTenState(companyModel, state).outcome)
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private inline fun <reified T : Enum<T>> JSONObject.readEnumOrNull(key: String): T? {
        val value = optNullableString(key)
        val decoded = enumValueOrNull<T>(value)
        require(value == null || decoded != null)
        return decoded
    }

    private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return getString(key).also { require(it.isNotBlank()) }
    }

    private fun JSONObject.strictNullableString(key: String, maxLength: Int): String? {
        if (isNull(key)) return null
        val value = get(key) as? String ?: error("Invalid string field: $key")
        require(value.isNotBlank() && value.length <= maxLength)
        return value
    }
}
