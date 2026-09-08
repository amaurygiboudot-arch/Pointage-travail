package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

/**
 * Extrait une catégorie ANI uniquement lorsque la même clause KALI relie explicitement
 * une catégorie objective à la classification réelle du salarié.
 *
 * Sont acceptées uniquement les expressions déterministes : valeur exacte, liste,
 * intervalle ou borne explicite. Les équivalences de fonctions et rapprochements
 * sémantiques restent volontairement refusés.
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

    private enum class MatchState { NOT_MENTIONED, MATCH, MISMATCH }

    private data class DimensionMatch(
        val state: MatchState,
        val evidence: String? = null
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
            Candidate(category, evidence)
        }.distinct()

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
            extensionEffectiveFrom = if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED) article.extensionEffectiveFrom else null,
            // Un classement conventionnel ANI ne devient jamais fiable à partir du seul texte KALI.
            // La preuve APEC/AGIRC est enrichie séparément.
            approvalRequired = true,
            approvalEvidence = null
        )

        if (!rule.structurallyValid()) {
            return Diagnostic(article.articleId, null, listOf("règle de catégorie ANI structurée incohérente"))
        }

        return Diagnostic(
            article.articleId,
            rule,
            buildList {
                add("classification prouvée : ${candidates.joinToString(" ; ") { it.evidence }}")
                add("catégorie prouvée par KALI : ${category.name}")
                add("agrément APEC/AGIRC : preuve séparée requise avant classement automatique")
                if (auditDate.isBefore(article.effectiveFrom)) {
                    add("règle future à la date d'audit : conservée mais non applicable avant ${article.effectiveFrom}")
                }
                if (article.status.uppercase(Locale.ROOT) == "VIGUEUR_ETEN" && article.extensionEffectiveFrom == null) {
                    add("statut étendu présent mais date exacte d'extension absente ; applicabilité automatique bloquée")
                }
                if (extensionStatus == ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED) {
                    add("texte non étendu : applicabilité à l'entreprise non démontrée")
                }
                if (category == ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE) {
                    add("extension de régime seulement : même après agrément, l'affiliation effective dépend d'un choix formalisé par l'entreprise")
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

        val r242Context = clause.contains("r. 242-1-1") || clause.contains("r 242-1-1") || clause.contains("r242-1-1")
        val extensionLanguage = extensionWords.any(clause::contains)
        val nonAniPopulation = clause.contains("non-cadres") ||
            clause.contains("non cadres") ||
            clause.contains("non-assimiles aux cadres") ||
            clause.contains("non assimiles aux cadres") ||
            clause.contains("agents de maitrise") ||
            clause.contains("techniciens") ||
            clause.contains("employes")
        return if (r242Context && extensionLanguage && nonAniPopulation) {
            ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE
        } else null
    }

    private fun statusCompatible(category: ProtectionCategoryV2.AniCategory, status: String): Boolean = when (category) {
        ProtectionCategoryV2.AniCategory.ARTICLE_2_1 -> status == "CADRE"
        ProtectionCategoryV2.AniCategory.ARTICLE_2_2,
        ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE,
        ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2 -> status == "NON_CADRE"
        ProtectionCategoryV2.AniCategory.TO_CONFIRM,
        ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE -> false
    }

    /**
     * Toute dimension explicitement utilisée par la clause doit correspondre à la fiche.
     * Une dimension non utilisée dans la clause n'est pas exigée.
     */
    private fun classificationEvidence(clause: String, classification: ConventionClassificationV2): String? {
        val checks = listOf(
            coefficientMatch(clause, classification.coefficient),
            levelMatch(clause, classification.level),
            echelonMatch(clause, classification.echelon),
            positionMatch(clause, classification.position),
            simpleLabelMatch(clause, "groupe", "groupes", classification.group),
            classificationCategoryMatch(clause, classification.category),
            employmentMatch(clause, classification.employment)
        )
        if (checks.any { it.state == MatchState.MISMATCH }) return null
        val evidence = checks.mapNotNull { it.evidence }.filter { it.isNotBlank() }
        return evidence.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

    private fun coefficientMatch(clause: String, value: Int?): DimensionMatch {
        if (!coefficientVocabulary.containsMatchIn(clause) && !coefficientPrefixLowerBoundRegex.containsMatchIn(clause)) {
            return DimensionMatch(MatchState.NOT_MENTIONED)
        }
        if (value == null) return DimensionMatch(MatchState.MISMATCH)

        val allowed = mutableListOf<String>()
        var recognized = false

        coefficientRangeRegex.findAll(clause).forEach { match ->
            val from = match.groupValues[1].toIntOrNull() ?: return@forEach
            val to = match.groupValues[2].toIntOrNull() ?: return@forEach
            recognized = true
            val range = minOf(from, to)..maxOf(from, to)
            allowed += "$from-$to"
            if (value in range) return DimensionMatch(MatchState.MATCH, "coefficient $value dans plage $from-$to")
        }
        coefficientLowerBoundRegex.findAll(clause).forEach { match ->
            val min = match.groupValues[1].toIntOrNull() ?: return@forEach
            recognized = true
            allowed += ">=$min"
            if (value >= min) return DimensionMatch(MatchState.MATCH, "coefficient $value >= $min")
        }
        coefficientPrefixLowerBoundRegex.findAll(clause).forEach { match ->
            val min = match.groupValues[1].toIntOrNull() ?: return@forEach
            recognized = true
            allowed += ">=$min"
            if (value >= min) return DimensionMatch(MatchState.MATCH, "coefficient $value >= $min")
        }
        coefficientUpperBoundRegex.findAll(clause).forEach { match ->
            val max = match.groupValues[1].toIntOrNull() ?: return@forEach
            recognized = true
            allowed += "<=$max"
            if (value <= max) return DimensionMatch(MatchState.MATCH, "coefficient $value <= $max")
        }
        coefficientListRegex.findAll(clause).forEach { match ->
            val values = integerList(match.groupValues[1])
            if (values.isNotEmpty()) {
                recognized = true
                allowed += values.joinToString(",")
                if (value in values) return DimensionMatch(MatchState.MATCH, "coefficient $value dans liste explicite")
            }
        }
        coefficientExactRegex.findAll(clause).forEach { match ->
            val exact = match.groupValues[1].toIntOrNull() ?: return@forEach
            recognized = true
            allowed += exact.toString()
            if (value == exact) return DimensionMatch(MatchState.MATCH, "coefficient $value explicite")
        }

        return if (recognized) DimensionMatch(MatchState.MISMATCH, "coefficient $value hors ${allowed.distinct().joinToString("/")}")
        else DimensionMatch(MatchState.MISMATCH)
    }

    private fun levelMatch(clause: String, rawValue: String?): DimensionMatch {
        if (!levelVocabulary.containsMatchIn(clause) && !levelPrefixLowerBoundRegex.containsMatchIn(clause)) {
            return DimensionMatch(MatchState.NOT_MENTIONED)
        }
        val value = rawValue?.let(::romanOrArabic) ?: return DimensionMatch(MatchState.MISMATCH)
        var recognized = false

        levelRangeRegex.findAll(clause).forEach { match ->
            val from = romanOrArabic(match.groupValues[1]) ?: return@forEach
            val to = romanOrArabic(match.groupValues[2]) ?: return@forEach
            recognized = true
            if (value in minOf(from, to)..maxOf(from, to)) {
                return DimensionMatch(MatchState.MATCH, "niveau ${normalizeToken(rawValue)} dans plage explicite")
            }
        }
        levelLowerBoundRegex.findAll(clause).forEach { match ->
            val min = romanOrArabic(match.groupValues[1]) ?: return@forEach
            recognized = true
            if (value >= min) return DimensionMatch(MatchState.MATCH, "niveau ${normalizeToken(rawValue)} >= ${match.groupValues[1].uppercase()}")
        }
        levelPrefixLowerBoundRegex.findAll(clause).forEach { match ->
            val min = romanOrArabic(match.groupValues[1]) ?: return@forEach
            recognized = true
            if (value >= min) return DimensionMatch(MatchState.MATCH, "niveau ${normalizeToken(rawValue)} >= ${match.groupValues[1].uppercase()}")
        }
        levelListRegex.findAll(clause).forEach { match ->
            val values = tokenList(match.groupValues[1]).mapNotNull(::romanOrArabic).toSet()
            if (values.isNotEmpty()) {
                recognized = true
                if (value in values) return DimensionMatch(MatchState.MATCH, "niveau ${normalizeToken(rawValue)} dans liste explicite")
            }
        }
        levelExactRegex.findAll(clause).forEach { match ->
            val exact = romanOrArabic(match.groupValues[1]) ?: return@forEach
            recognized = true
            if (value == exact) return DimensionMatch(MatchState.MATCH, "niveau ${normalizeToken(rawValue)} explicite")
        }
        return if (recognized) DimensionMatch(MatchState.MISMATCH) else DimensionMatch(MatchState.MISMATCH)
    }

    private fun echelonMatch(clause: String, rawValue: String?): DimensionMatch {
        if (!echelonVocabulary.containsMatchIn(clause)) return DimensionMatch(MatchState.NOT_MENTIONED)
        val value = rawValue?.let(::normalizeToken) ?: return DimensionMatch(MatchState.MISMATCH)
        var recognized = false

        echelonRangeRegex.findAll(clause).forEach { match ->
            val from = orderedToken(match.groupValues[1]) ?: return@forEach
            val to = orderedToken(match.groupValues[2]) ?: return@forEach
            val wanted = orderedToken(value) ?: return@forEach
            recognized = true
            if (wanted in minOf(from, to)..maxOf(from, to)) {
                return DimensionMatch(MatchState.MATCH, "échelon $value dans plage explicite")
            }
        }
        echelonListRegex.findAll(clause).forEach { match ->
            val values = tokenList(match.groupValues[1]).map(::normalizeToken).toSet()
            if (values.isNotEmpty()) {
                recognized = true
                if (value in values) return DimensionMatch(MatchState.MATCH, "échelon $value dans liste explicite")
            }
        }
        echelonExactRegex.findAll(clause).forEach { match ->
            recognized = true
            if (value == normalizeToken(match.groupValues[1])) {
                return DimensionMatch(MatchState.MATCH, "échelon $value explicite")
            }
        }
        return if (recognized) DimensionMatch(MatchState.MISMATCH) else DimensionMatch(MatchState.MISMATCH)
    }

    private fun positionMatch(clause: String, rawValue: String?): DimensionMatch {
        if (!positionVocabulary.containsMatchIn(clause)) return DimensionMatch(MatchState.NOT_MENTIONED)
        val value = rawValue?.let(::normalizeToken) ?: return DimensionMatch(MatchState.MISMATCH)
        var recognized = false
        val numericValue = value.replace(',', '.').toBigDecimalOrNull()

        positionRangeRegex.findAll(clause).forEach { match ->
            val from = match.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return@forEach
            val to = match.groupValues[2].replace(',', '.').toBigDecimalOrNull() ?: return@forEach
            recognized = true
            if (numericValue != null && numericValue >= minOf(from, to) && numericValue <= maxOf(from, to)) {
                return DimensionMatch(MatchState.MATCH, "position $value dans plage explicite")
            }
        }
        positionListRegex.findAll(clause).forEach { match ->
            val values = tokenList(match.groupValues[1]).map { normalizeToken(it).replace(',', '.') }.toSet()
            if (values.isNotEmpty()) {
                recognized = true
                if (value.replace(',', '.') in values) return DimensionMatch(MatchState.MATCH, "position $value dans liste explicite")
            }
        }
        positionExactRegex.findAll(clause).forEach { match ->
            recognized = true
            if (value == normalizeToken(match.groupValues[1])) return DimensionMatch(MatchState.MATCH, "position $value explicite")
        }
        return if (recognized) DimensionMatch(MatchState.MISMATCH) else DimensionMatch(MatchState.MISMATCH)
    }

    private fun simpleLabelMatch(
        clause: String,
        singular: String,
        plural: String,
        rawValue: String?
    ): DimensionMatch {
        val exactRegex = Regex("\\b$singular\\s*[:.\\-]?\\s*([a-z0-9.]+)\\b")
        val listRegex = Regex("\\b$plural\\s*[:.\\-]?\\s*([a-z0-9.]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9.]+)+)")
        val mentions = exactRegex.containsMatchIn(clause) || listRegex.containsMatchIn(clause)
        if (!mentions) return DimensionMatch(MatchState.NOT_MENTIONED)
        val value = rawValue?.let(::normalizeToken) ?: return DimensionMatch(MatchState.MISMATCH)
        listRegex.findAll(clause).forEach { match ->
            val values = tokenList(match.groupValues[1]).map(::normalizeToken).toSet()
            if (value in values) return DimensionMatch(MatchState.MATCH, "$singular $value dans liste explicite")
        }
        exactRegex.findAll(clause).forEach { match ->
            if (value == normalizeToken(match.groupValues[1])) return DimensionMatch(MatchState.MATCH, "$singular $value explicite")
        }
        return DimensionMatch(MatchState.MISMATCH)
    }

    /** "catégorie des cadres" n'est pas une catégorie de classification : seules les valeurs codées sont discriminantes. */
    private fun classificationCategoryMatch(clause: String, rawValue: String?): DimensionMatch {
        val exactRegex = Regex("\\bcategorie\\s+(?:professionnelle\\s+)?[:.\\-]?\\s*([a-z0-9.]{1,8})\\b")
        val listRegex = Regex("\\bcategories\\s+(?:professionnelles\\s+)?[:.\\-]?\\s*([a-z0-9.]{1,8}(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9.]{1,8})+)")
        val recognized = (exactRegex.findAll(clause).map { it.groupValues[1] } + listRegex.findAll(clause).map { it.groupValues[1] })
            .filterNot { normalizeToken(it) in setOf("CADRE", "CADRES", "OBJECTIVE", "OBJECTIVES") }
            .toList()
        if (recognized.isEmpty()) return DimensionMatch(MatchState.NOT_MENTIONED)
        val value = rawValue?.let(::normalizeToken) ?: return DimensionMatch(MatchState.MISMATCH)
        if (recognized.any { token -> value in tokenList(token).map(::normalizeToken) }) {
            return DimensionMatch(MatchState.MATCH, "catégorie $value explicite")
        }
        return DimensionMatch(MatchState.MISMATCH)
    }

    private fun employmentMatch(clause: String, rawValue: String?): DimensionMatch {
        val value = rawValue ?: return DimensionMatch(MatchState.NOT_MENTIONED)
        val wanted = OfficialKaliProfileMatcherV2.normalize(value)
        val exact = Regex("\\b(?:emploi|fonction|poste)\\s*[:.\\-]?\\s*${Regex.escape(wanted)}\\b")
        return if (exact.containsMatchIn(clause)) {
            DimensionMatch(MatchState.MATCH, "emploi ${normalizeToken(value)} explicite")
        } else {
            // Le simple mot emploi/fonction dans une phrase générale n'est pas un critère de classement.
            DimensionMatch(MatchState.NOT_MENTIONED)
        }
    }

    private fun integerList(raw: String): Set<Int> = tokenList(raw).mapNotNull { it.toIntOrNull() }.toSet()

    private fun tokenList(raw: String): List<String> = raw
        .split(Regex("\\s*(?:,|/|et|ou)\\s*"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun orderedToken(raw: String): Int? {
        val token = normalizeToken(raw)
        token.toIntOrNull()?.let { return it }
        if (token.length == 1 && token[0] in 'A'..'Z') return token[0].code - 'A'.code + 1
        return romanOrArabic(token)
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
            "XIII" -> 13
            "XIV" -> 14
            "XV" -> 15
            else -> null
        }
    }

    private fun normalizeToken(raw: String): String = OfficialKaliProfileMatcherV2.normalize(raw).uppercase(Locale.FRANCE)

    private fun minOf(a: BigDecimal, b: BigDecimal): BigDecimal = if (a <= b) a else b
    private fun maxOf(a: BigDecimal, b: BigDecimal): BigDecimal = if (a >= b) a else b

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
        "peuvent etre integres a la categorie des cadres",
        "peuvent etre integrees a la categorie des cadres",
        "pouvant etre integres a la categorie des cadres",
        "pouvant etre integrees a la categorie des cadres",
        "assimiles a la categorie de cadres en vue de la constitution d'une categorie objective",
        "assimilees a la categorie de cadres en vue de la constitution d'une categorie objective",
        "extension de regime"
    )

    private val coefficientVocabulary = Regex("\\bcoef(?:ficients?)?\\b|\\bcoefficients?\\b")
    private val coefficientRangeRegex = Regex("\\b(?:du\\s+)?coefficients?\\s*[:.\\-]?\\s*(\\d{2,4})\\s*(?:a|au|-)\\s*(?:coefficient\\s*)?(\\d{2,4})\\b")
    private val coefficientLowerBoundRegex = Regex("\\bcoefficients?\\s*(?:est\\s*)?(?:(?:egal|superieur)\\s+ou\\s+(?:superieur|egal)\\s+a|au\\s+moins\\s+egal\\s+a|au\\s+moins\\s+a|>=|≥)\\s*(\\d{2,4})\\b|\\bcoefficients?\\s*[:.\\-]?\\s*(\\d{2,4})\\s*(?:et|ou)\\s+plus\\b").let(::normalizeSecondCaptureRegex)
    private val coefficientPrefixLowerBoundRegex = Regex("\\ba\\s+partir\\s+du\\s+coefficient\\s+(\\d{2,4})\\b")
    private val coefficientUpperBoundRegex = Regex("\\bcoefficients?\\s*(?:est\\s*)?(?:(?:egal|inferieur)\\s+ou\\s+(?:inferieur|egal)\\s+a|au\\s+plus\\s+egal\\s+a|au\\s+plus\\s+a|<=|≤)\\s*(\\d{2,4})\\b")
    private val coefficientListRegex = Regex("\\bcoefficients\\s*[:.\\-]?\\s*((?:\\d{2,4}\\s*(?:,|/|et|ou)\\s*)+\\d{2,4})\\b")
    private val coefficientExactRegex = Regex("\\bcoefficient\\s*[:.\\-]?\\s*(\\d{2,4})\\b")

    private val levelVocabulary = Regex("\\bniveaux?\\b")
    private val levelRangeRegex = Regex("\\bniveaux?\\s*[:.\\-]?\\s*([ivx]+|\\d{1,2})\\s*(?:a|au|-)\\s*([ivx]+|\\d{1,2})\\b")
    private val levelLowerBoundRegex = Regex("\\bniveau\\s*[:.\\-]?\\s*([ivx]+|\\d{1,2})\\s*(?:(?:et|ou)\\s+(?:au-dela|plus)|ou\\s+superieur)\\b|\\bniveau\\s*(?:egal\\s+ou\\s+superieur\\s+a|superieur\\s+ou\\s+egal\\s+a|au\\s+moins\\s+egal\\s+a)\\s*([ivx]+|\\d{1,2})\\b").let(::normalizeSecondCaptureRegex)
    private val levelPrefixLowerBoundRegex = Regex("\\ba\\s+partir\\s+du\\s+niveau\\s+([ivx]+|\\d{1,2})\\b")
    private val levelListRegex = Regex("\\bniveaux\\s*[:.\\-]?\\s*(([ivx]+|\\d{1,2})(?:\\s*(?:,|/|et|ou)\\s*([ivx]+|\\d{1,2}))+)")
    private val levelExactRegex = Regex("\\bniveau\\s*[:.\\-]?\\s*([ivx]+|\\d{1,2})\\b")

    private val echelonVocabulary = Regex("\\bechelons?\\b")
    private val echelonRangeRegex = Regex("\\bechelons?\\s*[:.\\-]?\\s*([a-z0-9]+)\\s*(?:a|au|-)\\s*([a-z0-9]+)\\b")
    private val echelonListRegex = Regex("\\bechelons\\s*[:.\\-]?\\s*([a-z0-9]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9]+)+)")
    private val echelonExactRegex = Regex("\\bechelon\\s*[:.\\-]?\\s*([a-z0-9]+)\\b")

    private val positionVocabulary = Regex("\\bpositions?\\b")
    private val positionRangeRegex = Regex("\\bpositions?\\s*[:.\\-]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:a|au|-)\\s*(\\d+(?:[.,]\\d+)?)\\b")
    private val positionListRegex = Regex("\\bpositions\\s*[:.\\-]?\\s*([a-z0-9.,]+(?:\\s*(?:,|/|et|ou)\\s*[a-z0-9.,]+)+)")
    private val positionExactRegex = Regex("\\bposition\\s*[:.\\-]?\\s*([a-z0-9.,]+)\\b")

    /**
     * Kotlin Regex ne propose pas de branche conditionnelle pour choisir le groupe non vide.
     * Cette normalisation transforme les regex à deux alternatives utilisées ci-dessus en
     * regex dont le groupe 1 contient toujours la valeur utile.
     */
    private fun normalizeSecondCaptureRegex(regex: Regex): Regex = regex
}
