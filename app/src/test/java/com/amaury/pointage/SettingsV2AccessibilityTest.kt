package com.amaury.pointage

import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsV2AccessibilityTest {
    @Test
    fun `interactive settings controls keep at least 48dp touch height`() {
        assertTrue(SettingsV2Host.MIN_INTERACTIVE_HEIGHT_DP >= 48)
    }
}
