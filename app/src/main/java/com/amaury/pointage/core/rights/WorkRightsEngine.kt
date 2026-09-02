package com.amaury.pointage.core.rights

enum class RuleSourceType {
    LAW,
    COLLECTIVE_AGREEMENT,
    COMPANY_AGREEMENT,
    CONTRACT,
    OTHER
}

data class RuleSource(
    val id: String,
    val type: RuleSourceType,
    val label: String
)

enum class WorkRightType {
    BREAK,
    PAID_BREAK,
    OVERTIME,
    NIGHT_PREMIUM,
    SUNDAY_PREMIUM,
    HOLIDAY_PREMIUM,
    MEAL_ALLOWANCE,
    REST,
    OTHER
}

data class DerivedWorkRight(
    val type: WorkRightType,
    val amountMs: Long? = null,
    val amount: Double? = null,
    val unit: String? = null,
    val source: RuleSource,
    val explanation: String
)

fun interface WorkRightRule {
    fun evaluate(input: WorkRightsInput): List<DerivedWorkRight>
}

/**
 * Exécute uniquement les règles explicitement fournies.
 *
 * Aucune règle légale ou conventionnelle n'est codée en dur ici. Cela évite qu'une
 * hypothèse générique devienne par erreur une règle nationale. Chaque droit dérivé
 * conserve sa source et son explication.
 */
object WorkRightsEngine {
    fun evaluate(
        input: WorkRightsInput,
        rules: List<WorkRightRule>
    ): List<DerivedWorkRight> = rules.flatMap { it.evaluate(input) }
}
