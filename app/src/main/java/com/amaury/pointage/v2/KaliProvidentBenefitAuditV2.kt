package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Audit KALI strict des garanties/prestations conventionnelles de prévoyance.
 *
 * Décès, incapacité et invalidité constituent le noyau de complétude. Chaque famille doit être
 * soit structurée par une règle officielle exacte, soit explicitement exclue par un article KALI
 * applicable au même profil. Les rentes conjoint/éducation, lorsqu'elles sont mentionnées, doivent
 * elles aussi être structurées ou explicitement exclues avant de déclarer le lot complet.
 */
object KaliProvidentBenefitAuditV2 {
    private val EXPRESSIONS = listOf(
        "prévoyance capital décès",
        "garantie décès prévoyance",
        "incapacité temporaire prévoyance",
        "indemnités incapacité prévoyance",
        "invalidité rente prévoyance",
        "rente conjoint prévoyance",
        "rente éducation prévoyance",
        "garanties régime prévoyance",
        "prestations régime prévoyance"
    )

    private val CORE_FAMILIES = setOf(
        ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
        ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT,
        ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION
    )

    data class ExclusionEvidence(
        val family: ConventionProvidentBenefitV2.Family,
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
        val observedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val structuredFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val explicitlyExcludedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val savedRules: Int,
        val completed: Boolean,
        val warnings: List<String>
    )

    internal data class Completion(
        val state: ConventionMatterCoverageV2.State,
        val completed: Boolean,
        val warnings: List<String>
    )

    fun audit(context: Context, companyId: String, referenceDate: LocalDate): Task<Summary> {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Tasks.forResult(emptySummary("", referenceDate, "entreprise introuvable"))
        if (profile.idcc.isBlank()) return Tasks.forResult(emptySummary("", referenceDate, "IDCC manquant"))
        if (profile.classification.isEmpty()) {
            markCoverage(
                context,
                profile,
                referenceDate,
                ConventionMatterCoverageV2.State.INCOMPLETE,
                emptySet(),
                "Classification locale manquante"
            )
            return Tasks.forResult(emptySummary(profile.idcc, referenceDate, "classification conventionnelle exacte requise"))
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
            return Tasks.forResult(emptySummary(profile.idcc, referenceDate, "statut cadre/non-cadre exact requis"))
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
                emptySummary(profile.idcc, referenceDate, "catégorie ANI exacte requise").copy(
                    warnings = (category.warnings +
                        "KALI garanties prévoyance : catégorie ANI exacte requise avant audit.").distinct()
                )
            )
        }

        val seniorityMonths = seniorityMonths(profile, referenceDate)
        if (seniorityMonths == null) {
            markCoverage(
                context,
                profile,
                referenceDate,
                ConventionMatterCoverageV2.State.INCOMPLETE,
                emptySet(),
                "Ancienneté conventionnelle locale non déterminable"
            )
            return Tasks.forResult(emptySummary(profile.idcc, referenceDate, "ancienneté conventionnelle requise"))
        }

