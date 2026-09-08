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

        // L'extension au régime cadres est plus précise que le simple fait d'être hors 2.1/2.2.
        if (extensionEligibleRegexes.any { it.containsMatchIn(text) }) {
            return setOf(ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE)
        }
        if (outsideAniRegexes.any { it.containsMatchIn(text) }) {
            return setOf(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2)
        }

        return buildSet {
            if (article21Regex.containsMatchIn(text)) add(ProtectionCategoryV2.AniCategory.ARTICLE_2_1)
            if (article22Regex.containsMatchIn(text)) add(ProtectionCategoryV2.AniCategory.ARTICLE_2_2)
        }
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
}
