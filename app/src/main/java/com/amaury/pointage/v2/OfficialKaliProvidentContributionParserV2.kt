package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.time.LocalDate
import java.util.Locale

/**
 * Assemble une règle de cotisation de prévoyance à partir de plusieurs KALIARTI uniquement
 * lorsqu'ils appartiennent au même KALITEXT officiel et que chaque élément indispensable
 * (bénéficiaires, ancienneté, assiette, taux salarié/employeur) est prouvé sans ambiguïté.
 *
 * Ce parseur est volontairement fail-closed : il ne complète jamais un taux, une ancienneté,
 * une assiette, une classification ou une catégorie à partir d'un usage supposé de branche.
 */
object OfficialKaliProvidentContributionParserV2 {
    data class Diagnostic(
        val rule: ConventionProvidentContributionV2.Rule?,
        val conventionScopeKey: String?,
        val usedArticleIds: List<String>,
        val reasons: List<String>
    )

    private data class Basis(
        val label: String,
        val lowerCeilingMultiple: Double,
        val upperCeilingMultiple: Double?
    )

    private data class Rates(
        val employeeRate: Double,
        val employerRate: Double,
        val minimumTotalRate: Double? = null,
        val minimumEmployerRate: Double? = null,
        val allocationRule: ConventionProvidentContributionV2.AllocationRule =
            ConventionProvidentContributionV2.AllocationRule.EXACT
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
        if (profileIdcc.isBlank() || kaliIdcc.isBlank() || profileIdcc != kaliIdcc) {
            return unresolved("IDCC du profil et de la collecte KALI différents")
        }
        if (!protectionCategory.confirmed || protectionCategory.aniCategory !in supportedAniCategories) {
            return unresolved("catégorie ANI exacte non confirmée pour le salarié")
        }
        val professionalStatus = profile.professionalStatus
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return unresolved("statut cadre/non-cadre exact manquant")
        val classification = profile.classification.normalized()
        if (classification.isEmpty()) {
            return unresolved("classification conventionnelle exacte manquante")
        }

        val ambiguousNormalized = ambiguousArticleTextIds.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val eligible = articles.filter { article ->
            val id = article.articleId.trim().uppercase(Locale.ROOT)
            id.matches(kaliArticleIdRegex) &&
                id !in ambiguousNormalized &&
                !auditDate.isBefore(article.effectiveFrom) &&
                article.effectiveTo?.let(auditDate::isAfter) != true &&
                article.status.trim().uppercase(Locale.ROOT) in acceptedStatuses
        }
        if (eligible.isEmpty()) return unresolved("aucun KALIARTI applicable et non ambigu n'est exploitable")

        val grouped = eligible.groupBy { article ->
            articleTextIds[article.articleId.trim().uppercase(Locale.ROOT)]
                ?: articleTextIds.entries.firstOrNull { it.key.equals(article.articleId, ignoreCase = true) }?.value
        }.filterKeys { it?.matches(kaliTextIdRegex) == true }

        if (grouped.isEmpty()) return unresolved("aucun KALITEXT parent exact n'est prouvé pour les articles consultés")

        val complete = grouped.mapNotNull { (scope, scopedArticles) ->
            parseScope(
                idcc = kaliIdcc,
                scope = scope!!,
                auditDate = auditDate,
                classification = classification,
                professionalStatus = professionalStatus,
                category = protectionCategory.aniCategory,
                articles = scopedArticles
            )
        }
        if (complete.isEmpty()) {
            return unresolved("aucun KALITEXT ne contient à lui seul bénéficiaires, ancienneté, assiette et financement suffisamment prouvés pour la classification exacte")
        }
        if (complete.size != 1) {
            return unresolved("plusieurs KALITEXT produisent des barèmes complets ; périmètre conventionnel unique non déterminé")
        }
        return complete.single()
    }

