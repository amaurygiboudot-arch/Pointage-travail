package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionSicknessMaintenanceBridgeReliabilityTest {
    @Test
    fun `un profil juridique non resolu reste incomplet et non fiable`() {
        val snapshot = V2ConventionSicknessMaintenanceBridge.unresolvedProfileSnapshot(
            "Maintien maladie : profil local non fiable."
        )

        assertFalse(snapshot.result.reliable)
        assertFalse(snapshot.result.eligibilityConfirmed)
        assertFalse(snapshot.result.applicable)
        assertNull(snapshot.result.selectedRule)
        assertNull(snapshot.result.alreadyConsumedIndemnifiedDays)
        assertNull(snapshot.result.currentIndemnifiableDays)
        assertFalse(snapshot.coverage.reliable)
        assertTrue(snapshot.coverage.state == ConventionMatterCoverageV2.State.INCOMPLETE)
        assertTrue(snapshot.result.warnings.any { it.contains("non fiable") })
        assertTrue(snapshot.coverage.warnings.any { it.contains("non fiable") })
    }
}
