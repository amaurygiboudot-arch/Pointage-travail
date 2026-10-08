package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Qualification temporelle des sources, distincte des montants et des faits pointés.
 * Un intitulé de métier n'est jamais un propriétaire ni une preuve d'applicabilité.
 */
data class WorkRuleOwnerV2(
    val accountId: String,
    val employeeId: String,
    val employerId: String,
    val contractVersionId: String
) {
    fun isValid(): Boolean = listOf(accountId, employeeId, employerId, contractVersionId)
        .all { it.isNotBlank() && it == it.trim() }
}

enum class WorkRuleTopicV2 {
    TIME_ACCOUNTING, PAUSE_COMPENSATION, NIGHT_WORK, WEEKEND_WORK,
    PUBLIC_HOLIDAY, OVERTIME, ON_CALL, BUSINESS_TRAVEL, MEAL_ALLOWANCE, ABSENCE
}

enum class WorkRuleConfirmationV2 { CONFIRMED, TO_CONFIRM }

/**
 * Référence datée vers la source canonique (contrat, convention, accord, document).
 * Aucune valeur de paie ni règle locale n'est dupliquée dans ce registre.
 * effectiveToEpochDay est EXCLUSIF ; null signifie jusqu'à changement confirmé.
 */
data class DatedWorkRuleV2(
    val id: String,
    val owner: WorkRuleOwnerV2,
    val topic: WorkRuleTopicV2,
    val effectiveFromEpochDay: Long,
    val effectiveToEpochDay: Long?,
    val sourceId: String,
    val ruleReference: String,
    val checkedAtMs: Long,
    val confirmation: WorkRuleConfirmationV2,
    val explicitlyNotApplicable: Boolean = false
)

enum class WorkRuleResolutionStateV2 { CONFIRMED, MISSING, PENDING, CONFLICT, INVALID }

data class WorkRuleDayResolutionV2(
    val state: WorkRuleResolutionStateV2,
    val record: DatedWorkRuleV2?,
    val warnings: List<String>
) {
    val reliable: Boolean get() = state == WorkRuleResolutionStateV2.CONFIRMED && record != null
}

data class WorkRulePeriodSegmentV2(
    val startEpochDay: Long,
    val endExclusiveEpochDay: Long,
    val record: DatedWorkRuleV2
)

data class WorkRulePeriodResolutionV2(
    val segments: List<WorkRulePeriodSegmentV2>,
    val reliable: Boolean,
    val requiresSegmentedCalculation: Boolean,
    val warnings: List<String>
)

/**
 * Déterministe, fail-closed. Seule une source confirmée propre à chaque
 * compte/salarié/employeur/version du contrat et à la bonne date est retenue.
 */
object DatedWorkRuleApplicabilityV2 {
    const val MISSING_WARNING = "Règle individuelle datée absente : qualification à confirmer."
    const val PENDING_WARNING = "Règle individuelle en attente de confirmation."
    const val CONFLICT_WARNING = "Plusieurs règles applicables : conflit non arbitré."
    const val INVALID_WARNING = "Historique des règles incohérent : calcul certifié bloqué."
    const val SEGMENTED_WARNING = "Changement de règle pendant la période : calculer les segments séparément."

    fun validRecord(record: DatedWorkRuleV2): Boolean {
        if (!record.owner.isValid() || record.id.isBlank() || record.id != record.id.trim()) return false
        if (!validDay(record.effectiveFromEpochDay)) return false
        if (record.effectiveToEpochDay != null &&
            (!validDay(record.effectiveToEpochDay) || record.effectiveToEpochDay <= record.effectiveFromEpochDay)
        ) return false
        if (record.confirmation == WorkRuleConfirmationV2.CONFIRMED &&
            (record.sourceId.isBlank() || record.ruleReference.isBlank() ||
                record.checkedAtMs <= 0L || record.sourceId != record.sourceId.trim() ||
                record.ruleReference != record.ruleReference.trim())
        ) return false
        return true
    }

