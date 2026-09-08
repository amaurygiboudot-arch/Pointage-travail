package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.time.LocalDate
import java.util.Locale

/**
 * Extrait une preuve KALI de catégorie ANI uniquement lorsqu'une clause relie explicitement
 * une catégorie objective à la classification réelle du salarié.
 *
 * Cette preuve KALI ne suffit jamais, à elle seule, à rendre le classement applicable :
 * l'agrément APEC exact reste volontairement non vérifié à ce stade.
 */
object OfficialKaliProtectionCategoryParserV2 {
    data class Diagnostic(
        val articleId: String,
        val rule: ConventionProtectionCategoryV2.Rule?,
        val reasons: List<String>
    )

    private data class Candidate(
        val category: ProtectionCategoryV2.AniCategory,
        val evidence: String
    )

    /**
     * Compatibilité volontairement bloquante : sans IDCC provenant de la collecte KALI,
     * le profil local seul ne prouve pas que l'article a été obtenu dans la bonne convention.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2,
        auditDate: LocalDate
    ): Diagnostic = Diagnostic(
        article.articleId,
        null,
        listOf("IDCC de la collecte KALI non fourni ; classement ANI bloqué")
    )

    fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2,
        auditDate: LocalDate,
        verifiedIdcc: String
    ): Diagnostic {
        val normalizedProfileIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        val normalizedVerifiedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(verifiedIdcc)
        if (normalizedProfileIdcc.isBlank() || normalizedVerifiedIdcc.isBlank() || normalizedProfileIdcc != normalizedVerifiedIdcc) {
            return Diagnostic(article.articleId, null, listOf("IDCC KALI différent ou non prouvé pour le profil salarié"))
        }
        if (!kaliArticleIdRegex.matches(article.articleId)) {
            return Diagnostic(article.articleId, null, listOf("identifiant source KALIARTI invalide ou non prouvé"))
        }
        val officialStatus = article.status.trim().uppercase(Locale.ROOT)
        if (officialStatus !in acceptedStatuses) {
            return Diagnostic(article.articleId, null, listOf("statut officiel de l'article non exploitable avec certitude"))
        }
        if (auditDate.isBefore(article.effectiveFrom) || article.effectiveTo?.let(auditDate::isAfter) == true) {
            return Diagnostic(article.articleId, null, listOf("article hors période d'effet à la date contrôlée"))
        }
        if (profile.classification.isEmpty()) {
            return Diagnostic(article.articleId, null, listOf("classification conventionnelle absente de la fiche salarié"))
        }
        val professionalStatus = profile.professionalStatus
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it == "CADRE" || it == "NON_CADRE" }
            ?: return Diagnostic(article.articleId, null, listOf("statut cadre/non-cadre absent ou non normalisé dans la fiche salarié"))

        val raw = listOfNotNull(article.title, article.content).joinToString("\n")
        val clauses = splitClauses(raw)

        // Les contradictions sont détectées AVANT le filtre cadre/non-cadre. Un texte qui
        // rattache la même classification à 2.1 et 2.2 ne peut jamais être "réparé" par le statut local.
        val classificationCandidates = clauses.mapNotNull { clause ->
            val category = categoryForClause(clause) ?: return@mapNotNull null
            val evidence = classificationEvidence(clause, profile.classification) ?: return@mapNotNull null
            Candidate(category, evidence)
        }.distinctBy { it.category to it.evidence }

        if (classificationCandidates.isEmpty()) {
            return Diagnostic(
                article.articleId,
                null,
                listOf("aucune clause ne relie sans ambiguïté la catégorie ANI à la classification exacte du salarié")
            )
        }
        val categories = classificationCandidates.map { it.category }.distinct()
        if (categories.size != 1) {
            return Diagnostic(
                article.articleId,
                null,
                listOf("plusieurs catégories ANI contradictoires correspondent à la classification du salarié")
            )
        }

        val category = categories.single()
        if (!statusCompatible(category, professionalStatus)) {
            return Diagnostic(
                article.articleId,
                null,
                listOf("statut professionnel incompatible avec la catégorie ANI explicitement trouvée ; classement bloqué")
            )
        }

        val extensionStatus = extensionStatus(article)
        val source = buildString {
            append("Légifrance KALI — ").append(article.articleId)
            article.title?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.trim()) }
        }
        val rule = ConventionProtectionCategoryV2.Rule(
            idcc = normalizedVerifiedIdcc,
            ruleId = "KALI-PROTECTION-CATEGORY-${article.articleId}-${category.name}-${profile.classification.normalized().label().hashCode().toUInt().toString(16)}",
            effectiveFrom = article.effectiveFrom,
            effectiveTo = article.effectiveTo,
            // Même lorsqu'une plage est citée, on persiste uniquement le profil exact qui a été prouvé.
            classification = profile.classification.normalized(),
            professionalStatus = professionalStatus,
            aniCategory = category,
            source = source,
            extensionStatus = extensionStatus,
            extensionEffectiveFrom = if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) article.extensionEffectiveFrom else null,
            // KALIARTI + IDCC ne suffisent pas à identifier sans ambiguïté un accord national/régional.
            // Le périmètre exact sera rattaché par une couche dédiée avant tout agrément APEC.
            conventionScopeKey = null,
            approvalStatus = ConventionProtectionCategoryV2.ApprovalStatus.APEC_REQUIRED_UNVERIFIED
        )

        if (!rule.structurallyValid()) {
            return Diagnostic(article.articleId, null, listOf("règle de catégorie ANI structurée incohérente"))
        }

        return Diagnostic(
            article.articleId,
            rule,
            buildList {
                add("IDCC prouvé : $normalizedVerifiedIdcc")
                add("classification prouvée : ${profile.classification.label()}")
                add("catégorie KALI prouvée : ${category.name}")
                if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN) {
                    add("extension officielle exacte non prouvée ; applicabilité automatique bloquée")
                }
                if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED) {
                    add("texte non étendu : applicabilité à l'entreprise non démontrée")
                }
                add("périmètre exact de l'accord et agrément APEC non encore rapprochés ; classement automatique bloqué")
                if (category == ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE) {
                    add("extension de régime seulement : aucune affiliation ANI 2.1/2.2 n'est déduite")
                }
            }
        )
    }

    /**
     * Accepte les listes à puces seulement lorsqu'elles suivent immédiatement un en-tête ANI explicite.
     * Le contexte est réinitialisé au premier paragraphe ordinaire afin d'éviter toute propagation lointaine.
     */
    private fun splitClauses(raw: String): List<String> {
        val parts = raw
            .replace('–', '-')
            .replace('—', '-')
            .split(Regex("[;\n]+"))

        val result = mutableListOf<String>()
        var bulletContext: String? = null
        parts.forEach { part ->
            val trimmedRaw = part.trim()
            if (trimmedRaw.isBlank()) return@forEach
            val isBullet = trimmedRaw.startsWith("-") || trimmedRaw.startsWith("•") || trimmedRaw.startsWith("*")
            val normalized = OfficialKaliProfileMatcherV2.normalize(trimmedRaw)
                .trim(' ', '-', '•', '*', '\t')
            if (normalized.length < 2) return@forEach

            val explicitCategoryHeader = categoryForClause(normalized) != null
            val containsClassification = classificationVocabularyPresent(normalized)
            when {
                explicitCategoryHeader -> {
                    if (normalized.length >= 20) result += normalized
                    bulletContext = if (containsClassification) null else normalized
                }
                isBullet && bulletContext != null -> {
                    val contextualized = "${bulletContext!!} $normalized"
                    if (contextualized.length >= 20) result += contextualized
                }
                else -> {
                    bulletContext = null
                    if (normalized.length >= 20) result += normalized
                }
            }
        }
        return result
    }

