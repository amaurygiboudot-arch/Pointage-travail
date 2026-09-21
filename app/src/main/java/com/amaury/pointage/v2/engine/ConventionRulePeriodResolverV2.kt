package com.amaury.pointage.v2.engine

/** Segment de règles conventionnelles confirmé sur une période inclusive. */
data class ConventionRuleCoverageSegmentV2(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val snapshot: ConventionRuleSnapshotV2
)

data class ConventionRuleCoverageV2(
    val idcc: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val segments: List<ConventionRuleCoverageSegmentV2>,
    val fullyCovered: Boolean
) {
    val singleSnapshotForWholePeriod: ConventionRuleSnapshotV2?
        get() = segments.singleOrNull()?.snapshot?.takeIf {
            fullyCovered &&
                segments[0].startEpochDay == periodStartEpochDay &&
                segments[0].endEpochDay == periodEndEpochDay
        }

    val requiresMultipleRuleVersions: Boolean
        get() = fullyCovered && segments.size > 1
}

data class ConventionRulePeriodResolutionV2(
    val idcc: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val sourceReliable: Boolean,
    val coverage: ConventionRuleCoverageV2?,
    val warnings: List<String>
) {
    val calculationSegments: List<ConventionRuleCoverageSegmentV2>
        get() = coverage?.segments.orEmpty().takeIf {
            sourceReliable && coverage?.fullyCovered == true && it.isNotEmpty()
        }.orEmpty()

    val readyForCalculation: Boolean
        get() = calculationSegments.isNotEmpty()

    val requiresMultipleRuleVersions: Boolean
        get() = sourceReliable && coverage?.requiresMultipleRuleVersions == true
}

/**
 * Résout les règles conventionnelles datées sur toute une période, sans fallback vers une version
 * plus récente. Le comportement est volontairement parallèle à EmploymentContractPeriodResolverV2.
 */
object ConventionRulePeriodResolverV2 {
    const val MISSING_IDCC_WARNING =
        "Convention collective : IDCC absent ; aucune règle conventionnelle n'est appliquée automatiquement."
    const val UNRELIABLE_WARNING =
        "Convention collective : historique des règles non fiable ; aucune règle n'est utilisée pour cette période."
    const val INCOMPLETE_WARNING =
        "Convention collective : la période n'est pas entièrement couverte par des règles datées confirmées ; aucun fallback n'est utilisé."
    const val MULTIPLE_WARNING =
        "Convention collective : plusieurs versions de règles couvrent cette période ; le calcul doit respecter leurs segments datés."

    fun resolve(
        idcc: String,
        periodStartEpochDay: Long,
        periodEndEpochDay: Long,
        sourceReliable: Boolean,
        snapshots: List<ConventionRuleSnapshotV2>
    ): ConventionRulePeriodResolutionV2 {
        require(periodEndEpochDay >= periodStartEpochDay) { "Période invalide" }
        val rawIdcc = idcc.trim()
        if (rawIdcc.isBlank()) {
            return unavailable("", periodStartEpochDay, periodEndEpochDay, MISSING_IDCC_WARNING)
        }
        val normalizedIdcc = normalizeIdcc(rawIdcc)

        if (!sourceReliable) {
            return blocked(normalizedIdcc, periodStartEpochDay, periodEndEpochDay, UNRELIABLE_WARNING)
        }

        val history = runCatching { ConventionRuleHistoryV2(snapshots) }.getOrNull()
            ?: return blocked(normalizedIdcc, periodStartEpochDay, periodEndEpochDay, UNRELIABLE_WARNING)

        val segments = history.allVersions(normalizedIdcc)
            .mapNotNull { snapshot ->
                val start = maxOf(snapshot.effectiveFromEpochDay, periodStartEpochDay)
                val end = minOf(snapshot.effectiveToEpochDay ?: periodEndEpochDay, periodEndEpochDay)
                if (start > end) null else ConventionRuleCoverageSegmentV2(start, end, snapshot)
            }
            .sortedWith(compareBy<ConventionRuleCoverageSegmentV2> { it.startEpochDay }
                .thenBy { it.endEpochDay })

        val fullyCovered = coversEveryDay(segments, periodStartEpochDay, periodEndEpochDay)
        val coverage = ConventionRuleCoverageV2(
            idcc = normalizedIdcc,
            periodStartEpochDay = periodStartEpochDay,
            periodEndEpochDay = periodEndEpochDay,
            segments = segments,
            fullyCovered = fullyCovered
        )
        val warnings = when {
            !fullyCovered -> listOf(INCOMPLETE_WARNING)
            segments.size > 1 -> listOf(MULTIPLE_WARNING)
            else -> emptyList()
        }
        return ConventionRulePeriodResolutionV2(
            idcc = normalizedIdcc,
            periodStartEpochDay = periodStartEpochDay,
            periodEndEpochDay = periodEndEpochDay,
            sourceReliable = true,
            coverage = coverage,
            warnings = warnings
        )
    }

    private fun coversEveryDay(
        segments: List<ConventionRuleCoverageSegmentV2>,
        start: Long,
        end: Long
    ): Boolean {
        if (segments.isEmpty()) return false
        var cursor = start
        for (segment in segments) {
            if (segment.startEpochDay != cursor) return false
            if (segment.endEpochDay == end) return true
            if (segment.endEpochDay >= end || segment.endEpochDay == Long.MAX_VALUE) return false
            cursor = segment.endEpochDay + 1L
        }
        return false
    }

    /** Donnée métier absente mais stockage techniquement exploitable. */
    private fun unavailable(
        idcc: String,
        start: Long,
        end: Long,
        warning: String
    ) = ConventionRulePeriodResolutionV2(
        idcc = idcc,
        periodStartEpochDay = start,
        periodEndEpochDay = end,
        sourceReliable = true,
        coverage = null,
        warnings = listOf(warning)
    )

    /** Source ou historique techniquement non fiable. */
    private fun blocked(
        idcc: String,
        start: Long,
        end: Long,
        warning: String
    ) = ConventionRulePeriodResolutionV2(
        idcc = idcc,
        periodStartEpochDay = start,
        periodEndEpochDay = end,
        sourceReliable = false,
        coverage = null,
        warnings = listOf(warning)
    )

    private fun normalizeIdcc(value: String): String = value.trim().padStart(4, '0')
}