        return KaliMatterEvidenceAuditV2.audit(profile.idcc, referenceDate, EXPRESSIONS).continueWith { task ->
            if (!task.isSuccessful) {
                markCoverage(
                    context,
                    profile,
                    referenceDate,
                    ConventionMatterCoverageV2.State.INCOMPLETE,
                    setOf(ConventionMatterCoverageV2.Authority.KALI),
                    "Collecte KALI garanties prévoyance interrompue"
                )
                return@continueWith emptySummary(profile.idcc, referenceDate, "collecte officielle impossible")
            }

            val evidence = task.result
            val diagnostic = OfficialKaliProvidentBenefitParserV2.parse(
                profile = profile,
                protectionCategory = category.category,
                evidence = evidence
            )
            val exclusions = explicitExclusions(profile, evidence)
            val savedIds = mutableSetOf<String>()
            val saveWarnings = mutableListOf<String>()
            diagnostic.rules.forEach { rule ->
                runCatching { V2ConventionProvidentBenefitStore.saveVerified(context, rule) }
                    .onSuccess { savedIds += rule.ruleId }
                    .onFailure { error ->
                        saveWarnings += "KALI garanties : ${rule.ruleId} non enregistrée : ${error.message ?: "stockage impossible"}."
                    }
            }

            val resolution = ConventionProvidentBenefitV2.resolve(
                rules = diagnostic.rules,
                idcc = profile.idcc,
                referenceDate = referenceDate,
                classification = profile.classification,
                professionalStatus = profile.professionalStatus,
                protectionCategory = category.category,
                seniorityMonths = seniorityMonths
            )
            val positiveRulesExist = diagnostic.rules.isNotEmpty()
            val completion = evaluateCompletion(
                technicalCoverageComplete = evidence.technicalCoverageComplete,
                rules = diagnostic.rules,
                savedRuleIds = savedIds,
                observedFamilies = diagnostic.observedFamilies,
                structuredFamilies = diagnostic.structuredFamilies,
                exclusions = exclusions,
                resolutionReliable = !positiveRulesExist || resolution.reliable,
                referenceDate = referenceDate
            )

            markCoverage(
                context = context,
                profile = profile,
                referenceDate = referenceDate,
                state = completion.state,
                authorities = setOf(ConventionMatterCoverageV2.Authority.KALI),
                source = when {
                    !completion.completed -> "Analyse KALI garanties prévoyance incomplète"
                    completion.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE ->
                        "Légifrance KALI — absence des garanties cœur explicitement prouvée et étendue pour ce profil"
                    else ->
                        "Légifrance KALI — garanties prévoyance structurées/exclues explicitement et applicables"
                }
            )

            Summary(
                idcc = profile.idcc,
                referenceDate = referenceDate,
                pagesRead = evidence.pagesRead,
                articlesConsulted = evidence.articlesConsulted,
                observedFamilies = diagnostic.observedFamilies,
                structuredFamilies = diagnostic.structuredFamilies,
                explicitlyExcludedFamilies = exclusions.map { it.family }.toSet(),
                savedRules = savedIds.size,
                completed = completion.completed,
                warnings = buildList {
                    addAll(evidence.warnings)
                    addAll(diagnostic.reasons)
                    addAll(saveWarnings)
                    if (positiveRulesExist) addAll(resolution.warnings)
                    addAll(completion.warnings)
                    when {
                        completion.completed && completion.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE ->
                            add("KALI garanties prévoyance : absence des garanties cœur explicitement confirmée pour ce profil et cette période.")
                        completion.completed ->
                            add("KALI garanties prévoyance : noyau décès/incapacité/invalidité entièrement prouvé ou explicitement exclu pour ce profil.")
                        else ->
                            add("KALI garanties prévoyance : couverture finale incomplète ; aucune famille manquante n'est supposée absente.")
                    }
                    add("KALI garanties prévoyance : une recherche ciblée vide ne vaut jamais preuve d'absence de garantie.")
                }.distinct()
            )
        }
    }

    internal fun evaluateCompletion(
        technicalCoverageComplete: Boolean,
        rules: List<ConventionProvidentBenefitV2.Rule>,
        savedRuleIds: Set<String>,
        observedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        structuredFamilies: Set<ConventionProvidentBenefitV2.Family>,
        exclusions: List<ExclusionEvidence>,
        resolutionReliable: Boolean,
        referenceDate: LocalDate
    ): Completion {
        val excludedFamilies = exclusions.map { it.family }.toSet()
        val contradictions = structuredFamilies intersect excludedFamilies
        val unresolvedObserved = observedFamilies - structuredFamilies - excludedFamilies
        val covered = structuredFamilies + excludedFamilies
        val coreComplete = covered.containsAll(CORE_FAMILIES)
        val allRulesSaved = rules.all { it.ruleId in savedRuleIds }
        val allRulesExtended = rules.all { rule ->
            rule.extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED &&
                rule.extensionEffectiveFrom?.let { !referenceDate.isBefore(it) } == true
        }
        val exclusionsExtended = excludedFamilies.all { family ->
            exclusions.any { it.family == family && it.extendedOn(referenceDate) }
        }
        val positiveResolutionRequired = structuredFamilies.isNotEmpty()
        val resolutionSatisfied = !positiveResolutionRequired || resolutionReliable
        val completed = technicalCoverageComplete &&
            coreComplete &&
            unresolvedObserved.isEmpty() &&
            contradictions.isEmpty() &&
            allRulesSaved &&
            allRulesExtended &&
            exclusionsExtended &&
            resolutionSatisfied

        val state = when {
            !completed -> ConventionMatterCoverageV2.State.INCOMPLETE
            structuredFamilies.isEmpty() -> ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE
            else -> ConventionMatterCoverageV2.State.CONFIRMED_RULES
        }
        return Completion(
            state = state,
            completed = completed,
            warnings = buildList {
                if (!technicalCoverageComplete) add("KALI garanties : couverture technique des recherches incomplète.")
                if (!coreComplete) add("KALI garanties : décès, incapacité et invalidité ne sont pas toutes prouvées ou explicitement exclues.")
                if (unresolvedObserved.isNotEmpty()) add("KALI garanties : ${unresolvedObserved.joinToString()} mentionnée(s) mais non structurée(s).")
                if (contradictions.isNotEmpty()) add("KALI garanties : contradiction présence/exclusion pour ${contradictions.joinToString()}.")
                if (!allRulesSaved) add("KALI garanties : toutes les règles structurées n'ont pas été enregistrées.")
                if (!allRulesExtended) add("KALI garanties : extension officielle active non démontrée pour toutes les garanties structurées.")
                if (!exclusionsExtended) add("KALI garanties : statut VIGUEUR_ETEN + date d'extension active non démontrés pour toutes les exclusions utilisées.")
                if (positiveResolutionRequired && !resolutionReliable) {
                    add("KALI garanties : les règles structurées ne produisent pas un ensemble de droits unique pour le profil.")
                }
            }
        )
    }

    internal fun explicitExclusions(
        profile: ConventionLegalProfileV2,
        evidence: KaliMatterEvidenceAuditV2.Evidence
    ): List<ExclusionEvidence> {
        val ambiguous = evidence.ambiguousArticleTextIds.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val status = profile.professionalStatus ?: return emptyList()
        return buildList {
            evidence.articles.forEach articleLoop@ { article ->
                val articleId = article.articleId.trim().uppercase(Locale.ROOT)
                if (!articleId.matches(kaliArticleIdRegex) || articleId in ambiguous) return@articleLoop
                val officialStatus = article.status.trim().uppercase(Locale.ROOT)
                if (officialStatus !in acceptedArticleStatuses) return@articleLoop
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

                exclusionPatterns.forEach { (family, patterns) ->
                    val exactProfileExclusion = patterns.any { pattern ->
                        pattern.findAll(text).any { match ->
                            if (classified) {
                                OfficialKaliProfileMatcherV2.nearestScopeMatches(
                                    rawText = text,
                                    classification = profile.classification,
                                    professionalStatus = status,
                                    targetOffset = match.range.first,
                                    maxClassificationSpan = 320
                                )
                            } else {
                                OfficialKaliProfileMatcherV2.statusScopeMatches(text, status)
                            }
                        }
                    }
                    if (exactProfileExclusion) {
                        add(
                            ExclusionEvidence(
                                family = family,
                                articleId = articleId,
                                conventionScopeKey = scope,
                                // Une date isolée n'est jamais suffisante : elle n'est conservée
                                // comme preuve d'extension que pour un article officiellement étendu.
                                extensionEffectiveFrom = article.extensionEffectiveFrom.takeIf {
                                    officialStatus == "VIGUEUR_ETEN"
                                }
                            )
                        )
                    }
                }
            }
        }.distinctBy { listOf(it.family.name, it.articleId, it.conventionScopeKey).joinToString("|") }
    }

    private fun seniorityMonths(profile: ConventionLegalProfileV2, referenceDate: LocalDate): Int? {
        val start = profile.conventionSeniorityDate ?: profile.entryDate ?: return null
        if (start.isAfter(referenceDate)) return null
        return ChronoUnit.MONTHS.between(start, referenceDate).toInt().takeIf { it in 0..600 }
    }

    private fun emptySummary(idcc: String, referenceDate: LocalDate, reason: String) = Summary(
        idcc = idcc,
        referenceDate = referenceDate,
        pagesRead = 0,
        articlesConsulted = 0,
        observedFamilies = emptySet(),
        structuredFamilies = emptySet(),
        explicitlyExcludedFamilies = emptySet(),
        savedRules = 0,
        completed = false,
        warnings = listOf("KALI garanties prévoyance : $reason ; aucun droit n'est inventé.")
    )

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
                matter = ConventionMatterCoverageV2.Matter.PROVIDENT_BENEFITS,
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
        "VIGUEUR_DIFF",
        "VIGUEUR_PARTIELLE"
    )
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
    private val kaliTextIdRegex = Regex("^KALITEXT\\d+$")
    private val classificationVocabulary = Regex(
        "\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction|poste)s?\\b"
    )
    private val exclusionPatterns = mapOf(
        ConventionProvidentBenefitV2.Family.DEATH_CAPITAL to listOf(
            Regex("\\b(?:aucune|absence de|sans)\\s+(?:garantie|prestation|couverture|capital)[^.;]{0,70}?deces\\b"),
            Regex("\\bne\\s+(?:couvre|garantit|prevoit|ouvre droit)[^.;]{0,70}?pas[^.;]{0,70}?deces\\b")
        ),
        ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT to listOf(
            Regex("\\b(?:aucune|absence de|sans)\\s+(?:garantie|prestation|couverture|indemnite)[^.;]{0,70}?incapacite\\b"),
            Regex("\\bne\\s+(?:couvre|garantit|prevoit|ouvre droit)[^.;]{0,70}?pas[^.;]{0,70}?incapacite\\b")
        ),
        ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION to listOf(
            Regex("\\b(?:aucune|absence de|sans)\\s+(?:garantie|prestation|couverture|rente)[^.;]{0,70}?invalidite\\b"),
            Regex("\\bne\\s+(?:couvre|garantit|prevoit|ouvre droit)[^.;]{0,70}?pas[^.;]{0,70}?invalidite\\b")
        ),
        ConventionProvidentBenefitV2.Family.SPOUSE_PENSION to listOf(
            Regex("\\b(?:aucune|absence de|sans)\\s+(?:garantie|prestation|rente)[^.;]{0,70}?conjoint\\b")
        ),
        ConventionProvidentBenefitV2.Family.EDUCATION_PENSION to listOf(
            Regex("\\b(?:aucune|absence de|sans)\\s+(?:garantie|prestation|rente)[^.;]{0,70}?education\\b")
        )
    )
}
