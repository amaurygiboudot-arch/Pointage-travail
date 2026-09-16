package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Test

class PauseActionPolicyV2Test {
    @Test
    fun `no session blocks pause action`() {
        assertEquals(
            PauseActionPolicyV2.Next.NO_OPEN_SESSION,
            PauseActionPolicyV2.next(hasOpenSession = false, openPauseCount = 0)
        )
    }

    @Test
    fun `new pause requires explicit paid status selection`() {
        assertEquals(
            PauseActionPolicyV2.Next.SELECT_PAID_STATUS,
            PauseActionPolicyV2.next(hasOpenSession = true, openPauseCount = 0)
        )
    }

    @Test
    fun `existing pause is closed without selecting a new status`() {
        assertEquals(
            PauseActionPolicyV2.Next.CLOSE_EXISTING,
            PauseActionPolicyV2.next(hasOpenSession = true, openPauseCount = 1)
        )
    }

    @Test
    fun `multiple open pauses fail closed`() {
        assertEquals(
            PauseActionPolicyV2.Next.INVALID_MULTIPLE_OPEN_PAUSES,
            PauseActionPolicyV2.next(hasOpenSession = true, openPauseCount = 2)
        )
    }
}
