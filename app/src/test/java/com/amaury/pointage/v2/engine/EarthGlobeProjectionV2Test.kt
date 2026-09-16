package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarthGlobeProjectionV2Test {

    @Test
    fun `position observateur est exactement au centre du globe`() {
        val projected = EarthGlobeProjectionV2.project(
            latitudeDeg = 46.67,
            longitudeDeg = -1.43,
            observerLatitudeDeg = 46.67,
            observerLongitudeDeg = -1.43
        )

        assertEquals(0.0, projected.x, 1e-9)
        assertEquals(0.0, projected.y, 1e-9)
        assertEquals(1.0, projected.depth, 1e-9)
        assertTrue(projected.visible)
    }

    @Test
    fun `japon devient face visible quand observateur est au japon`() {
        val tokyo = EarthGlobeProjectionV2.project(
            latitudeDeg = 35.6762,
            longitudeDeg = 139.6503,
            observerLatitudeDeg = 35.6762,
            observerLongitudeDeg = 139.6503
        )

        assertEquals(0.0, tokyo.x, 1e-9)
        assertEquals(0.0, tokyo.y, 1e-9)
        assertTrue(tokyo.depth > 0.999999)
    }

    @Test
    fun `antipode est cache derriere la sphere`() {
        val antipode = EarthGlobeProjectionV2.project(
            latitudeDeg = -46.67,
            longitudeDeg = 178.57,
            observerLatitudeDeg = 46.67,
            observerLongitudeDeg = -1.43
        )

        assertFalse(antipode.visible)
        assertTrue(antipode.depth < -0.999)
    }

    @Test
    fun `nord local reste vers le haut`() {
        val north = EarthGlobeProjectionV2.project(
            latitudeDeg = 50.0,
            longitudeDeg = 2.0,
            observerLatitudeDeg = 46.0,
            observerLongitudeDeg = 2.0
        )

        assertTrue(north.y < 0.0)
    }

    @Test
    fun `projection inverse retrouve latitude longitude`() {
        val observerLat = 35.6762
        val observerLon = 139.6503
        val originalLat = 34.6937
        val originalLon = 135.5023

        val projected = EarthGlobeProjectionV2.project(
            latitudeDeg = originalLat,
            longitudeDeg = originalLon,
            observerLatitudeDeg = observerLat,
            observerLongitudeDeg = observerLon
        )
        assertTrue(projected.visible)

        val recovered = EarthGlobeProjectionV2.unproject(
            x = projected.x,
            y = projected.y,
            observerLatitudeDeg = observerLat,
            observerLongitudeDeg = observerLon
        )
        assertNotNull(recovered)
        assertEquals(originalLat, recovered!!.latitudeDeg, 1e-6)
        assertEquals(originalLon, recovered.longitudeDeg, 1e-6)
    }
}
