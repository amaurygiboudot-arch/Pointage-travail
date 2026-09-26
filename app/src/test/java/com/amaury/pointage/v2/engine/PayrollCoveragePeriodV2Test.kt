package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PayrollCoveragePeriodV2Test {
    @Test fun september2026IncludesWholeBoundaryWeeks() {
        val p = requireNotNull(PayrollCoveragePeriodV2.forMonth(2026, 9))
        assertEquals(LocalDate.of(2026, 8, 31).toEpochDay(), p.startEpochDay)
        assertEquals(LocalDate.of(2026, 10, 4).toEpochDay(), p.endEpochDay)
    }

    @Test fun mondayToSundayMonthNeedsNoExtraBoundaryDay() {
        val p = requireNotNull(PayrollCoveragePeriodV2.forMonth(2021, 2))
        assertEquals(LocalDate.of(2021, 2, 1).toEpochDay(), p.startEpochDay)
        assertEquals(LocalDate.of(2021, 2, 28).toEpochDay(), p.endEpochDay)
    }

    @Test fun periodCannotCloseBeforeDayAfterItsFinalSunday() {
        val p = requireNotNull(PayrollCoveragePeriodV2.forMonth(2026, 9))
        val zone = ZoneId.of("Europe/Paris")
        val before = LocalDate.of(2026, 10, 4).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val after = LocalDate.of(2026, 10, 5).atStartOfDay(zone).toInstant().toEpochMilli()
        assertFalse(p.isClosedAt(before, zone.id))
        assertTrue(p.isClosedAt(after, zone.id))
    }
}
