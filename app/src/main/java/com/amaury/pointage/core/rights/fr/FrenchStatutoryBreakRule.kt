package com.amaury.pointage.core.rights.fr

import com.amaury.pointage.core.rights.DerivedWorkRight
import com.amaury.pointage.core.rights.RuleSource
import com.amaury.pointage.core.rights.RuleSourceType
import com.amaury.pointage.core.rights.WorkRightRule
import com.amaury.pointage.core.rights.WorkRightType
import com.amaury.pointage.core.rights.WorkRightsInput

/**
 * Code du travail, article L3121-16 : dès que le temps de travail quotidien atteint
 * six heures, le salarié bénéficie d'au moins vingt minutes consécutives de pause.
 *
 * Cette règle dérive uniquement le droit minimal national. Les accords collectifs ou
 * d'entreprise plus favorables sont fournis séparément au moteur de droits.
 */
object FrenchStatutoryBreakRule : WorkRightRule {
    private const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L
    private const val TWENTY_MINUTES_MS = 20L * 60L * 1000L

    private val source = RuleSource(
        id = "code-travail-L3121-16",
        type = RuleSourceType.LAW,
        label = "Code du travail - article L3121-16"
    )

    override fun evaluate(input: WorkRightsInput): List<DerivedWorkRight> {
        if (input.workedMs < SIX_HOURS_MS) return emptyList()

        return listOf(
            DerivedWorkRight(
                type = WorkRightType.BREAK,
                amountMs = TWENTY_MINUTES_MS,
                source = source,
                explanation = "Temps de travail quotidien d'au moins 6 heures : pause minimale de 20 minutes consécutives"
            )
        )
    }
}
