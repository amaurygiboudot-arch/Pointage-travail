package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CelestialPanoramaGeometryV2Test {
    @Test
    fun horizonAndZenithMapToViewportEdges() {
        val horizon = CelestialPanoramaGeometryV2.normalized(
            LocalStarPositionV2(azimuthDeg = 90.0, geometricAltitudeDeg = 0.0, apparentAltitudeDeg = 0.0)
        )!!
        val zenith = CelestialPanoramaGeometryV2.normalized(
            LocalStarPositionV2(azimuthDeg = 90.0, geometricAltitudeDeg = 90.0, apparentAltitudeDeg = 90.0)
        )!!

        assertEquals(0.25, horizon.x01, 1e-12)
        assertEquals(1.0, horizon.y01, 1e-12)
        assertEquals(0.0, zenith.y01, 1e-12)
    }

    @Test
    fun selectedHeadingIsAlwaysAtScreenCenter() {
        for (heading in listOf(0.0, 45.0, 180.0, 359.0)) {
            assertEquals(
                0.5,
                CelestialPanoramaGeometryV2.screenX01(heading, heading),
                1e-12
            )
        }
    }

    @Test
    fun wrapAroundKeepsNearbyAzimuthsNearbyOnScreen() {
        assertEquals(
            0.4972222222,
            CelestialPanoramaGeometryV2.screenX01(359.0, 0.0),
            1e-8
        )
        assertEquals(
            0.5027777778,
            CelestialPanoramaGeometryV2.screenX01(1.0, 0.0),
            1e-8
        )
    }

    @Test
    fun cacheGeometryMatchesCanonicalPanoramaProjection() {
        val headings = listOf(0.0, 40.0, 180.0, 300.0)
        val positions = listOf(
            LocalStarPositionV2(5.0, 20.0, 20.0),
            LocalStarPositionV2(120.0, 45.0, 45.0),
            LocalStarPositionV2(275.0, 80.0, 80.0)
        )

        for (heading in headings) {
            for (position in positions) {
                val canonical = StarSkyProjectionV2.projectToPanorama(
                    position = position,
                    centerAzimuthDeg = heading
                )!!
                val canonicalX01 = 0.5 + canonical.x * 0.5
                val canonicalY01 = 0.5 + canonical.y * 0.5

                assertEquals(
                    canonicalX01,
                    CelestialPanoramaGeometryV2.screenX01(position.azimuthDeg, heading),
                    1e-12
                )
                assertEquals(
                    canonicalY01,
                    CelestialPanoramaGeometryV2.normalized(position)!!.y01,
                    1e-12
                )
            }
        }
    }

    @Test
    fun belowHorizonIsNotPromotedIntoPanorama() {
        assertNull(
            CelestialPanoramaGeometryV2.normalized(
                LocalStarPositionV2(0.0, -5.0, -5.0)
            )
        )
    }
}