    private fun classificationVocabularyPresent(clause: String): Boolean =
        coefficientVocabulary.containsMatchIn(clause) ||
            levelVocabulary.containsMatchIn(clause) ||
            echelonVocabulary.containsMatchIn(clause) ||
            Regex("\\b(?:position|groupe|categorie|emploi|fonction|poste)s?\\b").containsMatchIn(clause)

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

            val listed = coefficientListRegex.findAll(clause).flatMap { match ->
                numberRegex.findAll(match.groupValues[1]).mapNotNull { it.value.toIntOrNull() }
            }.toSet()
            if (coefficient in listed) return "coefficient $coefficient dans liste explicite"

            val exact = coefficientExactRegex.findAll(clause)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSet()
            if (coefficient in exact) return "coefficient $coefficient explicite"

            // Un coefficient mentionné mais différent interdit de retomber sur un critère moins précis.
            if (coefficientVocabulary.containsMatchIn(clause)) return null
        }

        val level = classification.level?.let(::normalizeToken)
        val echelon = classification.echelon?.let(::normalizeToken)
        val mentionsLevel = levelVocabulary.containsMatchIn(clause)
        val mentionsEchelon = echelonVocabulary.containsMatchIn(clause)
        if (mentionsLevel || mentionsEchelon) {
            if (mentionsLevel && level == null) return null
            if (mentionsEchelon && echelon == null) return null

            // Quand niveau + échelon sont présents, ils doivent apparaître comme un couple explicite.
            // On refuse le produit cartésien implicite (ex. VI-A + VII-B ne prouve jamais VI-B).
            if (mentionsLevel && mentionsEchelon) {
                val wantedLevel = level ?: return null
                val wantedEchelon = echelon ?: return null
                if (!levelEchelonPairMatches(clause, wantedLevel, wantedEchelon)) return null
                return "niveau $wantedLevel / échelon $wantedEchelon explicites dans le même couple"
            }
            if (mentionsLevel) {
                if (!levelMatches(clause, level!!)) return null
                return "niveau $level explicite"
            }
            if (mentionsEchelon) {
                if (!echelonMatches(clause, echelon!!)) return null
                return "échelon $echelon explicite"
            }
        }

        classification.position?.let { value ->
            if (simpleLabelMatch(clause, "position", value)) return "position ${normalizeToken(value)} explicite"
        }
        classification.group?.let { value ->
            if (simpleLabelMatch(clause, "groupe", value)) return "groupe ${normalizeToken(value)} explicite"
        }
        classification.category?.let { value ->
            if (simpleLabelMatch(clause, "categorie", value)) return "catégorie ${normalizeToken(value)} explicite"
        }
        classification.employment?.let { value ->
            val wanted = OfficialKaliProfileMatcherV2.normalize(value)
            if (Regex("\\b(?:emploi|fonction|poste)\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b").containsMatchIn(clause)) {
                return "emploi ${normalizeToken(value)} explicite"
            }
        }
        return null
    }

    private fun levelEchelonPairMatches(clause: String, wantedLevelRaw: String, wantedEchelonRaw: String): Boolean {
        val wantedEchelon = normalizeToken(wantedEchelonRaw)
        return levelEchelonPairRegex.findAll(clause).any { match ->
            val pairLevel = match.groupValues[1]
            val echelons = match.groupValues[2]
                .split(Regex("\\s*(?:,|/|et|ou)\\s*"))
                .map(::normalizeToken)
                .toSet()
            levelTokensEqual(pairLevel, wantedLevelRaw) && wantedEchelon in echelons
        }
    }

    private fun levelTokensEqual(left: String, right: String): Boolean {
        val leftNumeric = romanOrArabic(left)
        val rightNumeric = romanOrArabic(right)
        return if (leftNumeric != null && rightNumeric != null) {
            leftNumeric == rightNumeric
        } else {
            normalizeToken(left) == normalizeToken(right)
        }
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

    private fun simpleLabelMatch(clause: String, label: String, raw: String): Boolean {
        if (exactLabelMatch(clause, label, raw)) return true
        val wanted = normalizeToken(raw)
        return simpleLabelListRegex(label).findAll(clause).any { match ->
            match.groupValues[1]
                .split(Regex("\\s*(?:,|/|et|ou)\\s*"))
                .map(::normalizeToken)
                .any { it == wanted }
        }
    }

    private fun simpleLabelListRegex(label: String): Regex = Regex(
        "\\b${label}s?\\s*[:.\\-]?\\s*([a-z0-9ivx]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9ivx]+)+)\\b"
    )

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
        when (article.status.trim().uppercase(Locale.ROOT)) {
            "VIGUEUR_ETEN" -> if (article.extensionEffectiveFrom != null) ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED else ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
            "VIGUEUR_NON_ETEN" -> ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED
            else -> ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN
        }

    // VIGUEUR/VIGUEUR_DIFF sont utilisables comme preuve de texte en vigueur, mais jamais comme
    // preuve d'extension : extensionStatus() les maintient volontairement à UNKNOWN.
    private val acceptedStatuses = setOf("VIGUEUR", "VIGUEUR_DIFF", "VIGUEUR_ETEN", "VIGUEUR_NON_ETEN")
    private val kaliArticleIdRegex = Regex("^KALIARTI\\d+$")
    private val article21Regex = Regex("\\barticle\\s*2[.,]1\\b")
    private val article22Regex = Regex("\\barticle\\s*2[.,]2\\b")
    private val extensionWords = listOf(
        "susceptibles de beneficier d'une extension de regime",
        "susceptible de beneficier d'une extension de regime",
        "pouvant etre integres au regime",
        "pouvant etre integrees au regime",
        "extension de regime"
    )

    private val numberRegex = Regex("\\d{1,5}")
    private val coefficientVocabulary = Regex("\\bcoefficients?\\b")
    private val coefficientRangeRegex = Regex("\\b(?:du\\s+)?coefficients?\\s*[:.\\-]?\\s*(\\d{1,5})\\s*(?:a|au|-)\\s*(?:(?:le\\s+)?coefficient\\s*)?(\\d{1,5})\\b")
    private val coefficientListRegex = Regex("\\bcoefficients?\\s*[:.\\-]?\\s*((?:\\d{1,5})(?:\\s*(?:,|/|et|ou)\\s*(?:(?:le\\s+)?coefficients?\\s*)?\\d{1,5})+)\\b")
    private val coefficientExactRegex = Regex("\\bcoefficients?\\s*[:.\\-]?\\s*(\\d{1,5})\\b")

    private val levelVocabulary = Regex("\\bniveaux?\\b")
    private val echelonVocabulary = Regex("\\bechelons?\\b")
    private val levelRangeRegex = Regex("\\bniveaux?\\s*[:.\\-]?\\s*([ivx]+|\\d{1,2})\\s*(?:a|au|-)\\s*([ivx]+|\\d{1,2})\\b")
    private val levelExactRegex = Regex("\\bniveau\\s*[:.\\-]?\\s*([a-z0-9]+)\\b")
    private val echelonExactRegex = Regex("\\bechelon\\s*[:.\\-]?\\s*([a-z0-9]+)\\b")
    private val echelonListRegex = Regex("\\bechelons\\s*[:.\\-]?\\s*([a-z0-9]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9]+)+)")
    private val levelEchelonPairRegex = Regex("\\bniveau\\s*[:.\\-]?\\s*([a-z0-9]+)\\s*(?:[-,/]\\s*)?echelons?\\s*[:.\\-]?\\s*([a-z0-9]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9]+)*)\\b")
}