    private fun parseScope(
        idcc: String,
        scope: String,
        auditDate: LocalDate,
        classification: ConventionClassificationV2,
        professionalStatus: String,
        category: ProtectionCategoryV2.AniCategory,
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>
    ): Diagnostic? {
        val normalized = articles.associateWith { article ->
            OfficialKaliProfileMatcherV2.normalize(listOfNotNull(article.title, article.content).joinToString("\n"))
        }
        val profileCompatible = normalized.filterValues { text ->
            clauseMatchesProfile(text, classification, professionalStatus)
        }
        val beneficiaryArticles = profileCompatible.filterValues { text ->
            beneficiaryMatches(text, category, professionalStatus)
        }.keys
        if (beneficiaryArticles.isEmpty()) return null

        val seniorityCandidates = beneficiaryArticles
            .mapNotNull { article -> parseSeniorityMonths(profileCompatible.getValue(article)) }
            .distinct()
        if (seniorityCandidates.size != 1) return null
        val seniorityMonths = seniorityCandidates.single()

        val basisCandidates = profileCompatible.values.mapNotNull(::parseBasis).distinct()
        if (basisCandidates.size != 1) return null
        val basis = basisCandidates.single()

        val rateCandidates = profileCompatible.values.mapNotNull(::parseRates).distinct()
        if (rateCandidates.size != 1) return null
        val rates = rateCandidates.single()

        val usedArticles = buildSet {
            addAll(beneficiaryArticles)
            profileCompatible.forEach { (article, text) ->
                if (parseBasis(text) == basis || parseRates(text) == rates) add(article)
            }
        }.toList()
        if (usedArticles.isEmpty()) return null

        val effectiveFrom = usedArticles.maxOf { it.effectiveFrom }
        val finiteEnds = usedArticles.mapNotNull { it.effectiveTo }
        val effectiveTo = finiteEnds.minOrNull()
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) return null
        if (auditDate.isBefore(effectiveFrom) || effectiveTo?.let(auditDate::isAfter) == true) return null

        // Une date seule n'est jamais une preuve d'extension : le statut officiel doit être
        // VIGUEUR_ETEN pour chacun des articles qui composent la règle.
        val allExtended = usedArticles.all { article ->
            article.status.trim().uppercase(Locale.ROOT) == "VIGUEUR_ETEN" &&
                article.extensionEffectiveFrom != null
        }
        val allExplicitlyNotExtended = usedArticles.all { article ->
            article.status.trim().uppercase(Locale.ROOT) == "VIGUEUR_NON_ETEN"
        }
        val extensionStatus = when {
            allExtended -> ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED
            allExplicitlyNotExtended -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
            else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
        }
        val extensionEffectiveFrom = if (allExtended) {
            usedArticles.mapNotNull { it.extensionEffectiveFrom }.maxOrNull()
        } else null

