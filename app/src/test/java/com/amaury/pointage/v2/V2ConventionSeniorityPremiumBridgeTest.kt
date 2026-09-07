package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionSeniorityPremiumBridgeTest {
    @Test
    fun `plasturgie cadres are a confirmed no-rule seniority scope`() {
        assertTrue(
            V2ConventionSeniorityPremiumBridge.builtInConfirmedNoRule(
                "0292",
                ConventionClassificationV2(coefficient = 900)
            )
        )
        assertTrue(
            V2ConventionSeniorityPremiumBridge.builtInConfirmedNoRule(
                "292",
                ConventionClassificationV2(coefficient = 940)
            )
        )
    }

    @Test
    fun `plasturgie collaborators and other idcc are not marked no-rule`() {
        assertFalse(
            V2ConventionSeniorityPremiumBridge.builtInConfirmedNoRule(
                "292",
                ConventionClassificationV2(coefficient = 800)
            )
        )
        assertFalse(
            V2ConventionSeniorityPremiumBridge.builtInConfirmedNoRule(
                "1486",
                ConventionClassificationV2(coefficient = 900)
            )
        )
    }
}
