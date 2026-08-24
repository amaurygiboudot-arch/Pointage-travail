package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundImageSamplingTest {
    @Test
    fun keepsSmallImagesAtFullResolution() {
        assertEquals(1, BackgroundImageSampling.calculateInSampleSize(1080, 1920, 1080, 1920))
    }

    @Test
    fun downsamplesLargeImagesByPowerOfTwo() {
        assertEquals(4, BackgroundImageSampling.calculateInSampleSize(4320, 7680, 1080, 1920))
    }

    @Test
    fun doesNotUndershootEitherTargetDimension() {
        // Sampling this 4000x1200 panorama by 2 would leave only 600 px of height,
        // below the requested 900 px target. Full resolution is therefore correct.
        assertEquals(1, BackgroundImageSampling.calculateInSampleSize(4000, 1200, 1080, 900))
    }

    @Test
    fun invalidDimensionsFallBackToOne() {
        assertEquals(1, BackgroundImageSampling.calculateInSampleSize(0, 4000, 1080, 1920))
    }
}
