package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphericRefractionV2Test {

    @Test
    fun `horizon apparent est releve par atmosphere standard`() {
        val correction = AtmosphericRefractionV2.correctionDeg(0.0)
        assertTrue(correction in 0.45..0.55)
        assertTrue(AtmosphericRefractionV2.apparentAltitudeDeg(0.0) > 0.45)
    }

    @Test
    fun `refraction diminue rapidement quand astre monte`() {
        val horizon = AtmosphericRefractionV2.correctionDeg(0.0)
        val tenDegrees = AtmosphericRefractionV2.correctionDeg(10.0)
        val fortyFive = AtmosphericRefractionV2.correctionDeg(45.0)

        assertTrue(horizon > tenDegrees)
        assertTrue(tenDegrees > fortyFive)
        assertTrue(fortyFive < 0.03)
    }

    @Test
    fun `astre legerement sous horizon peut avoir centre apparent au dessus`() {
        assertTrue(AtmosphericRefractionV2.apparentAltitudeDeg(-0.5) > 0.0)
    }

    @Test
    fun `zenith ne recoit aucune correction`() {
        assertEquals(0.0, AtmosphericRefractionV2.correctionDeg(90.0), 1e-12)
        assertEquals(90.0, AtmosphericRefractionV2.apparentAltitudeDeg(90.0), 1e-12)
    }

    @Test
    fun `modele ne pretend pas extrapoler tres loin sous horizon`() {
        assertEquals(0.0, AtmosphericRefractionV2.correctionDeg(-5.0), 1e-12)
    }
}
