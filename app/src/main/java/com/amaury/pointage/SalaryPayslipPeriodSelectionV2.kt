package com.amaury.pointage

import java.time.YearMonth

/** Report navigation currently uses positive epoch milliseconds. No rolling 36-month cutoff. */
object SalaryPayslipPeriodSelectionV2 {
    fun parse(value: String): YearMonth? {
        val match = Regex("^(\\d{2})/(\\d{4})$").matchEntire(value.trim()) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val year = match.groupValues[2].toIntOrNull()?.takeIf { it in 1971..9999 } ?: return null
        return runCatching { YearMonth.of(year, month) }.getOrNull()
    }
}
