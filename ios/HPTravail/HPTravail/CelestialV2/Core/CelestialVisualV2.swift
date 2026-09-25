import Foundation

// Presentation coordinates, NOT a star distance or astronomical measurement.
struct CelestialDomePointV2: Equatable, Sendable {
    let x: Double
    let y: Double
    let depth: Double
}

// Orthographic view of a transparent inclined hemisphere, not a rotating disk.
// Camera inclination is a presentation choice, not a phone sensor measurement.
// No rear-hemisphere cull: this remains an observer-centred 360° map.
enum CelestialDomeV2 {
    static let horizonDegrees = 0.0
    static let cameraElevationDegrees = 35.0
    static let radiusFraction = 0.76

    static func project(azimuthDegrees: Double, apparentAltitudeDegrees: Double,
                        headingDegrees: Double) -> CelestialDomePointV2? {
        guard azimuthDegrees.isFinite, headingDegrees.isFinite,
              apparentAltitudeDegrees.isFinite, (-90...90).contains(apparentAltitudeDegrees) else { return nil }
        let delta = azimuthDegrees.truncatingRemainder(dividingBy: 360) - headingDegrees.truncatingRemainder(dividingBy: 360)
        let a = delta.truncatingRemainder(dividingBy: 360) * .pi / 180
        let h = apparentAltitudeDegrees * .pi / 180
        let tilt = cameraElevationDegrees * .pi / 180
        let east = cos(h) * sin(a)
        let north = cos(h) * cos(a)
        let up = sin(h)
        return CelestialDomePointV2(x: radiusFraction * east,
            y: -radiusFraction * (north * sin(tilt) + up * cos(tilt)),
            depth: up * sin(tilt) - north * cos(tilt))
    }

    static func tangent(east: Double, north: Double, up: Double,
                        headingDegrees: Double) -> CelestialDomePointV2? {
        guard [east, north, up, headingDegrees].allSatisfy({ $0.isFinite }) else { return nil }
        let a = headingDegrees.truncatingRemainder(dividingBy: 360) * .pi / 180
        let tilt = cameraElevationDegrees * .pi / 180
        let x = east * cos(a) - north * sin(a)
        let n = east * sin(a) + north * cos(a)
        let y = -(n * sin(tilt) + up * cos(tilt))
        let length = hypot(x, y)
        guard length.isFinite, length >= 1e-9 else { return nil }
        return CelestialDomePointV2(x: x / length, y: y / length,
            depth: up * sin(tilt) - n * cos(tilt))
    }
}

struct CelestialStarStyleV2: Equatable, Sendable {
    let radius: Double
    let haloRadius: Double
    let coreAlpha: Double
    let haloAlpha: Double
}

// BSC5 apparent magnitude, not an invented distance. All radii, palette, tone
// curve and bounded shimmer are graphical choices, not new observations.
enum CelestialStarAppearanceV2 {
    static let twinkleMaxMagnitude = 2.2
    static let red = 243
    static let green = 247
    static let blue = 255

    static func resolve(magnitude: Double, apparentAltitudeDegrees: Double, starId: Int,
                        elapsedSeconds: Double, visibility: Double, animated: Bool) -> CelestialStarStyleV2? {
        guard magnitude.isFinite, apparentAltitudeDegrees.isFinite,
              (-90...90).contains(apparentAltitudeDegrees), elapsedSeconds.isFinite,
              visibility.isFinite, (0...1).contains(visibility) else { return nil }
        let level = pow(10, -0.128 * (min(8, max(-1.5, magnitude)) + 1.5))
        let radius = 0.32 + 1.25 * pow(level, 0.8)
        let horizon = smooth(0, 2, apparentAltitudeDegrees)
        let phase = Double(((starId % 4093) + 4093) % 4093) / 4093
        let shimmer: Double
        if animated && magnitude <= twinkleMaxMagnitude {
            let t = min(1e12, max(-1e12, elapsedSeconds))
            let signal = 0.65 * sin(t * (1.7 + phase) + phase * 2 * .pi) +
                0.35 * sin(t * (2.9 + phase * 0.7) + phase * 11)
            let amplitude = 0.04 + 0.08 * (1 - smooth(0, 45, apparentAltitudeDegrees))
            shimmer = 1 - amplitude * (0.5 + 0.5 * signal)
        } else { shimmer = 1 }
        let alpha = visibility * horizon * shimmer
        return CelestialStarStyleV2(radius: radius, haloRadius: radius * (2.2 + 2.8 * level),
            coreAlpha: alpha * (0.16 + 0.82 * level),
            haloAlpha: magnitude <= 3 ? alpha * (0.025 + 0.24 * level) : 0)
    }

    private static func smooth(_ a: Double, _ b: Double, _ x: Double) -> Double {
        let t = min(1, max(0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }
}

