package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialGlobeModeV2Test {
    @Test
    fun `local remains default and centres user position`() {
        assertEquals(CelestialGlobeModeV2.LOCAL, CelestialGlobeModeV2.fromStored(null))
        val snapshot = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.43,
            timeMs = 1_799_587_200_000L
        )
        val scene = CelestialGlobeSceneResolverV2.resolve(snapshot, CelestialGlobeModeV2.LOCAL)

        assertEquals(snapshot.latitudeDeg, scene.viewLatitudeDeg, 1e-12)
        assertEquals(snapshot.longitudeDeg, scene.viewLongitudeDeg, 1e-12)
    }

    @Test
    fun `world mode centres real terminator instead of user`() {
        val snapshot = DefaultCelestialEngineV2.snapshot(
            latitudeDeg = 46.67,
            longitudeDeg = -1.43,
            timeMs = 1_799_587_200_000L
        )
        val scene = CelestialGlobeSceneResolverV2.resolve(snapshot, CelestialGlobeModeV2.WORLD)
        val subsolarOnWorldView = EarthGlobeProjectionV2.project(
            latitudeDeg = scene.sunLatitudeDeg,
            longitudeDeg = scene.sunLongitudeDeg,
            observerLatitudeDeg = scene.viewLatitudeDeg,
            observerLongitudeDeg = scene.viewLongitudeDeg
        )

        assertTrue(kotlin.math.abs(subsolarOnWorldView.depth) < 1e-9)
        assertEquals(0.0, scene.viewLatitudeDeg, 1e-12)
    }

    @Test
    fun `stored world mode is restored case insensitively`() {
        assertEquals(CelestialGlobeModeV2.WORLD, CelestialGlobeModeV2.fromStored("world"))
        assertEquals(CelestialGlobeModeV2.LOCAL, CelestialGlobeModeV2.fromStored("unknown"))
    }
}
