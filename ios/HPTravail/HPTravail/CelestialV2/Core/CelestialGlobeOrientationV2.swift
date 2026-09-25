import Foundation

/// Display-only counter-rotation around the Earth's own centre.
/// Input is true-north heading already qualified by CelestialHeadingPolicyV2.
/// GPS centre, geography, lighting, sky and clock hands are not changed.
/// Screen coordinates are +X right / +Y down on both mobile platforms.
enum CelestialGlobeOrientationV2 {
    static func counterRotationDegrees(renderingHeadingDegrees: Double?) -> Double {
        guard let value = renderingHeadingDegrees, value.isFinite else { return 0 }
        let remainder = value.truncatingRemainder(dividingBy: 360)
        let heading = (remainder + 360).truncatingRemainder(dividingBy: 360)
        // Equivalent angles close to north stay close to zero (359 -> +1).
        return heading <= 180 ? -heading : 360 - heading
    }
}
