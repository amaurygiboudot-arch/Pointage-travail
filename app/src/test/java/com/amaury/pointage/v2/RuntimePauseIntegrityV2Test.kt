package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePauseIntegrityV2Test {
    @Test
    fun `stored paid value wins over caller fallback`() {
        assertTrue(RuntimePauseIntegrityV2.paidForClose(true, true, false) == true)
        assertTrue(RuntimePauseIntegrityV2.paidForClose(true, false, true) == false)
    }

    @Test
    fun `missing paid provenance stays unknown without explicit fallback`() {
        assertNull(RuntimePauseIntegrityV2.paidForClose(false, false, null))
        assertTrue(RuntimePauseIntegrityV2.paidForClose(false, false, true) == true)
        assertTrue(RuntimePauseIntegrityV2.paidForClose(false, true, false) == false)
    }

    @Test
    fun `automatic end only matches the exact system pause instance`() {
        val pause = PauseV2(
            startMs = 10_000L,
            endMs = null,
            paid = true,
            source = EventSourceV2.SYSTEM
        )
        assertTrue(RuntimePauseIntegrityV2.matchesAutomaticPause(pause, 10_000L))
        assertFalse(RuntimePauseIntegrityV2.matchesAutomaticPause(pause, 10_001L))
        assertFalse(RuntimePauseIntegrityV2.matchesAutomaticPause(pause, null))
    }

    @Test
    fun `automatic end never matches manual or already closed pause`() {
        val manual = PauseV2(10_000L, null, false, EventSourceV2.MANUAL)
        val closed = PauseV2(10_000L, 20_000L, true, EventSourceV2.SYSTEM)
        assertFalse(RuntimePauseIntegrityV2.matchesAutomaticPause(manual, 10_000L))
        assertFalse(RuntimePauseIntegrityV2.matchesAutomaticPause(closed, 10_000L))
    }
}
