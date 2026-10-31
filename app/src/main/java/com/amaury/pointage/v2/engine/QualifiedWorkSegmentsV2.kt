package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.WorkSegmentKindV2
import com.amaury.pointage.v2.model.WorkSessionV2

/**
 * Work, interventions and confirmed paid travel count toward worked time.
 * On-call standby and personal travel are excluded from worked minutes, but
 * their possible contractual compensation must be calculated separately.
 * Empty breakdown represents an ordinary session; it does not prove that
 * a mixed-activity shift is a continuous period of effective work.
 */
object QualifiedWorkSegmentsV2 {
    data class Resolution(
        val workedIntervals: List<Pair<Long, Long>>,
        val issues: List<String>
    ) {
        val reliable: Boolean get() = issues.isEmpty()
    }

    fun resolve(session: WorkSessionV2, startMs: Long, endMs: Long): Resolution {
        if (startMs <= 0L || endMs <= startMs) {
            return Resolution(emptyList(), listOf("Plage de travail invalide"))
        }
        if (session.workSegments.isEmpty()) return Resolution(listOf(startMs to endMs), emptyList())
        val segments = session.workSegments.sortedBy { it.startMs }
        val worked = mutableListOf<Pair<Long, Long>>()
        val issues = mutableListOf<String>()
        var cursor = startMs
        for (segment in segments) {
            val segmentEnd = segment.endMs
            if (segment.status != DecisionStatusV2.CONFIRMED ||
                segment.kind == WorkSegmentKindV2.TO_CONFIRM ||
                segmentEnd == null || segment.startMs != cursor ||
                segmentEnd <= segment.startMs || segmentEnd > endMs) {
                issues += "Segments d'activité incomplets, contradictoires ou à confirmer"
                break
            }
            when (segment.kind) {
                WorkSegmentKindV2.WORK,
                WorkSegmentKindV2.INTERVENTION,
                WorkSegmentKindV2.PAID_TRAVEL -> worked += segment.startMs to segmentEnd
                WorkSegmentKindV2.ON_CALL ->
                    issues += "Compensation d'astreinte à qualifier séparément du temps d'intervention"
                WorkSegmentKindV2.PERSONAL_TRAVEL,
                WorkSegmentKindV2.NON_WORK -> Unit
                WorkSegmentKindV2.TO_CONFIRM -> Unit
            }
            cursor = segmentEnd
        }
        if (cursor != endMs) issues += "Plage d'activité non couverte par des segments confirmés"
        return Resolution(worked, issues.distinct())
    }
}
