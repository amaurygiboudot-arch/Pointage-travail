package com.amaury.pointage.v2.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class PayrollCoveragePeriodV2(
    val startEpochDay: Long,
    val endEpochDay: Long
) {
    init { require(endEpochDay >= startEpochDay) }

    companion object {
        fun forMonth(year: Int, monthOneBased: Int): PayrollCoveragePeriodV2? = runCatching {
            val month = YearMonth.of(year, monthOneBased)
            val first = month.atDay(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val last = month.atEndOfMonth()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            PayrollCoveragePeriodV2(first.toEpochDay(), last.toEpochDay())
        }.getOrNull()
    }

    fun isClosedAt(nowMs: Long, timeZoneId: String): Boolean = runCatching {
        val zone = java.time.ZoneId.of(timeZoneId)
        val endExclusiveMs = LocalDate.ofEpochDay(Math.addExact(endEpochDay, 1L))
            .atStartOfDay(zone).toInstant().toEpochMilli()
        nowMs >= endExclusiveMs
    }.getOrDefault(false)
}
