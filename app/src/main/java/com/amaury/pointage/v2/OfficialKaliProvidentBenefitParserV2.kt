package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.time.LocalDate
import java.util.Locale

/**
 * Structure uniquement les garanties de prévoyance dont la formule et le périmètre sont
 * explicitement prouvés dans KALI. Une famille simplement mentionnée reste observée mais non
 * structurée ; elle ne peut donc jamais débloquer PROVIDENT_BENEFITS à elle seule.
 */
object OfficialKaliProvidentBenefitParserV2 {
    data class Diagnostic(
        val rules: List<ConventionProvidentBenefitV2.Rule>,
        val observedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val structuredFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val reasons: List<String>
    )

    fun parse(
        profile: ConventionLegalProfileV2,
        protectionCategory: ProtectionCategoryV2.Result,
        evidence: KaliMatterEvidenceAuditV2.Evidence
    ): Diagnostic = parse(
        profile = profile,
        protectionCategory = protectionCategory,
        verifiedIdcc = evidence.idcc,
        auditDate = evidence.referenceDate,
        articles = evidence.articles,
        articleTextIds = evidence.articleTextIds,
        ambiguousArticleTextIds = evidence.ambiguousArticleTextIds
    )

    internal fun parse(
        profile: ConventionLegalProfileV2,
        protectionCategory: ProtectionCategoryV2.Result,
        verifiedIdcc: String,
        auditDate: LocalDate,
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>,
        articleTextIds: Map<String, String>,
        ambiguousArticleTextIds: Set<String> = emptySet()
    ): Diagnostic {
        val profileIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        val kaliIdcc = ConventionMinimumSalaryV2.normalizeIdcc(verifiedIdcc)
        if (profileIdcc.isBlank() || profileIdcc != kaliIdcc) return unresolved("IDCC du profil et de KALI différents")
        if (!protectionCategory.confirmed || protectionCategory.aniCategory !in supportedAniCategories) {
            return unresolved("catégorie ANI exacte non confirmée")
        }
        val status = profile.professionalStatus?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return unresolved("statut cadre/non-cadre exact manquant")
        val classification = profile.classification.normalized()
        if (classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")

        val ambiguous = ambiguousArticleTextIds.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val eligible = articles.filter { article ->
            val id = article.articleId.trim().uppercase(Locale.ROOT)
            id.matches(kaliArticleIdRegex) &&
                id !in ambiguous &&
                !auditDate.isBefore(article.effectiveFrom) &&
                article.effectiveTo?.let(auditDate::isAfter) != true &&
                article.status.trim().uppercase(Locale.ROOT) in acceptedStatuses
        }
        if (eligible.isEmpty()) return unresolved("aucun KALIARTI applicable et non ambigu")

        val observed = linkedSetOf<ConventionProvidentBenefitV2.Family>()
        eligible.forEach { article ->
            observed += observedFamilies(normalizeArticle(article))
        }

        val grouped = eligible.groupBy { article ->
            val id = article.articleId.trim().uppercase(Locale.ROOT)
            articleTextIds[id]
                ?: articleTextIds.entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.value
        }.filterKeys { it?.matches(kaliTextIdRegex) == true }

        val rules = mutableListOf<ConventionProvidentBenefitV2.Rule>()
        val reasons = mutableListOf<String>()
        grouped.forEach { (scopeRaw, scopedArticles) ->
            val scope = scopeRaw ?: return@forEach
            val normalized = scopedArticles.associateWith(::normalizeArticle)
            val profileCompatible = normalized.filterValues { text ->
                clauseMatchesProfile(text, classification, status)
            }
            if (profileCompatible.isEmpty()) return@forEach

            profileCompatible.forEach articleLoop@ { (article, text) ->
                val parsedGuarantees = parseGuarantees(text, article.articleId)
                if (parsedGuarantees.isEmpty()) return@articleLoop
                val seniority = parseSeniorityMonths(text)
                if (seniority == null) {
                    reasons += "KALI garanties $scope ${article.articleId} : ancienneté d'ouverture non prouvée dans le même article que la garantie ; cette garantie n'est pas persistée."
                    return@articleLoop
                }
                val extensionDate = article.extensionEffectiveFrom
                val extensionStatus = if (extensionDate != null) {
                    ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
                } else {
                    ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
                }
                parsedGuarantees.forEach { guarantee ->
                    val rule = ConventionProvidentBenefitV2.Rule(
                        idcc = kaliIdcc,
                        ruleId = "KALI-PROVIDENT-BENEFIT-$scope-${article.articleId}-${guarantee.family.name}-${guarantee.invalidityCategory ?: 0}",
                        effectiveFrom = article.effectiveFrom,
                        effectiveTo = article.effectiveTo,
                        classification = classification,
                        professionalStatus = status,
                        aniCategories = setOf(protectionCategory.aniCategory),
                        minimumSeniorityMonths = seniority,
                        guarantees = listOf(guarantee),
                        source = "Légifrance KALI — $scope — ${article.articleId}",
                        conventionScopeKey = scope,
                        extensionStatus = extensionStatus,
                        extensionEffectiveFrom = extensionDate
                    )
                    if (rule.structurallyValid()) rules += rule
                }
            }
        }

        val distinctRules = rules.distinctBy { it.ruleId }
        val structured = distinctRules.flatMap { it.guarantees }.map { it.family }.toSet()
        return Diagnostic(
            rules = distinctRules,
            observedFamilies = observed,
            structuredFamilies = structured,
            reasons = buildList {
                addAll(reasons)
                observed.filter { it !in structured }.forEach { family ->
                    add("KALI garanties : ${family.name} mentionnée mais formule/périmètre insuffisamment structurés ; aucun droit n'est inventé.")
                }
                if (distinctRules.isEmpty()) add("KALI garanties : aucune prestation complète et non ambiguë n'a été structurée.")
            }.distinct()
        )
    }

    private fun parseGuarantees(
        text: String,
        articleId: String
    ): List<ConventionProvidentBenefitV2.Guarantee> = buildList {
        parseDeath(text, articleId)?.let(::add)
        parseIncapacity(text, articleId)?.let(::add)
        addAll(parseInvalidity(text, articleId))
        parseSpousePension(text, articleId)?.let(::add)
        parseEducationPension(text, articleId)?.let(::add)
    }

    private fun parseDeath(text: String, articleId: String): ConventionProvidentBenefitV2.Guarantee? {
        val window = contextWindow(text, deathRegex) ?: return null
        val formula = parseFormula(window) ?: return null
        return guarantee(
            family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            label = "Capital décès — ${formulaLabel(formula)}",
            formula = formula,
            articleId = articleId
        )
    }

    private fun parseIncapacity(text: String, articleId: String): ConventionProvidentBenefitV2.Guarantee? {
        val window = contextWindow(text, incapacityRegex, before = 100, after = 360) ?: return null
        if (!incomeBenefitRegex.containsMatchIn(window)) return null
        val formula = parseFormula(window) ?: return null
        return guarantee(
            family = ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT,
            label = "Incapacité temporaire — ${formulaLabel(formula)}",
            formula = formula,
            waitingPeriodDays = parseWaitingDays(window),
            socialSecurityTreatment = parseSocialSecurityTreatment(window),
            articleId = articleId
        )
    }

    private fun parseInvalidity(text: String, articleId: String): List<ConventionProvidentBenefitV2.Guarantee> {
        val window = contextWindow(text, invalidityRegex, before = 100, after = 420) ?: return emptyList()
        if (!pensionRegex.containsMatchIn(window)) return emptyList()
        val formula = parseFormula(window) ?: return emptyList()
        val categories = invalidityCategoryRegex.findAll(window).mapNotNull { match ->
            match.groupValues[1].toIntOrNull()
        }.toSet()
        if (categories.size > 1) return emptyList()
        return listOf(
            guarantee(
                family = ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION,
                label = "Invalidité${categories.singleOrNull()?.let { " catégorie $it" }.orEmpty()} — ${formulaLabel(formula)}",
                formula = formula,
                invalidityCategory = categories.singleOrNull(),
                socialSecurityTreatment = parseSocialSecurityTreatment(window),
                articleId = articleId
            )
        )
    }

    private fun parseSpousePension(text: String, articleId: String): ConventionProvidentBenefitV2.Guarantee? {
        val window = contextWindow(text, spousePensionRegex) ?: return null
        val formula = parseFormula(window) ?: return null
        return guarantee(
            family = ConventionProvidentBenefitV2.Family.SPOUSE_PENSION,
            label = "Rente conjoint — ${formulaLabel(formula)}",
            formula = formula,
            articleId = articleId
        )
    }

    private fun parseEducationPension(text: String, articleId: String): ConventionProvidentBenefitV2.Guarantee? {
        val window = contextWindow(text, educationPensionRegex) ?: return null
        val formula = parseFormula(window) ?: return null
        return guarantee(
            family = ConventionProvidentBenefitV2.Family.EDUCATION_PENSION,
            label = "Rente éducation — ${formulaLabel(formula)}",
            formula = formula,
            articleId = articleId
        )
    }

    private fun guarantee(
        family: ConventionProvidentBenefitV2.Family,
        label: String,
        formula: ConventionProvidentBenefitV2.Formula,
        waitingPeriodDays: Int? = null,
        invalidityCategory: Int? = null,
        socialSecurityTreatment: ConventionProvidentBenefitV2.SocialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE,
        articleId: String
    ) = ConventionProvidentBenefitV2.Guarantee(
        family = family,
        label = label,
        formula = formula,
        waitingPeriodDays = waitingPeriodDays,
        invalidityCategory = invalidityCategory,
        socialSecurityTreatment = socialSecurityTreatment,
        evidenceArticleIds = setOf(articleId.trim().uppercase(Locale.ROOT))
    )

    /**
     * Une fenêtre ne devient calculable que si elle contient exactement une formule distincte.
     * Deux taux ou deux assiettes possibles ne sont jamais départagés par ordre d'apparition.
     */
    private fun parseFormula(window: String): ConventionProvidentBenefitV2.Formula? {
        val candidates = buildList {
            annualSalaryPercentRegex.findAll(window).forEach { match ->
                percentFormula(ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY, match.groupValues[1])?.let(::add)
            }
            monthlySalaryPercentRegex.findAll(window).forEach { match ->
                percentFormula(ConventionProvidentBenefitV2.Basis.MONTHLY_REFERENCE_SALARY, match.groupValues[1])?.let(::add)
            }
            pmssMultipleRegex.findAll(window).forEach { match ->
                val value = parseNumber(match.groupValues[1])?.takeIf { it > 0.0 && it <= 100.0 } ?: return@forEach
                ConventionProvidentBenefitV2.Formula(
                    basis = ConventionProvidentBenefitV2.Basis.PMSS,
                    coefficient = value
                ).takeIf { it.structurallyValid() }?.let(::add)
            }
        }.distinctBy(::formulaFingerprint)
        return candidates.singleOrNull()
    }

    private fun formulaFingerprint(value: ConventionProvidentBenefitV2.Formula): String = listOf(
        value.basis.name,
        value.coefficient?.toString().orEmpty(),
        value.fixedAmount?.toString().orEmpty()
    ).joinToString("|")

    private fun percentFormula(
        basis: ConventionProvidentBenefitV2.Basis,
        raw: String
    ): ConventionProvidentBenefitV2.Formula? {
        val value = parseNumber(raw)?.takeIf { it > 0.0 && it <= 10_000.0 } ?: return null
        return ConventionProvidentBenefitV2.Formula(basis = basis, coefficient = value / 100.0)
            .takeIf { it.structurallyValid() }
    }

    private fun parseWaitingDays(window: String): Int? {
        franchiseRegex.find(window)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.takeIf { value -> value in 0..3660 } }
        startDayRegex.find(window)?.groupValues?.get(1)?.toIntOrNull()?.let { day ->
            return (day - 1).takeIf { it in 0..3660 }
        }
        return null
    }

