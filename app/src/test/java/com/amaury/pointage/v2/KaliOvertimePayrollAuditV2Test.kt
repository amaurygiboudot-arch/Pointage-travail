package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliOvertimePayrollAuditV2Test {
    @Test
    fun `detecte les chevauchements de periodes avant stockage`() {
        val jan = LocalDate.of(2026, 1, 1).toEpochDay()
        val june = LocalDate.of(2026, 6, 30).toEpochDay()
        val may = LocalDate.of(2026, 5, 1).toEpochDay()
        val dec = LocalDate.of(2026, 12, 31).toEpochDay()

        assertTrue(KaliOvertimePayrollAuditV2.intervalsOverlap(jan, june, may, dec))
        assertTrue(KaliOvertimePayrollAuditV2.intervalsOverlap(jan, null, may, dec))
        assertFalse(KaliOvertimePayrollAuditV2.intervalsOverlap(jan, june, LocalDate.of(2026, 7, 1).toEpochDay(), dec))
    }
}
