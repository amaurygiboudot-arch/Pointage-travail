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
        assertEquals(2, BackgroundImageSampling.calculateInSampleSize(4000, 1200, 1080, 900))
    }

    @Test
    fun invalidDimensionsFallBackToOne() {
        assertEquals(1, BackgroundImageSampling.calculateInSampleSize(0, 4000, 1080, 1920))
    }
}
