package com.amaury.pointage.v2.engine

import java.util.Locale

/**
 * Classification conventionnelle générique.
 *
 * Les conventions françaises n'utilisent pas toutes un coefficient numérique :
 * certaines reposent sur un niveau, un échelon, une position, un groupe, une
 * catégorie ou un emploi repère. Les champs restent donc indépendants et aucun
 * rapprochement approximatif n'est effectué.
 */
data class ConventionClassificationV2(
    val coefficient: Int? = null,
    val level: String? = null,
    val echelon: String? = null,
    val position: String? = null,
    val group: String? = null,
    val category: String? = null,
    val employment: String? = null
) {
    fun isEmpty(): Boolean = coefficient == null &&
        level.isNullOrBlank() &&
        echelon.isNullOrBlank() &&
        position.isNullOrBlank() &&
        group.isNullOrBlank() &&
        category.isNullOrBlank() &&
        employment.isNullOrBlank()

    fun normalized(): ConventionClassificationV2 = copy(
        level = normalizeText(level),
        echelon = normalizeText(echelon),
        position = normalizeText(position),
        group = normalizeText(group),
        category = normalizeText(category),
        employment = normalizeText(employment)
    )

    /**
     * Retourne vrai lorsque tous les critères renseignés dans [selector] sont
     * exactement présents dans cette classification. Un champ absent dans le
     * sélecteur signifie « non discriminant », jamais « valeur par défaut ».
     */
    fun matches(selector: ConventionClassificationV2): Boolean {
        val actual = normalized()
        val wanted = selector.normalized()
        if (wanted.coefficient != null && actual.coefficient != wanted.coefficient) return false
        if (wanted.level != null && actual.level != wanted.level) return false
        if (wanted.echelon != null && actual.echelon != wanted.echelon) return false
        if (wanted.position != null && actual.position != wanted.position) return false
        if (wanted.group != null && actual.group != wanted.group) return false
        if (wanted.category != null && actual.category != wanted.category) return false
        if (wanted.employment != null && actual.employment != wanted.employment) return false
        return true
    }

    fun specificity(): Int = listOfNotNull(
        coefficient,
        normalizeText(level),
        normalizeText(echelon),
        normalizeText(position),
        normalizeText(group),
        normalizeText(category),
        normalizeText(employment)
    ).size

    fun label(): String = buildList {
        coefficient?.let { add("coefficient $it") }
        normalizeText(level)?.let { add("niveau $it") }
        normalizeText(echelon)?.let { add("échelon $it") }
        normalizeText(position)?.let { add("position $it") }
        normalizeText(group)?.let { add("groupe $it") }
        normalizeText(category)?.let { add("catégorie $it") }
        normalizeText(employment)?.let { add("emploi $it") }
    }.joinToString(", ").ifBlank { "classification non précisée" }

    companion object {
        private fun normalizeText(value: String?): String? = value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase(Locale.FRANCE)
    }
}
