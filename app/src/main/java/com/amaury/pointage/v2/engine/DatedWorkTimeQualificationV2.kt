package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.Instant
import java.time.ZoneId

/**
 * Entrée stricte du pointage qualifié : les faits viennent de TimeEngineV2,
 * le registre daté prouve uniquement QUELLES sources doivent être consultées.
 * Les règles juridiques et le calcul des montants restent ceux de Salaire V2.
 */
object DatedWorkTimeQualificationV2 {
    const val CONTEXT_WARNING = "Calcul personnalisé : compte, dates ou sujets de règles non qualifiés."
    const val MULTIPLE_RULES_WARNING =
        "Plusieurs règles pendant la session : le temps doit être segmenté avant certification."

    data class Assessment(
        val time: TimeResultV2,
        val selectedRuleEvidence: Map<WorkRuleTopicV2, DatedWorkRuleV2>,
        val warnings: List<String>
    ) {
        val reliable: Boolean get() = time.reliable
        val selectedSourceIds: Map<WorkRuleTopicV2, String>
            get() = selectedRuleEvidence.mapValues { it.value.sourceId }
    }

    fun assess(
        session: WorkSessionV2,
        owner: WorkRuleOwnerV2,
        zoneId: ZoneId,
        requiredTopics: Set<WorkRuleTopicV2>,
        rules: List<DatedWorkRuleV2>,
        sourceReliable: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Assessment {
        val base = DefaultTimeEngineV2.calculate(session, nowMs)
        val start = session.realArrivalMs
        val end = session.realExitMs
        if (!owner.isValid() || requiredTopics.isEmpty() ||
            session.employerId != owner.employerId ||
            start == null || end == null || start <= 0L || end <= start) {
            val warnings = (base.warnings + CONTEXT_WARNING).distinct()
            return Assessment(base.copy(reliable = false, warnings = warnings), emptyMap(), warnings)
        }
        val beginDay = Instant.ofEpochMilli(start).atZone(zoneId).toLocalDate().toEpochDay()
        val endDayExclusive = Instant.ofEpochMilli(end - 1L)
            .atZone(zoneId).toLocalDate().toEpochDay() + 1L
        val warnings = base.warnings.toMutableList()
        val selectedRules = mutableMapOf<WorkRuleTopicV2, DatedWorkRuleV2>()
        var rulesReliable = true
        for (topic in requiredTopics) {
            val resolution = DatedWorkRuleApplicabilityV2.resolvePeriod(
                owner, topic, beginDay, endDayExclusive, rules, sourceReliable
            )
            warnings += resolution.warnings
            if (!resolution.reliable || resolution.requiresSegmentedCalculation ||
                resolution.segments.size != 1) {
                rulesReliable = false
                if (resolution.requiresSegmentedCalculation) warnings += MULTIPLE_RULES_WARNING
                continue
            }
            val record = resolution.segments.single().record
            selectedRules[topic] = record
        }
        val finalWarnings = warnings.distinct()
        return Assessment(
            time = base.copy(
                reliable = base.reliable && rulesReliable && selectedRules.size == requiredTopics.size,
                warnings = finalWarnings
            ),
            selectedRuleEvidence = if (rulesReliable) selectedRules.toMap() else emptyMap(),
            warnings = finalWarnings
        )
    }
}
