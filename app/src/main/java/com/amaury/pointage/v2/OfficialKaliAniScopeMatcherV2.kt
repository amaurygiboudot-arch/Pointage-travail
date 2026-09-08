package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ProtectionCategoryV2

/**
 * Vérifie la portée ANI explicitement écrite dans une clause KALI.
 *
 * Absence de vocabulaire ANI = clause générale, donc compatible avec la catégorie déjà vérifiée.
 * Dès qu'une population ANI est nommée, elle doit correspondre sans ambiguïté à la catégorie
 * confirmée du salarié. Un texte mélangeant plusieurs populations reste volontairement bloqué.
 */
object OfficialKaliAniScopeMatcherV2 {
    fun matches(rawText: String, category: ProtectionCategoryV2.AniCategory): Boolean {
        val scopes = explicitScopes(rawText)
        return scopes.isEmpty() || scopes == setOf(category)
    }

    fun hasExplicitScope(rawText: String): Boolean = explicitScopes(rawText).isNotEmpty()

    internal fun explicitScopes(rawText: String): Set<ProtectionCategoryV2.AniCategory> {
        val text = OfficialKaliProfileMatcherV2.normalize(rawText)
        if (text.isBlank()) return emptySet()

        val extensionMatches = extensionEligibleRegexes.flatMap { it.findAll(text).toList() }
        val outsideMatches = outsideAniRegexes.flatMap { it.findAll(text).toList() }

        return buildSet {
            if (extensionMatches.isNotEmpty()) {
                add(ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE)
            }

            // « hors 2.1/2.2 » cite mécaniquement 2.1 et 2.2 : ces références ne sont donc
            // jamais relues comme deux catégories positives. Lorsqu'une extension au régime cadres
            // figure dans la même clause, elle est la portée la plus précise de cette clause.
            outsideMatches.forEach { outside ->
                val belongsToExtensionClause = extensionMatches.any { extension ->
                    sameClause(text, outside.range, extension.range)
                }
                if (!belongsToExtensionClause) {
                    add(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2)
                }
            }

            article21Regex.findAll(text).forEach { match ->
                if (outsideMatches.none { match.range.first >= it.range.first && match.range.last <= it.range.last }) {
                    add(ProtectionCategoryV2.AniCategory.ARTICLE_2_1)
                }
            }
            article22Regex.findAll(text).forEach { match ->
                if (outsideMatches.none { match.range.first >= it.range.first && match.range.last <= it.range.last }) {
                    add(ProtectionCategoryV2.AniCategory.ARTICLE_2_2)
                }
            }
        }
    }

    private fun sameClause(text: String, first: IntRange, second: IntRange): Boolean {
        val left = minOf(first.last, second.last) + 1
        val right = maxOf(first.first, second.first)
        if (right <= left) return true
        if (right - left > MAX_SAME_CLAUSE_DISTANCE) return false
        val between = text.substring(left.coerceAtMost(text.length), right.coerceAtMost(text.length))
        return '.' !in between && ';' !in between
    }

    private val article21Regex = Regex("\\b(?:article|art\\.?)\\s*2[.,]1\\b")
    private val article22Regex = Regex("\\b(?:article|art\\.?)\\s*2[.,]2\\b")

    private val outsideAniRegexes = listOf(
        Regex("\\bne relevant pas (?:des?\\s+)?articles?\\s+2[.,]1(?: et| ni| ou|,)?\\s*2[.,]2\\b"),
        Regex("\\bhors (?:les )?articles?\\s+2[.,]1(?: et| ni| ou|,)?\\s*2[.,]2\\b"),
        Regex("\\bnon[- ]cadres? ne relevant pas (?:des )?articles?\\s+2[.,]1(?: et| ni| ou|,)?\\s*2[.,]2\\b")
    )

    private val extensionEligibleRegexes = listOf(
        Regex("\\bextension (?:du )?regime (?:de )?prevoyance des cadres\\b"),
        Regex("\\bintegres? au regime de protection sociale complementaire des cadres\\b")
    )

    private const val MAX_SAME_CLAUSE_DISTANCE = 240
}
