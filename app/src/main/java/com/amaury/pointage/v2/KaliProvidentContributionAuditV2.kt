package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate
import java.util.Locale

/**
 * Audit KALI strict des cotisations conventionnelles de prévoyance.
 *
 * La catégorie ANI doit déjà être vérifiée par le lot KALI + APEC. Cette étape recherche ensuite
 * bénéficiaires, assiette et taux dans KALI et ne persiste qu'un barème assemblé dans un KALITEXT
 * unique. Une recherche vide ou techniquement incomplète ne prouve jamais l'absence de cotisation.
 * CONFIRMED_NO_RULE n'est permis que par une exclusion textuelle explicite, exacte pour le profil,
 * rattachée à un KALITEXT non ambigu et dont l'extension est active à la date contrôlée.
 */
object KaliProvidentContributionAuditV2 {
    private val EXPRESSIONS = listOf(
        "prévoyance cotisation",
        "cotisations régime prévoyance",
        "salaire de référence prévoyance",
        "assiette prévoyance",
        "bénéficiaires prévoyance",
        "part salariale prévoyance",
        "part patronale prévoyance"
    )

    data class ExclusionEvidence(
        val articleId: String,
        val conventionScopeKey: String,
        val extensionEffectiveFrom: LocalDate?
    ) {
        fun extendedOn(date: LocalDate): Boolean =
            extensionEffectiveFrom?.let { !date.isBefore(it) } == true
    }

    data class Summary(
        val idcc: String,
        val referenceDate: LocalDate,
        val pagesRead: Int,
        val articlesConsulted: Int,
        val structured: Boolean,
        val saved: Boolean,
        val completed: Boolean,
        val selectedRuleId: String? = null,
        val warnings: List<String> = emptyList()
    )

    internal data class Completion(
        val state: ConventionMatterCoverageV2.State,
        val completed: Boolean
    )

