package com.amaury.pointage.v2.engine

/**
 * Display-only counter-rotation of the Earth around its own centre.
 * Input is the true-north heading already qualified by CelestialHeadingPolicyV2.
 * Never changes the GPS centre, geography, lighting, sky or clock hands.
 * Screen coordinates are +X right / +Y down on both mobile platforms.
 */
object CelestialGlobeOrientationV2 {
    fun counterRotationDeg(renderingHeadingDeg: Double?): Double {
        if (renderingHeadingDeg == null || !renderingHeadingDeg.isFinite()) return 0.0
        val heading = ((renderingHeadingDeg % 360.0) + 360.0) % 360.0
        // Equivalent angles close to north stay close to zero (359 -> +1).
        return if (heading <= 180.0) -heading else 360.0 - heading
    }
}
