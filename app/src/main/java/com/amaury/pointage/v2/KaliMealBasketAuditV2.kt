package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/** Audit KALI fail-closed des paniers / indemnités repas. */
object KaliMealBasketAuditV2 {
    private val EXPRESSIONS = listOf(
        "panier repas",
        "indemnité repas",
        "prime de panier",
        "allocation repas",
        "panier de nuit",
        "repas travail posté",
        "indemnité repas chantier"
    )

    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val pagesRead: Int,
        val articlesConsulted: Int,
        val structuredRules: Int,
        val savedRules: Int,
        val completed: Boolean,
        val warnings: List<String>
    )

    internal data class Completion(
        val state: ConventionMatterCoverageV2.State,
        val completed: Boolean
    )

    fun audit(context: Context, companyId: String, referenceDate: LocalDate): Task<Summary> {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, 0, false, listOf("KALI repas : entreprise introuvable.")))
        if (profile.idcc.isBlank()) {
            return Tasks.forResult(Summary("", referenceDate, 0, 0, 0, 0, false, listOf("KALI repas : IDCC manquant.")))
        }
        if (profile.classification.isEmpty() || profile.professionalStatus == null) {
            markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Profil juridique local incomplet")
            return Tasks.forResult(
                Summary(profile.idcc, referenceDate, 0, 0, 0, 0, false,
                    listOf("KALI repas : classification exacte et statut cadre/non-cadre requis avant audit."))
            )
        }

        val trustPrimed = MealBasketAuditTrustStoreV2.markKali(
            context = context,
            profile = profile,
            referenceDate = referenceDate,
            state = MealBasketAuditTrustStoreV2.State.INCOMPLETE
        )
        if (!trustPrimed) {
            markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "État d'audit KALI repas non persistable")
            return Tasks.forResult(
                Summary(
                    profile.idcc, referenceDate, 0, 0, 0, 0, false,
                    listOf("KALI repas : impossible de verrouiller localement le début de l'audit ; aucun cache précédent n'est revalidé.")
                )
            )
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                MealBasketAuditTrustStoreV2.markKali(
                    context, profile, referenceDate, MealBasketAuditTrustStoreV2.State.INCOMPLETE
                )
                markCoverage(context, profile, referenceDate, ConventionMatterCoverageV2.State.INCOMPLETE, "Collecte KALI repas interrompue")
                return@continueWith Summary(profile.idcc, referenceDate, 0, 0, 0, 0, false,
                    listOf("KALI repas : collecte officielle impossible."))
            }
            val evidence = task.result
            val diagnostic = OfficialKaliMealBasketParserV2.parse(profile, evidence)
            val lineageComplete = articleLineageComplete(evidence)
            val storedBeforeAudit = V2ConventionMealBasketStore.readVerified(context)
            var saved = 0
            val saveWarnings = mutableListOf<String>()

            if (storedBeforeAudit.reliable) {
                diagnostic.rules.forEach { rule ->
                    runCatching { V2ConventionMealBasketStore.saveVerified(context, rule) }
                        .onSuccess { saved++ }
                        .onFailure { error -> saveWarnings += "KALI repas : ${rule.ruleId} non enregistré : ${error.message ?: "stockage impossible"}." }
                }
            } else {
                val repairEligible = evaluateCompletion(
                    technicalCoverageComplete = evidence.technicalCoverageComplete,
                    diagnostic = diagnostic,
                    savedRules = diagnostic.rules.size,
                    referenceDate = referenceDate,
                    articleLineageComplete = lineageComplete
                ).completed && V2ConventionMealBasketStore.acceptsVerifiedPackage(diagnostic.rules)

                if (repairEligible) {
                    val rebuilt = V2ConventionMealBasketStore.replaceVerifiedPackage(context, diagnostic.rules)
                    if (rebuilt) {
                        saved = diagnostic.rules.size
                        saveWarnings += "KALI repas : ancien cache local incohérent remplacé atomiquement par le paquet certifié de cet audit."
                    } else {
                        saveWarnings += "KALI repas : audit certifiable mais reconstruction atomique du cache local impossible."
                    }
                } else {
                    saveWarnings += "KALI repas : cache local incohérent conservé ; l'audit courant n'est pas assez complet pour autoriser sa reconstruction."
                }
            }

            val parsedCompletion = evaluateCompletion(
                technicalCoverageComplete = evidence.technicalCoverageComplete,
                diagnostic = diagnostic,
                savedRules = saved,
                referenceDate = referenceDate,
                articleLineageComplete = lineageComplete
            )
            val trustStored = MealBasketAuditTrustStoreV2.markKali(
                context = context,
                profile = profile,
                referenceDate = referenceDate,
                state = if (parsedCompletion.completed) {
                    MealBasketAuditTrustStoreV2.State.COMPLETE
                } else {
                    MealBasketAuditTrustStoreV2.State.INCOMPLETE
                },
                fingerprints = if (parsedCompletion.completed) {
                    diagnostic.rules.mapTo(linkedSetOf(), MealBasketAuditTrustStoreV2::kaliFingerprint)
                } else emptySet()
            )
            val completion = if (parsedCompletion.completed && !trustStored) {
                Completion(ConventionMatterCoverageV2.State.INCOMPLETE, completed = false)
            } else parsedCompletion

            markCoverage(
                context, profile, referenceDate, completion.state,
                if (completion.completed) "Légifrance KALI — règles repas structurées, étendues et applicables"
                else "Analyse KALI paniers/indemnités repas incomplète"
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                structuredRules = diagnostic.rules.size,
                savedRules = saved,
                completed = completion.completed,
                warnings = buildList {
                    addAll(evidence.warnings)
                    addAll(diagnostic.reasons)
                    addAll(saveWarnings)
                    if (!trustStored) add("KALI repas : paquet de règles non lié au marqueur d'audit local ; couverture automatique bloquée.")
                    if (!evidence.technicalCoverageComplete) add("KALI repas : couverture technique incomplète ; aucune règle n'est déclarée applicable.")
                    if (!lineageComplete) add("KALI repas : filiation KALIARTI → KALITEXT incomplète ou ambiguë pour au moins un article consulté ; paquet non certifiable.")
                    if (diagnostic.observedOccurrences == 0) add("KALI repas : recherche ciblée sans occurrence exploitable ; cela ne constitue jamais une preuve d'absence de droit.")
                    if (diagnostic.unresolvedOccurrences > 0) add("KALI repas : occurrence(s) incomplète(s) détectée(s) ; couverture de la matière bloquée.")
                    if (completion.completed) add("KALI repas : toutes les occurrences observées sont structurées, stockées, liées à cet audit et étendues à la date contrôlée.")
                }.distinct()
            )
        }
    }

    internal fun articleLineageComplete(evidence: KaliMatterEvidenceAuditV2.Evidence): Boolean {
        val ambiguous = evidence.ambiguousArticleTextIds.mapTo(linkedSetOf()) { it.trim().uppercase() }
        val mappings = evidence.articleTextIds.entries.associate { (articleId, textId) ->
            articleId.trim().uppercase() to textId.trim().uppercase()
        }
        return evidence.articles.all { article ->
            val articleId = article.articleId.trim().uppercase()
            articleId !in ambiguous && mappings[articleId]?.matches(Regex("^KALITEXT\\d+$")) == true
        }
    }

    internal fun evaluateCompletion(
        technicalCoverageComplete: Boolean,
        diagnostic: OfficialKaliMealBasketParserV2.Diagnostic,
        savedRules: Int,
        referenceDate: LocalDate,
        articleLineageComplete: Boolean = true
    ): Completion {
        val allRulesApplicable = diagnostic.rules.isNotEmpty() && diagnostic.rules.all { rule ->
            rule.structurallyValid() &&
                rule.extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
                rule.extensionEffectiveFrom?.let { !referenceDate.isBefore(it) } == true
        }
        val completed = technicalCoverageComplete &&
            articleLineageComplete &&
            diagnostic.observedOccurrences > 0 &&
            diagnostic.unresolvedOccurrences == 0 &&
            diagnostic.structuredOccurrences == diagnostic.observedOccurrences &&
            savedRules == diagnostic.rules.size &&
            allRulesApplicable
        return Completion(
            state = if (completed) ConventionMatterCoverageV2.State.CONFIRMED_RULES else ConventionMatterCoverageV2.State.INCOMPLETE,
            completed = completed
        )
    }

    private fun markCoverage(
        context: Context,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        state: ConventionMatterCoverageV2.State,
        source: String
    ) {
        V2ConventionMatterCoverageStore.save(
            context,
            ConventionMatterCoverageV2.Record(
                idcc = profile.idcc,
                matter = ConventionMatterCoverageV2.Matter.MEAL_BASKET,
                effectiveFrom = referenceDate.withDayOfMonth(1),
                effectiveTo = referenceDate.withDayOfMonth(referenceDate.lengthOfMonth()),
                classification = profile.classification,
                professionalStatus = profile.professionalStatus,
                state = state,
                source = source,
                checkedAtMs = System.currentTimeMillis().coerceAtLeast(1L),
                authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
            )
        )
    }
}
