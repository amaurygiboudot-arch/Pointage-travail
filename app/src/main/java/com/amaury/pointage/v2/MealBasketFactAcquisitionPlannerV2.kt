package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.MealBasketLegalArbitrationBridgeV2

/**
 * Détermine uniquement les faits externes au pointage nécessaires aux règles repas déjà arbitrées.
 * Les heures, pauses et positions temporelles calculables depuis WorkSessionV2 ne deviennent jamais
 * des questions utilisateur.
 */
object MealBasketFactAcquisitionPlannerV2 {
    data class Requirement(
        val key: MealBasketFactJournalV2.Key,
        val recommendedScope: MealBasketFactJournalV2.Scope,
        val subjects: Set<String>,
        val reason: String
    )

    data class Plan(
        val requirements: List<Requirement>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    private data class Spec(
        val deliveryMode: ConventionMealBasketV2.DeliveryMode,
        val eligibilityAnyOf: List<ConventionMealBasketV2.EligibilityGroup>,
        val blockers: Set<ConventionMealBasketV2.Blocker>
    )

    fun plan(arbitration: MealBasketLegalArbitrationBridgeV2.Result): Plan {
        if (!arbitration.reliable) {
            return Plan(
                requirements = emptyList(),
                reliable = false,
                warnings = (arbitration.warnings +
                    "Panier : arbitrage juridique non fiable ; aucune question factuelle n'est générée sur une règle incertaine.").distinct()
            )
        }

        val collected = linkedMapOf<Pair<MealBasketFactJournalV2.Key, MealBasketFactJournalV2.Scope>, MutableSet<String>>()
        val reasons = linkedMapOf<Pair<MealBasketFactJournalV2.Key, MealBasketFactJournalV2.Scope>, String>()
        val warnings = mutableListOf<String>()

        fun add(
            key: MealBasketFactJournalV2.Key,
            scope: MealBasketFactJournalV2.Scope,
            subject: String,
            reason: String
        ) {
            val id = key to scope
            collected.getOrPut(id) { linkedSetOf() } += subject
            reasons.putIfAbsent(id, reason)
        }

        arbitration.selected.forEach { selected ->
            val spec = selected.toSpec()
            if (spec == null) {
                warnings += "Panier ${selected.subject} : règle arbitrée absente/ambiguë ; acquisition factuelle bloquée."
                return Plan(emptyList(), false, warnings.distinct())
            }

            spec.eligibilityAnyOf.flatMap { it.allOf }.forEach { condition ->
                when (condition) {
                    ConventionMealBasketV2.Condition.PostedShiftWorker -> add(
                        MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER,
                        MealBasketFactJournalV2.Scope.COMPANY,
                        selected.subject,
                        "Le texte réserve le droit au travail posté / en équipes ; ce statut ne se déduit pas de l'heure de pointage."
                    )
                    ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal -> add(
                        MealBasketFactJournalV2.Key.CAN_RETURN_HOME_FOR_MEAL,
                        MealBasketFactJournalV2.Scope.SESSION,
                        selected.subject,
                        "Le texte dépend de la possibilité réelle de regagner le domicile pour le repas."
                    )
                    ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace -> add(
                        MealBasketFactJournalV2.Key.WORKS_AWAY_FROM_USUAL_WORKPLACE,
                        MealBasketFactJournalV2.Scope.SESSION,
                        selected.subject,
                        "Le texte dépend d'un travail hors du lieu habituel ; un simple point GPS ne suffit pas à qualifier juridiquement le déplacement."
                    )
                    ConventionMealBasketV2.Condition.MustEatAtWorkplace -> add(
                        MealBasketFactJournalV2.Key.MUST_EAT_AT_WORKPLACE,
                        MealBasketFactJournalV2.Scope.SESSION,
                        selected.subject,
                        "Le texte exige l'obligation de prendre le repas sur le lieu de travail."
                    )
                    is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow -> add(
                        MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW,
                        MealBasketFactJournalV2.Scope.COMPANY,
                        selected.subject,
                        "Le calcul dépend de la plage de nuit retenue par l'employeur, distincte des seules heures réellement pointées."
                    )
                    // Ces conditions sont calculables depuis les sessions/pauses déjà vérifiées.
                    ConventionMealBasketV2.Condition.WorkedDay,
                    ConventionMealBasketV2.Condition.ShiftEnclosesMidnight,
                    ConventionMealBasketV2.Condition.ShiftStartsAtMidnight,
                    is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow,
                    is ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow -> Unit
                }
            }

            spec.blockers.forEach { blocker ->
                when (blocker) {
                    ConventionMealBasketV2.Blocker.COMPANY_CANTEEN -> add(
                        MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE,
                        MealBasketFactJournalV2.Scope.SESSION,
                        selected.subject,
                        "Le texte prévoit un non-cumul avec une cantine/restaurant d'entreprise ; l'accès réel pendant cette session doit être connu."
                    )
                    ConventionMealBasketV2.Blocker.EMPLOYER_PROVIDED_MEAL -> add(
                        MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED,
                        MealBasketFactJournalV2.Scope.SESSION,
                        selected.subject,
                        "Le texte prévoit un non-cumul avec un repas fourni par l'employeur."
                    )
                    ConventionMealBasketV2.Blocker.MEAL_VOUCHER -> add(
                        MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED,
                        MealBasketFactJournalV2.Scope.DAY,
                        selected.subject,
                        "Le texte prévoit un non-cumul avec un titre/ticket-restaurant effectivement attribué ce jour."
                    )
                    ConventionMealBasketV2.Blocker.OTHER_SAME_NATURE_MEAL_BENEFIT -> add(
                        MealBasketFactJournalV2.Key.OTHER_SAME_NATURE_MEAL_BENEFIT,
                        MealBasketFactJournalV2.Scope.DAY,
                        selected.subject,
                        "Le texte prévoit un non-cumul avec un autre avantage repas de même nature attribué ce jour."
                    )
                }
            }

            if (spec.deliveryMode == ConventionMealBasketV2.DeliveryMode.EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED) {
                add(
                    MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED,
                    MealBasketFactJournalV2.Scope.SESSION,
                    selected.subject,
                    "Le texte prévoit un repas employeur ou, à défaut seulement, une indemnité en espèces."
                )
            }
        }

        val requirements = collected.map { (id, subjects) ->
            Requirement(
                key = id.first,
                recommendedScope = id.second,
                subjects = subjects.toSet(),
                reason = reasons.getValue(id)
            )
        }.sortedWith(compareBy<Requirement> { it.recommendedScope.rank }.thenBy { it.key.name })

        return Plan(requirements, true, warnings.distinct())
    }

    /** Ne garde que les faits réellement inconnus/bloqués pour la session considérée. */
    fun missing(
        plan: Plan,
        resolution: MealBasketFactJournalV2.Resolution
    ): List<Requirement> {
        if (!plan.reliable) return emptyList()
        return plan.requirements.filter { requirement ->
            requirement.key in resolution.blockedKeys || !known(requirement.key, resolution.facts)
        }
    }

    private fun MealBasketLegalArbitrationBridgeV2.Selected.toSpec(): Spec? {
        if ((branchRule == null) == (companyRule == null)) return null
        return branchRule?.let { Spec(it.deliveryMode, it.eligibilityAnyOf, it.blockers) }
            ?: companyRule?.let { Spec(it.deliveryMode, it.eligibilityAnyOf, it.blockers) }
    }

    private fun known(
        key: MealBasketFactJournalV2.Key,
        facts: com.amaury.pointage.v2.engine.VerifiedMealBasketPayrollV2.FactDefaults
    ): Boolean = when (key) {
        MealBasketFactJournalV2.Key.POSTED_SHIFT_WORKER -> facts.postedShiftWorker != null
        MealBasketFactJournalV2.Key.CAN_RETURN_HOME_FOR_MEAL -> facts.canReturnHomeForMeal != null
        MealBasketFactJournalV2.Key.WORKS_AWAY_FROM_USUAL_WORKPLACE -> facts.worksAwayFromUsualWorkplace != null
        MealBasketFactJournalV2.Key.MUST_EAT_AT_WORKPLACE -> facts.mustEatAtWorkplace != null
        MealBasketFactJournalV2.Key.COMPANY_CANTEEN_AVAILABLE -> facts.companyCanteenAvailable != null
        MealBasketFactJournalV2.Key.EMPLOYER_MEAL_PROVIDED -> facts.employerMealProvided != null
        MealBasketFactJournalV2.Key.MEAL_VOUCHER_PROVIDED -> facts.mealVoucherProvided != null
        MealBasketFactJournalV2.Key.OTHER_SAME_NATURE_MEAL_BENEFIT -> facts.otherSameNatureMealBenefit != null
        MealBasketFactJournalV2.Key.EMPLOYER_NIGHT_WINDOW -> facts.employerNightWindow != null
    }
}