        val articleIds = usedArticles.map { it.articleId.trim().uppercase(Locale.ROOT) }.sorted()
        val source = "Légifrance KALI — $scope — ${articleIds.joinToString(", ")}"
        val rule = ConventionProvidentContributionV2.Rule(
            idcc = idcc,
            ruleId = "KALI-PROVIDENT-CONTRIBUTION-$scope-${category.name}-${classification.label().hashCode().toUInt().toString(16)}",
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            // Même lorsqu'un texte couvre une population plus large, la preuve persistée reste
            // limitée au profil exact qui a été contrôlé afin d'empêcher tout débordement voisin.
            classification = classification,
            professionalStatus = professionalStatus,
            aniCategories = setOf(category),
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    minimumSeniorityMonths = seniorityMonths,
                    bands = listOf(
                        ConventionProvidentContributionV2.Band(
                            label = basis.label,
                            lowerCeilingMultiple = basis.lowerCeilingMultiple,
                            upperCeilingMultiple = basis.upperCeilingMultiple,
                            employeeRate = rates.employeeRate,
                            employerRate = rates.employerRate,
                            minimumTotalRate = rates.minimumTotalRate,
                            minimumEmployerRate = rates.minimumEmployerRate,
                            allocationRule = rates.allocationRule
                        )
                    )
                )
            ),
            source = source,
            conventionScopeKey = scope,
            extensionStatus = extensionStatus,
            extensionEffectiveFrom = extensionEffectiveFrom
        )
        if (!rule.structurallyValid()) return null

        return Diagnostic(
            rule = rule,
            conventionScopeKey = scope,
            usedArticleIds = articleIds,
            reasons = buildList {
                add("KALI prévoyance : bénéficiaire compatible avec ${category.name} et statut $professionalStatus.")
                add("KALI prévoyance : classification contrôlée : ${classification.label()}.")
                add("KALI prévoyance : ancienneté minimale prouvée à $seniorityMonths mois.")
                add("KALI prévoyance : assiette unique prouvée (${basis.label}).")
                if (rates.allocationRule == ConventionProvidentContributionV2.AllocationRule.EXACT) {
                    add("KALI prévoyance : taux exact salarié ${(rates.employeeRate * 100.0)} % / employeur ${(rates.employerRate * 100.0)} %.")
                } else {
                    add(
                        "KALI prévoyance : financement minimal ${(rates.minimumTotalRate!! * 100.0)} %, " +
                            "minimum employeur ${(rates.minimumEmployerRate!! * 100.0)} %, " +
                            "répartition par défaut salarié ${(rates.employeeRate * 100.0)} % / employeur ${(rates.employerRate * 100.0)} % ; accord d'entreprise susceptible de la modifier."
                    )
                }
                if (!allExtended) add("KALI prévoyance : statut VIGUEUR_ETEN + date d'extension non prouvés pour tous les articles utilisés ; applicabilité automatique bloquée.")
            }
        )
    }

    /**
     * Une clause qui ne cite aucune classification est générale et peut être utilisée pour le
     * profil déjà vérifié. Dès qu'une classification est citée, tous les critères locaux connus
     * doivent apparaître dans une fenêtre compacte de cette même clause.
     */
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
            before = 120,
            after = 260,
            maxClassificationSpan = 260
        ).isNotEmpty()
    }

    private fun classificationVocabularyPresent(text: String): Boolean =
        primaryClassificationVocabulary.containsMatchIn(text) ||
            categoryClassificationVocabulary.containsMatchIn(text)

    private fun beneficiaryMatches(
        text: String,
        category: ProtectionCategoryV2.AniCategory,
        professionalStatus: String
    ): Boolean {
        val providentContext = providentWords.any(text::contains)
        if (!providentContext) return false
        return when (category) {
            ProtectionCategoryV2.AniCategory.ARTICLE_2_1 ->
                professionalStatus == "CADRE" && article21Regex.containsMatchIn(text)
            ProtectionCategoryV2.AniCategory.ARTICLE_2_2 ->
                professionalStatus == "NON_CADRE" && article22Regex.containsMatchIn(text)
            ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2 ->
                professionalStatus == "NON_CADRE" && outsideAniRegexes.any { it.containsMatchIn(text) }
            ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE ->
                professionalStatus == "NON_CADRE" && extensionEligibleRegexes.any { it.containsMatchIn(text) }
            else -> false
        }
    }

    private fun parseSeniorityMonths(text: String): Int? {
        if (noSeniorityRegex.containsMatchIn(text)) return 0
        val match = seniorityRegex.find(text) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        return when {
            unit.startsWith("an") -> value * 12
            unit.startsWith("mois") -> value
            else -> null
        }.takeIf { it in 0..600 }
    }

    private fun parseBasis(text: String): Basis? {
        val hasBasisContext = basisContextWords.any(text::contains)
        if (!hasBasisContext) return null

        val ceiling = ceilingMultipleRegex.find(text)?.groupValues?.get(1)?.let(::parseNumber)
        if (ceiling != null && ceiling > 0.0 && ceiling <= 20.0) {
            return Basis(
                label = "Salaire de référence jusqu'à ${cleanNumber(ceiling)} PMSS",
                lowerCeilingMultiple = 0.0,
                upperCeilingMultiple = ceiling
            )
        }
        if (fullGrossRegexes.any { it.containsMatchIn(text) }) {
            return Basis("Salaire brut total", 0.0, null)
        }
        return null
    }

    private fun parseRates(text: String): Rates? {
        if (!rateContextWords.any(text::contains)) return null

        val explicitCandidates = buildList {
            explicitPartsRegex.findAll(text).forEach { match ->
                val employee = parsePercent(match.groupValues[1]) ?: return@forEach
                val employer = parsePercent(match.groupValues[2]) ?: return@forEach
                add(employee to employer)
            }
            explicitPartsEmployerFirstRegex.findAll(text).forEach { match ->
                val employer = parsePercent(match.groupValues[1]) ?: return@forEach
                val employee = parsePercent(match.groupValues[2]) ?: return@forEach
                add(employee to employer)
            }
        }.distinct()

        val minimumTotal = minimumTotalRateRegexes
            .asSequence()
            .mapNotNull { it.find(text)?.groupValues?.get(1)?.let(::parsePercent) }
            .distinct()
            .toList()
        val minimumEmployer = minimumEmployerRateRegexes
            .asSequence()
            .mapNotNull { it.find(text)?.groupValues?.get(1)?.let(::parsePercent) }
            .distinct()
            .toList()
        if (minimumTotal.size > 1 || minimumEmployer.size > 1) return null

        val minTotal = minimumTotal.singleOrNull()
        val minEmployer = minimumEmployer.singleOrNull()
        val defaultWording = defaultAllocationRegex.containsMatchIn(text)
        val companyOverride = companyAgreementOverrideRegex.containsMatchIn(text)
        val flexibleFinancingMentioned = minTotal != null || minEmployer != null || defaultWording || companyOverride

        if (flexibleFinancingMentioned) {
            // Un plancher ou une répartition "par défaut" n'est jamais assimilé à une répartition exacte.
            // Pour conserver la preuve, il faut les deux minima, les deux parts par défaut et la preuve
            // qu'un accord d'entreprise peut modifier cette répartition.
            if (minTotal == null || minEmployer == null || !defaultWording || !companyOverride) return null
            if (explicitCandidates.size != 1) return null
            val (employee, employer) = explicitCandidates.single()
            if (employee + employer + RATE_EPSILON < minTotal) return null
            if (employer + RATE_EPSILON < minEmployer) return null
            return Rates(
                employeeRate = employee,
                employerRate = employer,
                minimumTotalRate = minTotal,
                minimumEmployerRate = minEmployer,
                allocationRule = ConventionProvidentContributionV2.AllocationRule.DEFAULT_MODIFIABLE_BY_COMPANY_AGREEMENT
            )
        }

        if (explicitCandidates.size == 1) {
            val (employee, employer) = explicitCandidates.single()
            return Rates(employee, employer)
        }
        if (explicitCandidates.size > 1) return null

        val total = totalRateRegex.find(text)?.groupValues?.get(1)?.let(::parsePercent)
        val split = fiftyFiftyRegex.containsMatchIn(text)
        if (total != null && split) return Rates(total / 2.0, total / 2.0)
        return null
    }

    private fun parsePercent(raw: String): Double? = parseNumber(raw)
        ?.takeIf { it >= 0.0 && it <= 100.0 }
        ?.div(100.0)

    private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun cleanNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun unresolved(reason: String) = Diagnostic(
        rule = null,
        conventionScopeKey = null,
        usedArticleIds = emptyList(),
        reasons = listOf("KALI prévoyance cotisations : $reason ; aucun barème n'est enregistré.")
    )

    private val supportedAniCategories = setOf(
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE
    )
    private val acceptedStatuses = setOf(
        "VIGUEUR",
        "VIGUEUR_ETEN",
        "VIGUEUR_NON_ETEN",
        "VIGUEUR_DIFF",
        "VIGUEUR_PARTIELLE"
    )
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
    private val kaliTextIdRegex = Regex("^KALITEXT\\d+$")

    private val providentWords = listOf("prevoyance", "protection sociale complementaire", "incapacite", "invalidite", "deces")
    private val basisContextWords = listOf("salaire de reference", "assiette", "remuneration servant de base", "base de cotisation")
    private val rateContextWords = listOf(
        "cotisation",
        "taux",
        "financement",
        "part salariale",
        "part patronale",
        "charge du salarie",
        "charge de l'employeur"
    )

    private val primaryClassificationVocabulary = Regex(
        "\\b(?:coefficient|coef(?:ficient)?|niveau|echelon|position|groupe|emploi|fonction|poste)s?\\b"
    )
    private val categoryClassificationVocabulary = Regex("\\bcategorie\\s*(?:conventionnelle|[:.\\-])")

    private val article21Regex = Regex("\\b(?:article|art\\.?)\\s*2[.,]1\\b")
    private val article22Regex = Regex("\\b(?:article|art\\.?)\\s*2[.,]2\\b")
    private val outsideAniRegexes = listOf(
        Regex("\\bne relevant pas (?:des?\\s+)?articles?\\s+2[.,]1(?: et| ni| ou|,)? 2[.,]2\\b"),
        Regex("\\bhors (?:les )?articles? 2[.,]1(?: et| ni| ou|,)? 2[.,]2\\b"),
        Regex("\\bnon[- ]cadres? ne relevant pas (?:des )?articles? 2[.,]1(?: et| ni| ou|,)? 2[.,]2\\b")
    )
    private val extensionEligibleRegexes = listOf(
        Regex("\\bextension (?:du )?regime (?:de )?prevoyance des cadres\\b"),
        Regex("\\bintegres? au regime de protection sociale complementaire des cadres\\b")
    )

    private val noSeniorityRegex = Regex("\\b(?:sans condition d'anciennete|des l'embauche|des son embauche|a compter de l'embauche)\\b")
    private val seniorityRegex = Regex("\\b(?:a compter de|apres|ayant|justifiant de|beneficiant de)?\\s*(\\d{1,2})\\s*(mois|ans?|annees?)\\s+d'anciennete\\b")

    private val ceilingMultipleRegex = Regex(
        "\\b(?:limite[e]?|plafonne[e]?|jusqu[' ]?a|dans la limite de)\\s*(?:a\\s*)?(\\d+(?:[.,]\\d+)?)\\s*(?:fois|x)\\s*(?:le )?plafond(?: mensuel)?(?: de la securite sociale)?\\b"
    )
    private val fullGrossRegexes = listOf(
        Regex("\\b(?:totalite|integralite) (?:du )?salaire brut\\b"),
        Regex("\\bsalaire brut (?:total|sans plafond|non plafonne)\\b")
    )

    private val explicitPartsRegex = Regex(
        "(?:part salariale|a la charge du salarie|salarie)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%[^%]{0,120}(?:part patronale|a la charge de l'employeur|employeur)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%"
    )
    private val explicitPartsEmployerFirstRegex = Regex(
        "(?:part patronale|a la charge de l'employeur|employeur)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%[^%]{0,120}(?:part salariale|a la charge du salarie|salarie)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%"
    )
    private val totalRateRegex = Regex("\\b(?:cotisation|taux)\\s*(?:globale?|totale?)?\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*%")
    private val fiftyFiftyRegex = Regex("\\b(?:50\\s*%\\s*(?:employeur|patronal)[^%]{0,80}50\\s*%\\s*(?:salarie|salarial)|reparti[e]?\\s+par moitie|a parts egales)\\b")

    private val minimumTotalRateRegexes = listOf(
        Regex("\\b(?:financement|cotisation|taux)\\s+(?:(?:global|total)e?\\s+)?(?:minimal[e]?|minimum)\\s*(?:est|de|:|=)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%"),
        Regex("\\b(?:minimum|minimal[e]?)\\s+(?:(?:global|total)e?)\\s*(?:de|:|=)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%")
    )
    private val minimumEmployerRateRegexes = listOf(
        Regex("\\b(?:part|contribution|cotisation)\\s+(?:patronale|employeur)\\s+(?:minimal[e]?|minimum)\\s*(?:est|de|:|=)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%"),
        Regex("\\b(?:part|contribution|cotisation)\\s+(?:patronale|employeur)\\s+ne peut etre inferieur[e]?\\s+a\\s*(\\d+(?:[.,]\\d+)?)\\s*%"),
        Regex("\\b(?:au moins|minimum)\\s*(\\d+(?:[.,]\\d+)?)\\s*%\\s*(?:a la charge de l'employeur|employeur|patronal)\\b")
    )
    private val defaultAllocationRegex = Regex(
        "\\b(?:par defaut|a defaut d[' ]accord(?: d[' ]entreprise)?|en l[' ]absence d[' ]accord(?: d[' ]entreprise)?)\\b"
    )
    private val companyAgreementOverrideRegex = Regex(
        "\\b(?:a defaut d[' ]accord d[' ]entreprise|sauf accord d[' ]entreprise|accord d[' ]entreprise (?:peut|pourra) (?:modifier|modifie)|repartition[^.]{0,120}(?:peut|pourra) etre modifiee? par accord d[' ]entreprise)\\b"
    )

    private const val RATE_EPSILON = 1e-12
}
