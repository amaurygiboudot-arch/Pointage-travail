package com.amaury.pointage.v2.engine

import org.junit.Assert.*
import org.junit.Test

class CelestialCloudTextureV2Test {
    private fun recipe(width: Int = 17, height: Int = 33, octaves: Int = 3,
                       seconds: Double = 1_790_288_568.0, day: Double = 1.0,
                       twilight: Double = 0.0, night: Double = 0.0,
                       cover: Double = 0.5, fog: Boolean = false) = CloudTextureRecipeV2(
        CelestialCloudAtmosphereV2.resolve(cover, cover, 0.2 * cover, 0.4 * cover, fog, 400.0)!!,
        if (fog) CelestialWeatherTypeV2.FOG else CelestialWeatherTypeV2.RAIN,
        width, height, octaves, seconds, day, twilight, night)

    @Test fun invalidRecipesDoNotAllocateOrInventPixels() {
        for (invalid in listOf(recipe(width = 1), recipe(width = 129), recipe(height = 1), recipe(height = 257),
            recipe(octaves = 0), recipe(octaves = 5), recipe(seconds = Double.NaN), recipe(seconds = Double.POSITIVE_INFINITY),
            recipe(day = Double.NaN), recipe(day = -1.0), recipe(day = 2.0), recipe(day = 0.0))) {
            assertNull(CelestialCloudTextureV2.rasterize(invalid))
        }
    }

    @Test fun knownClearSkyIsFullyTransparent() {
        assertTrue(CelestialCloudTextureV2.rasterize(recipe(cover = 0.0))!!.all { it == 0 })
    }

    @Test fun fogStillExistsWithZeroCloudCoverAndStaysLow() {
        val pixels = CelestialCloudTextureV2.rasterize(recipe(cover = 0.0, fog = true))!!
        assertEquals(0, pixels[0] ushr 24)
        assertTrue((pixels[32 * 17] ushr 24) > 0)
    }

    @Test fun nightNeverProducesBrightWhiteClouds() {
        val pixels = CelestialCloudTextureV2.rasterize(recipe(day = 0.0, night = 1.0, cover = 1.0))!!
        assertTrue(pixels.all { ((it ushr 16) and 255) <= 30 && (it and 255) <= 49 })
    }

    @Test fun fullCoverageKeepsInternalLuminanceVariation() {
        val pixels = CelestialCloudTextureV2.rasterize(recipe(width = 128, height = 256, cover = 1.0))!!
        assertTrue(pixels.slice(100 * 128 until 101 * 128).toSet().size > 10)
    }

    @Test fun panoramaSeamAndPixelsAreDeterministicAtEveryDetail() {
        for (detail in 2..4) {
            val request = recipe(octaves = detail)
            val pixels = CelestialCloudTextureV2.rasterize(request)!!
            assertArrayEquals(pixels, CelestialCloudTextureV2.rasterize(request))
            for (y in 0 until 33) assertEquals(pixels[y * 17], pixels[y * 17 + 16])
        }
    }

    @Test fun cancelledWorkStopsAtTheNextRow() {
        var rows = 0
        val result = CelestialCloudTextureV2.rasterize(recipe()) { ++rows == 4 }
        assertNull(result)
        assertEquals(4, rows)
    }

    @Test fun argbGoldenPixelsMatchSwift() {
        val pixels = CelestialCloudTextureV2.rasterize(recipe())!!
        val positions = listOf(0, 16, 100, 280, 500, 560)
        val expected = listOf(0xe098a2adu, 0xe098a2adu, 0x44a8b1bbu, 0x3ca6afb9u, 0xe0959faau, 0xce9ba4b0u).map { it.toInt() }
        assertEquals(expected, positions.map { pixels[it] })
    }
}
