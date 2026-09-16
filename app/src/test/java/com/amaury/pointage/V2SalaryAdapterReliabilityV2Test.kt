package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Régression : un barème provisoire ou une référence incomplète ne doit jamais certifier le brut mensuel. */
class V2SalaryAdapterReliabilityV2Test {
    @Test
    fun provisionalComplementaryRateMakesMonthlyGrossUnreliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = false,
            arbitrationRequired = false,
            arbitrationResolved = false,
            provisionalComplementaryRateUsed = true
        )

        assertFalse(reliable)
    }

    @Test
    fun noComplementaryFallbackKeepsOtherwiseReliableGrossReliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = false,
            arbitrationRequired = false,
            arbitrationResolved = false,
            provisionalComplementaryRateUsed = false
        )

        assertTrue(reliable)
    }

    @Test
    fun provisionalOvertimeRateStillMakesMonthlyGrossUnreliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = true,
            arbitrationRequired = false,
            arbitrationResolved = false,
            provisionalComplementaryRateUsed = false
        )

        assertFalse(reliable)
    }

    @Test
    fun uncoveredGenericOvertimeMakesMonthlyGrossUnreliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = false,
            arbitrationRequired = false,
            arbitrationResolved = false,
            genericOvertimeCoverageReliable = false
        )

        assertFalse(reliable)
    }

    @Test
    fun coveredGenericOvertimeKeepsOtherwiseReliableGrossReliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = false,
            arbitrationRequired = false,
            arbitrationResolved = false,
            genericOvertimeCoverageReliable = true
        )

        assertTrue(reliable)
    }

    @Test
    fun unconfirmedFullTimeRegularReferenceMakesMonthlyGrossUnreliable() {
        val reliable = V2SalaryAdapter.monthlyGrossReliability(
            baseReliable = true,
            provisionalOvertimeRateUsed = false,
            arbitrationRequired = false,
            arbitrationResolved = false,
            fullTimeRegularReferenceReliable = false
        )

        assertFalse(reliable)
    }

    @Test
    fun confirmedWeeklyReferenceWinsForFullTime() {
        val reference = V2SalaryAdapter.resolveFullTimeRegularReference(
            confirmedWeeklyRegularMinutes = 37 * 60,
            overtimeTiers = listOf(ConventionCatalog.OvertimeTier(35.0, null, 1.25)),
            contractualWeeklyMinutes = 39 * 60
        )

        assertEquals(37 * 60, reference.minutes)
        assertTrue(reference.reliable)
    }

    @Test
    fun integratedTierStartIsAnExplicitFullTimeReference() {
        val reference = V2SalaryAdapter.resolveFullTimeRegularReference(
            confirmedWeeklyRegularMinutes = null,
            overtimeTiers = listOf(ConventionCatalog.OvertimeTier(35.0, 43.0, 1.25)),
            contractualWeeklyMinutes = 39 * 60
        )

        assertEquals(35 * 60, reference.minutes)
        assertTrue(reference.reliable)
    }

    @Test
    fun invalidTierCannotBecomeFullTimeRegularReference() {
        val reference = V2SalaryAdapter.resolveFullTimeRegularReference(
            confirmedWeeklyRegularMinutes = null,
            overtimeTiers = listOf(ConventionCatalog.OvertimeTier(35.0, null, 0.5)),
            contractualWeeklyMinutes = 39 * 60
        )

        assertEquals(39 * 60, reference.minutes)
        assertFalse(reference.reliable)
    }

    @Test
    fun overlappingTiersCannotBecomeFullTimeRegularReference() {
        val reference = V2SalaryAdapter.resolveFullTimeRegularReference(
            confirmedWeeklyRegularMinutes = null,
            overtimeTiers = listOf(
                ConventionCatalog.OvertimeTier(35.0, 43.0, 1.25),
                ConventionCatalog.OvertimeTier(40.0, null, 1.50)
            ),
            contractualWeeklyMinutes = 39 * 60
        )

        assertEquals(39 * 60, reference.minutes)
        assertFalse(reference.reliable)
    }

    @Test
    fun missingRuleAndTierFallsBackToContractWithoutInventing35Hours() {
        val reference = V2SalaryAdapter.resolveFullTimeRegularReference(
            confirmedWeeklyRegularMinutes = null,
            overtimeTiers = emptyList(),
            contractualWeeklyMinutes = 39 * 60
        )

        assertEquals(39 * 60, reference.minutes)
        assertFalse(reference.reliable)
    }
}
