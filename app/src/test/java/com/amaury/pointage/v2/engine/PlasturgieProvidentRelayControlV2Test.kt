package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlasturgieProvidentRelayControlV2Test {
    private fun relay(reached:Boolean=true) = PlasturgieProvidentIncapacityV2.Result(
        applicableConvention=true,
        potentiallyCovered=true,
        eligibilityConfirmed=true,
        protectionCategory=PlasturgieProtectionCategoryV2.Category.OUTSIDE_2_1_2_2,
        minimumGrossRate=0.60,
        relayAfterEmployerMaintenance=true,
        earliestContinuousStopDay=91,
        relayReached=reached,
        exactBenefitAmountAvailable=false,
        warnings=emptyList()
    )

    @Test
    fun `controle directement les montants sur une meme periode`() {
        val result=PlasturgieProvidentRelayControlV2.calculate(
            relay(),
            grossTargetAtSixtyPercentAmount=1200.0,
            socialSecurityGrossAmount=700.0,
            observedProvidentGrossAmount=520.0
        )
        assertTrue(result.complete)
        assertEquals(500.0,result.expectedMinimumProvidentGross!!,0.001)
        assertEquals(20.0,result.differenceGross!!,0.001)
        assertEquals(true,result.meetsBranchMinimum)
    }

    @Test
    fun `signale une prestation inferieure au minimum`() {
        val result=PlasturgieProvidentRelayControlV2.calculate(relay(),1200.0,700.0,450.0)
        assertTrue(result.complete)
        assertEquals(false,result.meetsBranchMinimum)
        assertEquals(-50.0,result.differenceGross!!,0.001)
        assertTrue(result.warnings.any{it.contains("inférieure")})
    }

    @Test
    fun `refuse le controle avant le relais`() {
        val result=PlasturgieProvidentRelayControlV2.calculate(relay(false),1200.0,700.0,500.0)
        assertFalse(result.complete)
        assertNull(result.expectedMinimumProvidentGross)
    }

    @Test
    fun `refuse une base incomplete au lieu de la reconstituer`() {
        val result=PlasturgieProvidentRelayControlV2.calculate(relay(),null,700.0,500.0)
        assertFalse(result.complete)
        assertNull(result.meetsBranchMinimum)
    }
}
