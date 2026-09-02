package com.amaury.pointage.core.rights

/**
 * Résout plusieurs règles candidates sans toucher aux faits bruts.
 *
 * Le moteur ne prétend pas déduire seul la hiérarchie juridique : chaque règle
 * fournit une priorité explicite issue du moteur juridique/configuration. À priorité
 * égale, la règle la plus favorable est retenue pour une valeur minimale mesurable.
 */
data class RightRuleCandidate(
    val ruleId: String,
    val source: RightSource,
    val priority: Int,
    val minimumValueMs: Long,
    val explanation: String
) {
    init {
        require(ruleId.isNotBlank())
        require(priority >= 0)
        require(minimumValueMs >= 0L)
        require(explanation.isNotBlank())
    }
}

data class RightRuleResolution(
    val selected: RightRuleCandidate?,
    val considered: List<RightRuleCandidate>
)

object RightRuleResolver {
    fun resolve(candidates: List<RightRuleCandidate>): RightRuleResolution {
        val ordered = candidates.sortedWith(
            compareByDescending<RightRuleCandidate> { it.priority }
                .thenByDescending { it.minimumValueMs }
                .thenBy { it.ruleId }
        )
        return RightRuleResolution(selected = ordered.firstOrNull(), considered = ordered)
    }
}
