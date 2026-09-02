package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.time.YearMonth

/** Période civile mensuelle utilisée par le moteur de paie V2. */
object PayrollPeriodV2 {
    data class Period(
        val start: LocalDate,
        val endInclusive: LocalDate
    ) {
        val referenceDate: LocalDate get() = endInclusive
    }

    fun month(year: Int, zeroBasedMonth: Int): Period {
        val yearMonth = YearMonth.of(year, zeroBasedMonth + 1)
        return Period(
            start = yearMonth.atDay(1),
            endInclusive = yearMonth.atEndOfMonth()
        )
    }
}
