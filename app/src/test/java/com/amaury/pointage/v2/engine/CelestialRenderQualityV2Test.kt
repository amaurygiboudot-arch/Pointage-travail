package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CelestialRenderQualityV2Test {
    @Test
    fun criticalConstraintsAlwaysReduceVisualQuality() {
        assertEquals(
            CelestialRenderQualityV2.REDUCED,
            CelestialRenderQualityPolicyV2.resolve(
                lowRamDevice = true,
                powerSaveMode = false,
                thermalSevere = false,
                memoryClassMb = 512
            )
        )
        assertEquals(
            CelestialRenderQualityV2.REDUCED,
            CelestialRenderQualityPolicyV2.resolve(
                lowRamDevice = false,
                powerSaveMode = true,
                thermalSevere = false,
                memoryClassMb = 512
            )
        )
        assertEquals(
            CelestialRenderQualityV2.REDUCED,
            CelestialRenderQualityPolicyV2.resolve(
                lowRamDevice = false,
                powerSaveMode = false,
                thermalSevere = true,
                memoryClassMb = 512
            )
        )
    }

    @Test
    fun memoryTierOnlyChangesPresentationQuality() {
        assertEquals(
            CelestialRenderQualityV2.REDUCED,
            CelestialRenderQualityPolicyV2.resolve(false, false, false, 192)
        )
        assertEquals(
            CelestialRenderQualityV2.BALANCED,
            CelestialRenderQualityPolicyV2.resolve(false, false, false, 320)
        )
        assertEquals(
            CelestialRenderQualityV2.HIGH,
            CelestialRenderQualityPolicyV2.resolve(false, false, false, 512)
        )
    }

    @Test
    fun reducedTierStillKeepsUsableRenderBudgets() {
        assertEquals(640, CelestialRenderQualityV2.REDUCED.maxPanoramaWidthPx)
        assertEquals(1280, CelestialRenderQualityV2.REDUCED.maxPanoramaHeightPx)
        assertEquals(5, CelestialRenderQualityV2.REDUCED.maxCloudClusters)
    }
}
