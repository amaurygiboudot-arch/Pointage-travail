package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class SalaryPayslipPeriodSelectionV2Test {
    @Test fun selectsOldPeriodsWithoutRollingCutoff() {
        assertEquals(YearMonth.of(2022, 4), SalaryPayslipPeriodSelectionV2.parse("04/2022"))
        assertEquals(YearMonth.of(1981, 2), SalaryPayslipPeriodSelectionV2.parse("02/1981"))
    }
    @Test fun invalidMonthOrAmbiguousFormatDoesNotChangePeriod() {
        listOf("00/2026", "13/2026", "1/2026", "11/26", "2026-11", "11/1969").forEach {
            assertNull(it, SalaryPayslipPeriodSelectionV2.parse(it))
        }
    }
}