    private fun parseSocialSecurityTreatment(window: String): ConventionProvidentBenefitV2.SocialSecurityTreatment = when {
        deductSsRegex.containsMatchIn(window) -> ConventionProvidentBenefitV2.SocialSecurityTreatment.DEDUCT_SOCIAL_SECURITY
        includedSsRegex.containsMatchIn(window) -> ConventionProvidentBenefitV2.SocialSecurityTreatment.INCLUDED_IN_TARGET_TOTAL
        additionalSsRegex.containsMatchIn(window) -> ConventionProvidentBenefitV2.SocialSecurityTreatment.ADDITIONAL_TO_SOCIAL_SECURITY
        else -> ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE
    }

    private fun parseSeniorityMonths(text: String): Int? {
        if (noSeniorityRegex.containsMatchIn(text)) return 0
        val match = seniorityRegex.find(text) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return when {
            match.groupValues[2].startsWith("an") -> value * 12
            match.groupValues[2].startsWith("mois") -> value
            else -> null
        }.takeIf { it in 0..600 }
    }

    private fun observedFamilies(text: String): Set<ConventionProvidentBenefitV2.Family> = buildSet {
        if (deathRegex.containsMatchIn(text)) add(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL)
        if (incapacityRegex.containsMatchIn(text)) add(ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT)
        if (invalidityRegex.containsMatchIn(text)) add(ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION)
        if (spousePensionRegex.containsMatchIn(text)) add(ConventionProvidentBenefitV2.Family.SPOUSE_PENSION)
        if (educationPensionRegex.containsMatchIn(text)) add(ConventionProvidentBenefitV2.Family.EDUCATION_PENSION)
    }

