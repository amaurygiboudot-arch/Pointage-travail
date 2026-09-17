package com.amaury.pointage

import com.amaury.pointage.v2.model.SessionStatusV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconSwitcherV2Test {
    @Test
    fun `unreliable runtime preserves the current launcher icon`() {
        assertNull(
            IconSwitcher.resolveV2IconState(
                reliable = false,
                status = SessionStatusV2.OPEN,
                hasOpenPause = false
            )
        )
    }

    @Test
    fun `reliable runtime maps canonical session states`() {
        assertEquals(
            IconSwitcher.IconState.DEFAULT,
            IconSwitcher.resolveV2IconState(true, null, false)
        )
        assertEquals(
            IconSwitcher.IconState.WORKING,
            IconSwitcher.resolveV2IconState(true, SessionStatusV2.OPEN, false)
        )
        assertEquals(
            IconSwitcher.IconState.PAUSED,
            IconSwitcher.resolveV2IconState(true, SessionStatusV2.OPEN, true)
        )
        assertEquals(
            IconSwitcher.IconState.DEFAULT,
            IconSwitcher.resolveV2IconState(true, SessionStatusV2.CLOSED, true)
        )
    }
}
