package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDiagnosticsGatePolicyTest {
    @Test
    fun `developer mode is unavailable on public builds`() {
        assertFalse(AdminDiagnosticsGate.developerModeAllowed(debugBuild = false))
    }

    @Test
    fun `developer mode remains available on internal builds`() {
        assertTrue(AdminDiagnosticsGate.developerModeAllowed(debugBuild = true))
    }
}
