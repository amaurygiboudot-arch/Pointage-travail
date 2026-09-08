package com.amaury.pointage.v2

/**
 * Porte de complétude du job générique KALI_PROVIDENT.
 *
 * Le backend signale la matière prévoyance au sens large. Le job ne peut donc être marqué
 * terminé que lorsque catégorie, cotisations ET garanties/prestations ont toutes été auditées.
 */
object ProvidentLegalReanalysisGateV2 {
    data class Component(
        val completed: Boolean,
        val saved: Boolean
    )

    data class Outcome(
        val completed: Boolean,
        val saved: Boolean
    )

    fun resolve(
        category: Component,
        contribution: Component,
        benefits: Component
    ): Outcome = Outcome(
        completed = category.completed && contribution.completed && benefits.completed,
        saved = category.saved || contribution.saved || benefits.saved
    )
}
