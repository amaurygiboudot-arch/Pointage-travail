package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.time.LocalDate
import java.util.Locale

/**
 * Extrait une catégorie ANI uniquement lorsque la même clause KALI relie explicitement
 * une catégorie objective à la classification réelle du salarié.
 *
 * Les plages numériques et listes explicites sont acceptées. Les équivalences de fonctions,
 * analogies ou rapprochements sémantiques ne sont jamais déduits.
 */
object OfficialKaliProtectionCategoryParserV2 {
    data class Diagnostic(
        val articleId: String,
        val rule: ConventionProtectionCategoryV2.Rule?,
        val reasons: List<String>
    )

    private data class Candidate(
        val category: ProtectionCategoryV2.AniCategory,
        val clause: String,
        val evidence: String
    )

    fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2,
        auditDate: LocalDate
    ): Diagnostic {
        if (profile.classification.isEmpty()) {
            return Diagnostic(article.articleId, null, listOf("classification conventionnelle absente de la fiche salarié"))
        }
        val professionalStatus = profile.professionalStatus
            ?: return Diagnostic(article.articleId, null, listOf("statut cadre/non-cadre absent de la fiche salarié"))

        val raw = listOfNotNull(article.title, article.content).joinToString("\n")
        val clauses = splitClauses(raw)
        val candidates = clauses.mapNotNull { clause ->
            val category = categoryForClause(clause) ?: return@mapNotNull null
            if (!statusCompatible(category, professionalStatus)) return@mapNotNull null
            val evidence = classificationEvidence(clause, profile.classification) ?: return@mapNotNull null
            Candidate(category, clause, evidence)
        }.distinctBy { it.category to it.evidence }

        if (candidates.isEmpty()) {
            return Diagnostic(
                article.articleId,
                null,
                listOf("aucune clause ne relie sans ambiguïté la catégorie ANI à la classification exacte du salarié")
            )
        }
        val categories = candidates.map { it.category }.distinct()
        if (categories.size != 1) {
            return Diagnostic(
                article.articleId,
                null,
                listOf("plusieurs catégories ANI contradictoires correspondent à la classification du salarié")
            )
        }

        val category = categories.single()
        val extensionStatus = extensionStatus(article)
        val source = buildString {
            append("Légifrance KALI — ").append(article.articleId)
            article.title?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.trim()) }
        }
        val rule = ConventionProtectionCategoryV2.Rule(
            idcc = profile.idcc,
            ruleId = "KALI-PROTECTION-CATEGORY-${article.articleId}-${category.name}-${profile.classification.normalized().label().hashCode().toUInt().toString(16)}",
            effectiveFrom = article.effectiveFrom,
            effectiveTo = article.effectiveTo,
            // La source peut exprimer une plage ; la règle locale est volontairement figée
            // sur le profil exact qui a été prouvé au moment de l'audit.
            classification = profile.classification.normalized(),
            professionalStatus = professionalStatus,
            aniCategory = category,
            source = source,
            extensionStatus = extensionStatus,
            extensionEffectiveFrom = if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) article.extensionEffectiveFrom else null
        )

        if (!rule.structurallyValid()) {
            return Diagnostic(article.articleId, null, listOf("règle de catégorie ANI structurée incohérente"))
        }

        return Diagnostic(
            article.articleId,
            rule,
            buildList {
                add("classification prouvée : ${profile.classification.label()}")
                add("catégorie prouvée : ${category.name}")
                if (article.status.uppercase(Locale.ROOT) == "VIGUEUR_ETEN" && article.extensionEffectiveFrom == null) {
                    add("statut étendu présent mais date exacte d'extension absente ; applicabilité automatique bloquée")
                }
                if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED) {
                    add("texte non étendu : applicabilité à l'entreprise non démontrée")
                }
                if (category == ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE) {
                    add("extension de régime seulement : aucune affiliation ANI 2.1/2.2 n'est déduite")
                }
            }
        )
    }

    private fun splitClauses(raw: String): List<String> = raw
        .replace('–', '-')
        .replace('—', '-')
        .split(Regex("[;\\n]+"))
        .map { OfficialKaliProfileMatcherV2.normalize(it) }
        .map { it.trim(' ', '-', '\t') }
        .filter { it.length >= 20 }

    private fun categoryForClause(clause: String): ProtectionCategoryV2.AniCategory? {
        val aniContext = clause.contains("accord national interprofessionnel") ||
            clause.contains("ani du 17 novembre 2017") ||
            clause.contains("ani du 17/11/2017") ||
            clause.contains("prevoyance des cadres")
        val article21 = article21Regex.containsMatchIn(clause)
        val article22 = article22Regex.containsMatchIn(clause)
        if (article21 && article22) return null
        if (aniContext && article21) return ProtectionCategoryV2.AniCategory.ARTICLE_2_1
        if (aniContext && article22) return ProtectionCategoryV2.AniCategory.ARTICLE_2_2

        val extension = extensionWords.any(clause::contains)
        val extensionLegalContext = clause.contains("r. 242-1-1") ||
            clause.contains("r 242-1-1") ||
            clause.contains("salariés non-cadres") ||
            clause.contains("salaries non-cadres") ||
            clause.contains("non-assimiles aux cadres") ||
            clause.contains("regime de protection sociale complementaire des cadres")
        return if (extension && extensionLegalContext) ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE else null
    }

    private fun statusCompatible(category: ProtectionCategoryV2.AniCategory, status: String): Boolean = when (category) {
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1 -> status == "CADRE"
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2 -> status == "NON_CADRE"
        ProtectionCategoryV2.AniCategory.TO_CONFIRM,
        ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE -> false
    }

    /** Retourne une preuve textuelle courte lorsque la classification est explicitement couverte. */
    private fun classificationEvidence(clause: String, classification: ConventionClassificationV2): String? {
        classification.coefficient?.let { coefficient ->
            val ranges = coefficientRangeRegex.findAll(clause).mapNotNull { match ->
                val from = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val to = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                minOf(from, to)..maxOf(from, to)
            }.toList()
            if (ranges.any { coefficient in it }) return "coefficient $coefficient dans plage explicite"
            val exact = coefficientExactRegex.findAll(clause)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSet()
            if (coefficient in exact) return "coefficient $coefficient explicite"
            // Si la clause utilise explicitement un coefficient mais ne couvre pas celui du salarié,
            // aucune autre dimension moins précise ne peut sauver la correspondance.
            if (coefficientVocabulary.containsMatchIn(clause)) return null
        }

        val level = classification.level?.let(::normalizeToken)
        val echelon = classification.echelon?.let(::normalizeToken)
        if (level != null || echelon != null) {
            val mentionsLevel = levelVocabulary.containsMatchIn(clause)
            val mentionsEchelon = echelonVocabulary.containsMatchIn(clause)
            if (mentionsLevel || mentionsEchelon) {
                if (mentionsLevel && level == null) return null
                if (mentionsEchelon && echelon == null) return null
                if (mentionsLevel && !levelMatches(clause, level!!)) return null
                if (mentionsEchelon && !echelonMatches(clause, echelon!!)) return null
                return buildString {
                    if (level != null) append("niveau ").append(level)
                    if (echelon != null) {
                        if (isNotEmpty()) append(" / ")
                        append("échelon ").append(echelon)
                    }
                    append(" explicite")
                }
            }
        }

        classification.position?.let { value ->
            if (exactLabelMatch(clause, "position", value)) return "position ${normalizeToken(value)} explicite"
        }
        classification.group?.let { value ->
            if (exactLabelMatch(clause, "groupe", value)) return "groupe ${normalizeToken(value)} explicite"
        }
        classification.category?.let { value ->
            if (exactLabelMatch(clause, "categorie", value)) return "catégorie ${normalizeToken(value)} explicite"
        }
        classification.employment?.let { value ->
            val wanted = OfficialKaliProfileMatcherV2.normalize(value)
            if (Regex("\\b(?:emploi|fonction|poste)\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b").containsMatchIn(clause)) {
                return "emploi ${normalizeToken(value)} explicite"
            }
        }
        return null
    }

    private fun levelMatches(clause: String, wantedRaw: String): Boolean {
        val wanted = romanOrArabic(wantedRaw) ?: return exactLabelMatch(clause, "niveau", wantedRaw)
        val ranges = levelRangeRegex.findAll(clause).mapNotNull { match ->
            val from = romanOrArabic(match.groupValues[1]) ?: return@mapNotNull null
            val to = romanOrArabic(match.groupValues[2]) ?: return@mapNotNull null
            minOf(from, to)..maxOf(from, to)
        }.toList()
        if (ranges.any { wanted in it }) return true
        val exact = levelExactRegex.findAll(clause).mapNotNull { romanOrArabic(it.groupValues[1]) }.toSet()
        return wanted in exact
    }

    private fun echelonMatches(clause: String, wantedRaw: String): Boolean {
        val wanted = normalizeToken(wantedRaw)
        val exact = echelonExactRegex.findAll(clause).map { normalizeToken(it.groupValues[1]) }.toSet()
        if (wanted in exact) return true
        val lists = echelonListRegex.findAll(clause).flatMap { match ->
            match.groupValues[1].split(Regex("\\s*(?:,|/|et|ou)\\s*")).asSequence()
        }.map(::normalizeToken).toSet()
        return wanted in lists
    }

    private fun exactLabelMatch(clause: String, label: String, raw: String): Boolean {
        val wanted = OfficialKaliProfileMatcherV2.normalize(raw)
        return Regex("\\b$label\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b").containsMatchIn(clause)
    }

    private fun romanOrArabic(raw: String): Int? {
        val token = normalizeToken(raw)
        token.toIntOrNull()?.let { return it }
        return when (token) {
            "I" -> 1
            "II" -> 2
            "III" -> 3
            "IV" -> 4
            "V" -> 5
            "VI" -> 6
            "VII" -> 7
            "VIII" -> 8
            "IX" -> 9
            "X" -> 10
            "XI" -> 11
            "XII" -> 12
            else -> null
        }
    }

    private fun normalizeToken(raw: String): String = OfficialKaliProfileMatcherV2.normalize(raw).uppercase(Locale.FRANCE)

    private fun extensionStatus(article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle): ConventionMinimumSalaryV2.ExtensionStatus =
        when (article.status.uppercase(Locale.ROOT)) {
            "VIGUEUR_ETEN" -> if (article.extensionEffectiveFrom != null) ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED else ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
            "VIGUEUR_NON_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
            else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
        }

    private val article21Regex = Regex("\\barticle\\s*2[.,]1\\b")
    private val article22Regex = Regex("\\barticle\\s*2[.,]2\\b")
    private val extensionWords = listOf(
        "susceptibles de beneficier d'une extension de regime",
        "susceptible de beneficier d'une extension de regime",
        "pouvant etre integres au regime",
        "pouvant etre integrees au regime",
        "extension de regime"
    )

    private val coefficientVocabulary = Regex("\\bcoefficients?\\b")
    private val coefficientRangeRegex = Regex("\\b(?:du\\s+)?coefficients?\\s*[:.\\-]?\\s*(\\d{2,4})\\s*(?:a|au|-)\\s*(?:coefficient\\s*)?(\\d{2,4})\\b")
    private val coefficientExactRegex = Regex("\\bcoefficients?\\s*[:.\\-]?\\s*(\\d{2,4})\\b")

    private val levelVocabulary = Regex("\\bniveaux?\\b")
    private val echelonVocabulary = Regex("\\bechelons?\\b")
    private val levelRangeRegex = Regex("\\bniveaux?\\s*[:.\\-]?\\s*([ivx]+|\\d{1,2})\\s*(?:a|au|-)\\s*([ivx]+|\\d{1,2})\\b")
    private val levelExactRegex = Regex("\\bniveau\\s*[:.\\-]?\\s*([ivx]+|\\d{1,2})\\b")
    private val echelonExactRegex = Regex("\\bechelon\\s*[:.\\-]?\\s*([a-z0-9]+)\\b")
    private val echelonListRegex = Regex("\\bechelons\\s*[:.\\-]?\\s*([a-z0-9]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9]+)+)")
}
