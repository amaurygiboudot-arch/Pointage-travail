package com.amaury.pointage

import org.junit.Assert.assertNull
import org.junit.Test

class ConventionNightRulesV2SafetyTest {
    @Test
    fun `aucun taux de nuit ne vient encore d une table IDCC codee en dur`() {
        assertNull(ConventionNightRules.forIdcc("0292"))
        assertNull(ConventionNightRules.forIdcc("2148"))
        assertNull(ConventionNightRules.forIdcc(null))
    }
}
