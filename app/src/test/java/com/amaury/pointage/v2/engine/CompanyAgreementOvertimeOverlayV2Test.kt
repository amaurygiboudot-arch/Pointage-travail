package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyAgreementOvertimeOverlayV2Test {
    private val base=listOf(
        OvertimeTierV2(35*60,43*60,1.25),
        OvertimeTierV2(43*60,null,1.50)
    )

    @Test
    fun verifiedBandOverridesOnlyItsOwnRange() {
        val result=CompanyAgreementOvertimeOverlayV2.apply(
            base,
            listOf(CompanyAgreementOvertimeOverlayV2.Override(35*60,39*60,1.10))
        )

        assertEquals(1,result.appliedCount)
        assertEquals(3,result.tiers.size)
        assertEquals(35*60,result.tiers[0].fromMinutes)
        assertEquals(39*60,result.tiers[0].toMinutes)
        assertEquals(1.10,result.tiers[0].multiplier,0.001)
        assertEquals(39*60,result.tiers[1].fromMinutes)
        assertEquals(43*60,result.tiers[1].toMinutes)
        assertEquals(1.25,result.tiers[1].multiplier,0.001)
        assertEquals(43*60,result.tiers[2].fromMinutes)
        assertEquals(null,result.tiers[2].toMinutes)
    }

    @Test
    fun rateBelowLegalMinimumIsRejected() {
        val result=CompanyAgreementOvertimeOverlayV2.apply(
            base,
            listOf(CompanyAgreementOvertimeOverlayV2.Override(35*60,43*60,1.05))
        )

        assertEquals(0,result.appliedCount)
        assertEquals(base,result.tiers)
        assertTrue(result.warnings.any{it.contains("invalide")})
    }

    @Test
    fun overlappingCompanyBandsAreRejected() {
        val result=CompanyAgreementOvertimeOverlayV2.apply(
            base,
            listOf(
                CompanyAgreementOvertimeOverlayV2.Override(35*60,40*60,1.15),
                CompanyAgreementOvertimeOverlayV2.Override(39*60,43*60,1.20)
            )
        )

        assertEquals(0,result.appliedCount)
        assertEquals(base,result.tiers)
        assertTrue(result.warnings.any{it.contains("chevauchent")})
    }
}
