package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlasturgieProvidentIncapacityV2Test {
    private val date = LocalDate.of(2026, 9, 1)
    private fun category(coefficient:Int?) = PlasturgieProtectionCategoryV2.classify("292",date,coefficient)
    private fun maintenance(limit:Int=105,consumed:Int=0,waiting:Int=0) = PlasturgieSicknessMaintenanceV2.Result(
        applicable=true,
        eligibilityConfirmed=true,
        employerWaitingDays=waiting,
        firstRecordedStopOfYear=waiting==0,
        annualLimitDays=limit,
        alreadyConsumedIndemnifiedDays=consumed,
        currentIndemnifiableDays=0,
        bands=emptyList(),
        exactEmployerAmountAvailable=false,
        warnings=emptyList()
    )

    @Test
    fun `moins de trois mois n ouvre pas la garantie de branche`() {
        val result = PlasturgieProvidentIncapacityV2.assessLegacy("0292",2,category(700),null,100)
        assertTrue(result.applicableConvention)
        assertFalse(result.potentiallyCovered)
        assertTrue(result.eligibilityConfirmed)
        assertTrue(result.reliable)
        assertNull(result.minimumGrossRate)
    }

    @Test
    fun `entre trois mois et un an le relais est au 91e jour`() {
        val result = PlasturgieProvidentIncapacityV2.assessLegacy("292",8,category(700),null,91)
        assertTrue(result.potentiallyCovered)
        assertTrue(result.eligibilityConfirmed)
        assertTrue(result.reliable)
        assertEquals(0.60,result.minimumGrossRate!!,0.001)
        assertEquals(91,result.earliestContinuousStopDay)
        assertEquals(true,result.relayReached)
    }

    @Test
    fun `a partir d un an le relais suit le maintien annuel restant`() {
        val result = PlasturgieProvidentIncapacityV2.assessLegacy(
            "292",48,category(700),maintenance(limit=105,consumed=30,waiting=3),79
        )
        assertTrue(result.potentiallyCovered)
        assertTrue(result.reliable)
        assertEquals(79,result.earliestContinuousStopDay)
        assertEquals(true,result.relayReached)
    }

    @Test
    fun `un arret plus court que le maintien restant n atteint pas le relais`() {
        val result = PlasturgieProvidentIncapacityV2.assessLegacy(
            "292",48,category(700),maintenance(limit=105,consumed=0,waiting=0),30
        )
        assertTrue(result.reliable)
        assertEquals(106,result.earliestContinuousStopDay)
        assertEquals(false,result.relayReached)
    }

    @Test
    fun `assimile cadre 830 est exclu du regime hors ANI`() {
        val result = PlasturgieProvidentIncapacityV2.assessLegacy("292",48,category(830),maintenance(),200)
        assertFalse(result.potentiallyCovered)
        assertTrue(result.eligibilityConfirmed)
        assertTrue(result.reliable)
        assertNull(result.minimumGrossRate)
    }

    @Test
    fun `coefficient 800 reste hors ANI mais signale extension cadre possible`() {
        val result = PlasturgieProvidentIncapacityV2.assessLegacy("292",48,category(800),maintenance(),200)
        assertTrue(result.potentiallyCovered)
        assertTrue(result.reliable)
        assertTrue(result.warnings.any { it.contains("extension") })
    }

    @Test
    fun `autre convention ne declenche pas le relais plasturgie`() {
        val other = PlasturgieProtectionCategoryV2.classify("1486",date,700)
        val result = PlasturgieProvidentIncapacityV2.assessLegacy("1486",48,other,null,200)
        assertFalse(result.applicableConvention)
        assertFalse(result.potentiallyCovered)
        assertTrue(result.reliable)
    }

    @Test
    fun `idcc manquant ne devient pas une exclusion fiable`() {
        val result = PlasturgieProvidentIncapacityV2.assess(null,48,category(700),null,200)
        assertFalse(result.applicableConvention)
        assertFalse(result.reliable)
        assertFalse(result.eligibilityConfirmed)
        assertTrue(result.warnings.any { it.contains("IDCC") })
    }

    @Test
    fun `anciennete manquante rend le relais non fiable`() {
        val result = PlasturgieProvidentIncapacityV2.assess("292",null,category(700),null,200)
        assertFalse(result.reliable)
        assertFalse(result.eligibilityConfirmed)
        assertNull(result.relayReached)
    }

    @Test
    fun `apres un an un maintien non resolu bloque la date du relais`() {
        val result = PlasturgieProvidentIncapacityV2.assess("292",48,category(700),null,200)
        assertTrue(result.potentiallyCovered)
        assertTrue(result.eligibilityConfirmed)
        assertFalse(result.reliable)
        assertNull(result.earliestContinuousStopDay)
        assertNull(result.relayReached)
    }
}