    fun validTimeline(owner: WorkRuleOwnerV2, records: List<DatedWorkRuleV2>): Boolean {
        if (!owner.isValid() || records.any { !validRecord(it) || it.owner != owner }) return false
        if (records.map { it.id }.distinct().size != records.size) return false
        val confirmed = records.filter { it.confirmation == WorkRuleConfirmationV2.CONFIRMED }
        return confirmed.groupBy { it.topic }.values.all { group ->
            val sorted = group.sortedBy { it.effectiveFromEpochDay }
            sorted.zipWithNext().none { (a, b) ->
                b.effectiveFromEpochDay < (a.effectiveToEpochDay ?: Long.MAX_VALUE)
            }
        }
    }

    fun resolveDay(
        owner: WorkRuleOwnerV2,
        topic: WorkRuleTopicV2,
        epochDay: Long,
        records: List<DatedWorkRuleV2>,
        sourceReliable: Boolean
    ): WorkRuleDayResolutionV2 {
        if (!owner.isValid() || !validDay(epochDay) || !sourceReliable ||
            records.any { !validRecord(it) }) {
            return WorkRuleDayResolutionV2(WorkRuleResolutionStateV2.INVALID, null, listOf(INVALID_WARNING))
        }
        val applicable = records.filter {
            it.owner == owner && it.topic == topic &&
                epochDay >= it.effectiveFromEpochDay &&
                (it.effectiveToEpochDay == null || epochDay < it.effectiveToEpochDay)
        }
        if (applicable.isEmpty()) return WorkRuleDayResolutionV2(
            WorkRuleResolutionStateV2.MISSING, null, listOf(MISSING_WARNING)
        )
        if (applicable.any { it.confirmation != WorkRuleConfirmationV2.CONFIRMED }) {
            return WorkRuleDayResolutionV2(WorkRuleResolutionStateV2.PENDING, null, listOf(PENDING_WARNING))
        }
        if (applicable.size != 1) return WorkRuleDayResolutionV2(
            WorkRuleResolutionStateV2.CONFLICT, null, listOf(CONFLICT_WARNING)
        )
        return WorkRuleDayResolutionV2(
            WorkRuleResolutionStateV2.CONFIRMED, applicable.single(), emptyList()
        )
    }

    fun resolvePeriod(
        owner: WorkRuleOwnerV2,
        topic: WorkRuleTopicV2,
        fromEpochDay: Long,
        toExclusiveEpochDay: Long,
        records: List<DatedWorkRuleV2>,
        sourceReliable: Boolean
    ): WorkRulePeriodResolutionV2 {
        // Une année au maximum par appel ; les périodes longues doivent être segmentées en amont.
        if (!validDay(fromEpochDay) || !validDay(toExclusiveEpochDay) ||
            toExclusiveEpochDay <= fromEpochDay || toExclusiveEpochDay - fromEpochDay > 366) {
            return WorkRulePeriodResolutionV2(emptyList(), false, false, listOf(INVALID_WARNING))
        }
        val segments = mutableListOf<WorkRulePeriodSegmentV2>()
        val warnings = mutableListOf<String>()
        var complete = true
        var cursor = fromEpochDay
        while (cursor < toExclusiveEpochDay) {
            val day = resolveDay(owner, topic, cursor, records, sourceReliable)
            val record = day.record
            if (!day.reliable || record == null) {
                complete = false
                warnings += day.warnings
            } else if (segments.lastOrNull()?.record?.id == record.id &&
                segments.last().endExclusiveEpochDay == cursor) {
                val previous = segments.removeAt(segments.lastIndex)
                segments += previous.copy(endExclusiveEpochDay = cursor + 1)
            } else {
                segments += WorkRulePeriodSegmentV2(cursor, cursor + 1, record)
            }
            cursor++
        }
        val changes = segments.map { it.record.id }.distinct().size > 1
        if (changes) warnings += SEGMENTED_WARNING
        return WorkRulePeriodResolutionV2(segments, complete, changes, warnings.distinct())
    }

    private fun validDay(day: Long): Boolean =
        runCatching { LocalDate.ofEpochDay(day) }.isSuccess
}