    private fun clauseMatchesProfile(
        text: String,
        classification: ConventionClassificationV2,
        professionalStatus: String
    ): Boolean {
        if (!classificationVocabularyPresent(text)) return true
        return OfficialKaliProfileMatcherV2.windows(
            rawText = text,
            classification = classification,
            professionalStatus = professionalStatus,
            before = 140,
            after = 360,
            maxClassificationSpan = 320
        ).isNotEmpty()
    }

    private fun classificationVocabularyPresent(text: String): Boolean = classificationVocabulary.containsMatchIn(text)

    private fun normalizeArticle(article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle): String =
        OfficialKaliProfileMatcherV2.normalize(listOfNotNull(article.title, article.content).joinToString("\n"))

    private fun contextWindow(text: String, regex: Regex, before: Int = 80, after: Int = 300): String? {
        val match = regex.find(text) ?: return null
        return text.substring(
            (match.range.first - before).coerceAtLeast(0),
            (match.range.last + 1 + after).coerceAtMost(text.length)
        )
    }

    private fun formulaLabel(value: ConventionProvidentBenefitV2.Formula): String = when (value.basis) {
        ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY -> "${cleanPercent(value.coefficient)} du salaire annuel de référence"
        ConventionProvidentBenefitV2.Basis.MONTHLY_REFERENCE_SALARY -> "${cleanPercent(value.coefficient)} du salaire mensuel de référence"
        ConventionProvidentBenefitV2.Basis.PMSS -> "${value.coefficient} PMSS"
        ConventionProvidentBenefitV2.Basis.FIXED_EURO -> "${value.fixedAmount} €"
    }

