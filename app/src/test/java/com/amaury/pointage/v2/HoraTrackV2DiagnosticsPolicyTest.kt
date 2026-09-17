package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoraTrackV2DiagnosticsPolicyTest {
    @Test
    fun `diagnostics are enabled only for debuggable builds`() {
        assertTrue(HoraTrackV2.diagnosticsEnabled(debuggable = true))
        assertFalse(HoraTrackV2.diagnosticsEnabled(debuggable = false))
    }
}
