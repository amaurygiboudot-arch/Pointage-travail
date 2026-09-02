package com.amaury.pointage.v2.engine

import java.time.LocalDate

/** Découpe une période de paie aux dates où une règle commence ou cesse de s'appliquer. */
object PayrollPeriodRuleSegmentsV2 {
    data class Segment(
        val start: LocalDate,
        val endInclusive: LocalDate
    )

    fun split(
        period: PayrollPeriodV2.Period,
        rulePeriods: List<Pair<LocalDate, LocalDate?>>
    ): List<Segment> {
        val boundaries = sortedSetOf(period.start, period.endInclusive.plusDays(1))

        rulePeriods.forEach { (effectiveFrom, effectiveTo) ->
            if (effectiveFrom > period.start && effectiveFrom <= period.endInclusive) {
                boundaries += effectiveFrom
            }
            effectiveTo?.plusDays(1)?.let { afterEnd ->
                if (afterEnd > period.start && afterEnd <= period.endInclusive) {
                    boundaries += afterEnd
                }
            }
        }

        val dates = boundaries.toList()
        return dates.zipWithNext { start, nextStart ->
            Segment(start = start, endInclusive = nextStart.minusDays(1))
        }
    }
}