    private fun cleanPercent(value: Double?): String = value?.let { "${it * 100.0} %" } ?: "taux inconnu"
    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun unresolved(reason: String) = Diagnostic(
        rules = emptyList(),
        observedFamilies = emptySet(),
        structuredFamilies = emptySet(),
        reasons = listOf("KALI garanties prévoyance : $reason ; aucun droit n'est enregistré.")
    )

    private val supportedAniCategories = setOf(
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE
    )
    private val acceptedStatuses = setOf("VIGUEUR", "VIGUEUR_ETEN", "VIGUEUR_NON_ETEN", "VIGUEUR_PARTIELLE")
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
    private val kaliTextIdRegex = Regex("^KALITEXT\\d+$")

    private val classificationVocabulary = Regex("\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|categorie|emploi|fonction|poste)s?\\b")
    private val deathRegex = Regex("\\b(?:capital\\s+deces|capital\\s+en\\s+cas\\s+de\\s+deces)\\b")
    private val incapacityRegex = Regex("\\b(?:incapacite\\s+temporaire|incapacite\\s+de\\s+travail)\\b")
    private val invalidityRegex = Regex("\\binvalidite\\b")
    private val spousePensionRegex = Regex("\\brente\\s+(?:de\\s+)?conjoint\\b")
    private val educationPensionRegex = Regex("\\brente\\s+(?:d[' ]|de\\s+)?education\\b")
    private val incomeBenefitRegex = Regex("\\b(?:indemnite|indemnites|rente|prestation|prestations)\\b")
    private val pensionRegex = Regex("\\brente\\b")

    private val annualSalaryPercentRegex = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*%[^.;]{0,90}?(?:salaire|remuneration|traitement)[^.;]{0,60}?(?:annuel|annuelle)"
    )
    private val monthlySalaryPercentRegex = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*%[^.;]{0,90}?(?:salaire|remuneration|traitement)[^.;]{0,60}?(?:mensuel|mensuelle)"
    )
    private val pmssMultipleRegex = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*(?:fois|x)\\s*(?:le\\s+)?(?:pmss|plafond mensuel(?: de la)? securite sociale)"
    )
    private val invalidityCategoryRegex = Regex("\\b([123])(?:re|ere|e|eme)?\\s+categorie\\b")
    private val franchiseRegex = Regex("\\bfranchise\\s+(?:de\\s+)?(\\d{1,4})\\s+jours?\\b")
    private val startDayRegex = Regex("\\ba compter du\\s+(\\d{1,4})(?:e|eme)?\\s+jour\\b")

    private val deductSsRegex = Regex("\\b(?:sous deduction|deduction faite)[^.;]{0,120}?(?:securite sociale|ijss|indemnites journalieres)\\b")
    private val includedSsRegex = Regex("\\b(?:y compris|incluant|prestations comprises)[^.;]{0,120}?(?:securite sociale|ijss|pension)\\b")
    private val additionalSsRegex = Regex("\\b(?:en complement|s'ajoute|s'ajoutent)[^.;]{0,120}?(?:securite sociale|ijss|pension)\\b")

    private val noSeniorityRegex = Regex("\\b(?:sans condition d'anciennete|sans anciennete|des l'embauche)\\b")
    private val seniorityRegex = Regex("\\b(?:anciennete|apres|a compter de)\\s+(?:d[' ]|de\\s+)?(\\d{1,3})\\s*(ans?|mois)\\b")
}
