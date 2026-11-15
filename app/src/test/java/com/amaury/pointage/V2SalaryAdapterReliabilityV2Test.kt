package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Régression : un barème provisoire ou une référence incomplète ne doit jamais certifier le brut mensuel. */
class V2SalaryAdapterReliabilityV2Test {
    @Test
    fun confirmedMayFirstRestoresOnlyDedicatedBlockerAndPreservesOtherFailures() {
        fun base(otherProofs: Boolean, gross: Double = 1500.0) = V2SalaryAdapter.Result(
            regularMs = 0, overtimeTiers = emptyList(), totalWorkedMs = 0, regularGross = gross,
            overtimeGross = 0.0, premiumsGross = 0.0, monthlyEstimatedGross = gross,
            monthlyGrossReliable = false, nightMs = 0, saturdayMs = 0, sundayMs = 0,
            complementaryMinutes = 0, completedSessions = 1, warnings = emptyList(),
            grossBeforeMayFirstReliable = otherProofs)
        val confirmed = com.amaury.pointage.v2.engine.MayFirstPayrollAdjustmentV2.Result(96.0, true)
        val resolved = V2SalaryAdapter.applyConfirmedMayFirstAdjustment(base(true), confirmed)
        assertTrue(resolved.monthlyGrossReliable)
        assertEquals(1596.0, resolved.monthlyEstimatedGross, 0.001)
        assertFalse(V2SalaryAdapter.applyConfirmedMayFirstAdjustment(base(false), confirmed).monthlyGrossReliable)
        assertFalse(V2SalaryAdapter.applyConfirmedMayFirstAdjustment(base(true), confirmed.copy(reliable = false)).monthlyGrossReliable)
        val overflow = V2SalaryAdapter.applyConfirmedMayFirstAdjustment(base(true, Double.MAX_VALUE),
            confirmed.copy(extraGross = Double.MAX_VALUE))
        assertFalse(overflow.monthlyGrossReliable)
        assertTrue(overflow.monthlyEstimatedGross.isFinite())
    }

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
    fun invalidContractRateFallsBackOnlyToFinitePositiveRate() {
        assertEquals(
            13.7,
            V2SalaryAdapter.resolvePositiveHourlyRate(Double.POSITIVE_INFINITY, 13.7)!!,
            0.0
        )
    }

    @Test
    fun invalidContractAndFallbackRatesProduceNoRate() {
        assertNull(
            V2SalaryAdapter.resolvePositiveHourlyRate(Double.NaN, Double.POSITIVE_INFINITY)
        )
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
