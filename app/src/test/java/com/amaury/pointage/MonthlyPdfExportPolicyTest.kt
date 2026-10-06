package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyPdfExportPolicyTest {
    private val hash = "a".repeat(64)
    @Test fun explicitPeriodRequiresBothValidCalendarFields() {
        assertTrue(MonthlyPdfExportPolicy.validPeriod(2026, 0))
        assertTrue(MonthlyPdfExportPolicy.validPeriod(2026, 11))
        assertFalse(MonthlyPdfExportPolicy.validPeriod(2026, -1))
        assertFalse(MonthlyPdfExportPolicy.validPeriod(2026, 12))
        assertFalse(MonthlyPdfExportPolicy.validPeriod(-1, 0))
    }
    @Test fun authorizedBytesForSameAccountCanExport() {
        assertTrue(MonthlyPdfExportPolicy.allows("alice", "alice", hash, hash))
    }
    @Test fun accountChangeOrMissingAccountCannotExport() {
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "bob", hash, hash))
        assertFalse(MonthlyPdfExportPolicy.allows(null, null, hash, hash))
    }
    @Test fun changedBytesOrLostAuthorizationCannotExport() {
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "alice", hash, "b".repeat(64)))
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "alice", null, hash))
        assertFalse(MonthlyPdfExportPolicy.allows("alice", "alice", "invalid", "invalid"))
    }
}
