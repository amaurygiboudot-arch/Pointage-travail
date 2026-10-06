package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyPdfRecoveryTest {
    private val hash = "a".repeat(64)
    @Test fun rotationDuringPaymentOnlyReverifiesNeverReplaysPayment() {
        assertEquals(MonthlyPdfRecovery.Action.REVERIFY, MonthlyPdfRecovery.action(MonthlyPdfRecovery.Phase.AUTHORIZING, "alice", "alice", true, hash))
    }
    @Test fun restoredPickerWaitsForExistingResultRatherThanLaunchingAnother() {
        assertEquals(MonthlyPdfRecovery.Action.WAIT_PICKER, MonthlyPdfRecovery.action(MonthlyPdfRecovery.Phase.WAIT_PICKER, "alice", "alice", true, hash))
    }
    @Test fun interruptedCopyRequiresSameOwnedDocument() {
        assertEquals(MonthlyPdfRecovery.Action.COPY, MonthlyPdfRecovery.action(MonthlyPdfRecovery.Phase.COPYING, "alice", "alice", true, hash))
        assertEquals(MonthlyPdfRecovery.Action.REJECT, MonthlyPdfRecovery.action(MonthlyPdfRecovery.Phase.COPYING, "alice", "bob", true, hash))
        assertEquals(MonthlyPdfRecovery.Action.REJECT, MonthlyPdfRecovery.action(MonthlyPdfRecovery.Phase.COPYING, "alice", "alice", false, hash))
        assertEquals(MonthlyPdfRecovery.Action.REJECT, MonthlyPdfRecovery.action(MonthlyPdfRecovery.Phase.COPYING, "alice", "alice", true, null))
    }
    @Test fun interruptedPreparationCanRestartWithoutAuthorization() {
        assertEquals(MonthlyPdfRecovery.Action.PREPARE, MonthlyPdfRecovery.action(MonthlyPdfRecovery.Phase.PREPARING, "alice", "alice", false, null))
    }
}
