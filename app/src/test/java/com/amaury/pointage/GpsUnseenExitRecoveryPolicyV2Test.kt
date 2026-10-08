package com.amaury.pointage

import com.amaury.pointage.v2.engine.GpsTransitionV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsUnseenExitRecoveryPolicyV2Test {
    private fun eligible(
        active: Set<String> = emptySet(),
        pending: Set<String> = emptySet(),
        entryPending: Boolean = false,
        transition: GpsTransitionV2 = GpsTransitionV2.EXIT,
        registered: List<String> = listOf("work-1"),
        triggered: List<String> = listOf("work-1"),
        open: Boolean = true
    ): Boolean = GpsUnseenExitRecoveryPolicyV2.shouldRecover(
        transition, active, pending, entryPending, triggered, registered, open
    )

    @Test fun `unique work EXIT survives an absent previous ENTER`() {
        assertTrue(eligible())
    }

    @Test fun `ambiguous untrusted or duplicate exits remain blocked`() {
        assertFalse(eligible(open = false))
        assertFalse(eligible(transition = GpsTransitionV2.ENTER))
        assertFalse(eligible(active = setOf("work-1")))
        assertFalse(eligible(pending = setOf("work-1")))
        assertFalse(eligible(entryPending = true))
        assertFalse(eligible(registered = listOf("work-1", "work-2")))
        assertFalse(eligible(triggered = listOf("work-2")))
        assertFalse(eligible(triggered = listOf("work-1", "work-2")))
    }
}