    fun audit(context: Context, companyId: String, referenceDate: LocalDate): Task<Summary> {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Tasks.forResult(
                Summary("", referenceDate, 0, 0, false, false, false, warnings = listOf("KALI prévoyance cotisations : entreprise introuvable."))
            )
        if (profile.idcc.isBlank()) {
            return Tasks.forResult(
                Summary("", referenceDate, 0, 0, false, false, false, warnings = listOf("KALI prévoyance cotisations : IDCC manquant."))
            )
        }
        if (profile.classification.isEmpty()) {
            markCoverage(
                context,
                profile,
                referenceDate,
                ConventionMatterCoverageV2.State.INCOMPLETE,
                emptySet(),
                "Classification conventionnelle locale manquante"
            )
            return Tasks.forResult(
                Summary(
                    profile.idcc,
                    referenceDate,
                    0,
                    0,
                    false,
                    false,
                    false,
                    warnings = listOf("KALI prévoyance cotisations : classification conventionnelle exacte requise avant audit.")
                )
            )
        }
        if (profile.professionalStatus == null) {
            markCoverage(
                context,
                profile,
                referenceDate,
                ConventionMatterCoverageV2.State.INCOMPLETE,
                emptySet(),
                "Statut cadre/non-cadre local manquant"
            )
            return Tasks.forResult(
                Summary(
                    profile.idcc,
                    referenceDate,
                    0,
                    0,
                    false,
                    false,
                    false,
                    warnings = listOf("KALI prévoyance cotisations : statut cadre/non-cadre exact requis avant audit.")
                )
            )
        }

        val category = VerifiedProtectionCategoryProviderV2.resolve(context, companyId, referenceDate)
        if (!category.reliable || !category.category.confirmed) {
            markCoverage(
                context,
                profile,
                referenceDate,
                ConventionMatterCoverageV2.State.INCOMPLETE,
                emptySet(),
                "Catégorie ANI KALI + APEC non confirmée"
            )
            return Tasks.forResult(
                Summary(
                    idcc = profile.idcc,
                    referenceDate = referenceDate,
                    pagesRead = 0,
                    articlesConsulted = 0,
                    structured = false,
                    saved = false,
                    completed = false,
                    warnings = (category.warnings +
                        "KALI prévoyance cotisations : catégorie ANI exacte requise avant recherche de barème.").distinct()
                )
            )
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                markCoverage(
                    context,
                    profile,
                    referenceDate,
                    ConventionMatterCoverageV2.State.INCOMPLETE,
                    setOf(ConventionMatterCoverageV2.Authority.KALI),
                    "Collecte KALI cotisations prévoyance interrompue"
                )
                return@continueWith Summary(
                    profile.idcc,
                    referenceDate,
                    0,
                    0,
                    false,
                    false,
                    false,
                    warnings = listOf("KALI prévoyance cotisations : collecte officielle impossible.")
                )
            }

            val evidence = task.result
            val diagnostic = OfficialKaliProvidentContributionParserV2.parse(
                profile = profile,
                protectionCategory = category.category,
                evidence = evidence
            )
            val rule = diagnostic.rule
            val exclusion = explicitExclusion(profile, evidence)
            var saved = false
            var saveError: String? = null
            if (rule != null) {
                saved = runCatching {
                    V2ConventionProvidentContributionStore.saveVerified(context, rule)
                    true
                }.getOrElse { error ->
                    saveError = error.message ?: "stockage impossible"
                    false
                }
            }

            val completion = evaluateCompletion(
                technicalCoverageComplete = evidence.technicalCoverageComplete,
                rule = rule,
                saved = saved,
                exclusion = exclusion,
                referenceDate = referenceDate
            )
            markCoverage(
                context = context,
                profile = profile,
                referenceDate = referenceDate,
                state = completion.state,
                authorities = setOf(ConventionMatterCoverageV2.Authority.KALI),
                source = when (completion.state) {
                    ConventionMatterCoverageV2.State.CONFIRMED_RULES ->
                        "Légifrance KALI — cotisations prévoyance structurées, étendues et applicables"
                    ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE ->
                        "Légifrance KALI — absence de cotisation prévoyance explicitement prouvée et étendue"
                    ConventionMatterCoverageV2.State.INCOMPLETE ->
                        "Analyse KALI cotisations prévoyance incomplète"
                }
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                structured = rule != null,
                saved = saved,
                completed = completion.completed,
                selectedRuleId = rule?.ruleId?.takeIf { saved },
                warnings = buildList {
                    addAll(evidence.warnings)
                    addAll(diagnostic.reasons)
                    if (!evidence.technicalCoverageComplete) {
                        add("KALI prévoyance cotisations : couverture technique incomplète ; aucun barème ni absence de barème n'est déclaré applicable.")
                    }
                    when {
                        rule != null && exclusion != null ->
                            add("KALI prévoyance cotisations : contradiction entre barème positif et exclusion explicite ; aucun résultat automatique.")
                        rule == null && exclusion == null ->
                            add("KALI prévoyance cotisations : aucun barème complet ni exclusion explicite non ambiguë ; aucune cotisation n'est inventée.")
                        rule == null && completion.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE ->
                            add("KALI prévoyance cotisations : absence de cotisation conventionnelle explicitement prouvée pour le profil et la période.")
                        rule == null ->
                            add("KALI prévoyance cotisations : exclusion observée mais extension/applicabilité insuffisamment démontrée.")
                        saveError != null ->
                            add("KALI prévoyance cotisations : règle structurée mais stockage impossible : $saveError.")
                        saved && completion.completed ->
                            add("KALI prévoyance cotisations : barème unique, extension datée et catégorie ANI vérifiée ; règle enregistrée.")
                        saved ->
                            add("KALI prévoyance cotisations : règle officielle enregistrée comme preuve, mais applicabilité automatique non démontrée pour cette date/entreprise.")
                    }
                    add("KALI prévoyance cotisations : une recherche ciblée vide ne vaut jamais preuve d'absence de cotisation conventionnelle.")
                }.distinct()
            )
        }
    }

    internal fun evaluateCompletion(
        technicalCoverageComplete: Boolean,
        rule: ConventionProvidentContributionV2.Rule?,
        saved: Boolean,
        exclusion: ExclusionEvidence? = null,
        referenceDate: LocalDate
    ): Completion {
        val contradiction = rule != null && exclusion != null
        val ruleCompleted = saved &&
            rule != null &&
            rule.structurallyValid() &&
            rule.extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
            rule.extensionEffectiveFrom?.let { !referenceDate.isBefore(it) } == true
        val noRuleCompleted = rule == null && exclusion?.extendedOn(referenceDate) == true
        val completed = technicalCoverageComplete && !contradiction && (ruleCompleted || noRuleCompleted)
        val state = when {
            !completed -> ConventionMatterCoverageV2.State.INCOMPLETE
            ruleCompleted -> ConventionMatterCoverageV2.State.CONFIRMED_RULES
            else -> ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE
        }
        return Completion(state = state, completed = completed)
    }

    internal fun explicitExclusion(
        profile: ConventionLegalProfileV2,
        evidence: KaliMatterEvidenceAuditV2.Evidence
    ): ExclusionEvidence? {
        if (profile.classification.isEmpty()) return null
        val status = profile.professionalStatus?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return null
        val ambiguous = evidence.ambiguousArticleTextIds.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val candidates = mutableListOf<ExclusionEvidence>()
        var nonExcludedContributionMention = false

        evidence.articles.forEach articleLoop@ { article ->
            val articleId = article.articleId.trim().uppercase(Locale.ROOT)
            if (!articleId.matches(kaliArticleIdRegex) || articleId in ambiguous) return@articleLoop
            if (article.status.trim().uppercase(Locale.ROOT) !in acceptedArticleStatuses) return@articleLoop
            if (evidence.referenceDate.isBefore(article.effectiveFrom) ||
                article.effectiveTo?.let(evidence.referenceDate::isAfter) == true
            ) return@articleLoop

            val scope = (
                evidence.articleTextIds[articleId]
                    ?: evidence.articleTextIds.entries.firstOrNull { it.key.equals(articleId, ignoreCase = true) }?.value
                )?.trim()?.uppercase(Locale.ROOT) ?: return@articleLoop
            if (!scope.matches(kaliTextIdRegex)) return@articleLoop

            val text = OfficialKaliProfileMatcherV2.normalize(
                listOfNotNull(article.title, article.content).joinToString("\n")
            )
            val classified = classificationVocabulary.containsMatchIn(text)
            val profileMentions = contributionMentionRegex.findAll(text).filter { match ->
                profileMatchesAt(text, classified, profile, status, match.range.first)
            }.toList()
            if (profileMentions.isEmpty()) return@articleLoop

            var articleHasExclusion = false
            profileMentions.forEach { mention ->
                val clause = clauseAround(text, mention.range.first)
                val excluded = exclusionPatterns.any { it.containsMatchIn(clause) }
                if (excluded) articleHasExclusion = true else nonExcludedContributionMention = true
            }
            if (articleHasExclusion) {
                candidates += ExclusionEvidence(
                    articleId = articleId,
                    conventionScopeKey = scope,
                    extensionEffectiveFrom = article.extensionEffectiveFrom
                )
            }
        }

        if (nonExcludedContributionMention) return null
        val distinct = candidates.distinctBy { listOf(it.articleId, it.conventionScopeKey).joinToString("|") }
        if (distinct.isEmpty()) return null
        if (distinct.map { it.conventionScopeKey }.toSet().size != 1) return null
        return distinct.singleOrNull()
    }

    private fun profileMatchesAt(
        text: String,
        classified: Boolean,
        profile: ConventionLegalProfileV2,
        status: String,
        offset: Int
    ): Boolean = if (classified) {
        OfficialKaliProfileMatcherV2.nearestScopeMatches(
            rawText = text,
            classification = profile.classification,
            professionalStatus = status,
            targetOffset = offset,
            maxClassificationSpan = 320
        )
    } else {
        OfficialKaliProfileMatcherV2.statusScopeMatches(text, status)
    }

    private fun clauseAround(text: String, offset: Int): String {
        val start = maxOf(
            text.lastIndexOf('.', offset - 1),
            text.lastIndexOf(';', offset - 1),
            text.lastIndexOf('\n', offset - 1)
        ).let { if (it < 0) 0 else it + 1 }
        val ends = listOf(
            text.indexOf('.', offset),
            text.indexOf(';', offset),
            text.indexOf('\n', offset)
        ).filter { it >= 0 }
        val end = (ends.minOrNull() ?: text.length).coerceAtLeast(start)
        return text.substring(start, end)
    }

    private fun markCoverage(
        context: Context,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        state: ConventionMatterCoverageV2.State,
        authorities: Set<ConventionMatterCoverageV2.Authority>,
        source: String
    ) {
        V2ConventionMatterCoverageStore.save(
            context,
            ConventionMatterCoverageV2.Record(
                idcc = profile.idcc,
                matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
                effectiveFrom = referenceDate.withDayOfMonth(1),
                effectiveTo = referenceDate.withDayOfMonth(referenceDate.lengthOfMonth()),
                classification = profile.classification,
                professionalStatus = profile.professionalStatus,
                state = state,
                source = source,
                checkedAtMs = System.currentTimeMillis(),
                authorities = authorities
            )
        )
    }

    private val acceptedArticleStatuses = setOf(
        "VIGUEUR",
        "VIGUEUR_ETEN",
        "VIGUEUR_NON_ETEN",
        "VIGUEUR_PARTIELLE"
    )
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
    private val kaliTextIdRegex = Regex("^KALITEXT\\d+$")
    private val classificationVocabulary = Regex(
        "\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction|poste)s?\\b"
    )
    private val contributionMentionRegex = Regex("\\b(?:cotisation|cotisations|contribution|contributions)\\b")
    private val exclusionPatterns = listOf(
        Regex("\\b(?:aucune|absence de|sans)\\s+(?:cotisation|cotisations|contribution|contributions)(?:\\s+de)?\\s+prevoyance\\b"),
        Regex("\\b(?:cotisation|cotisations|contribution|contributions)(?:\\s+de)?\\s+prevoyance[^.;]{0,100}?\\b(?:non due|non dues|n'est pas due|ne sont pas dues|n'est pas applicable|ne s'applique pas)\\b")
    )
}
