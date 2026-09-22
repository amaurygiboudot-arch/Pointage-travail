import SwiftUI

/// Small, deterministic projection helper shared by the Earth and Moon views.
/// Coordinates use screen axes: x points right, y points down and z points
/// towards the observer.
struct CelestialSphereVectorV2 {
    let x: Double
    let y: Double
    let z: Double

    var horizontalLength: Double {
        hypot(x, y)
    }
}

enum CelestialSphereLightingV2 {
    /// Returns the exact visible part of a sphere that faces away from the Sun.
    /// The boundary combines the projected great-circle terminator and the
    /// appropriate half of the limb. The result stays valid from day to night,
    /// including sunrise and sunset.
    static func nightPath(
        in rect: CGRect,
        sun: CelestialSphereVectorV2,
        samples: Int = 96
    ) -> Path {
        let radius = min(rect.width, rect.height) * 0.5
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let horizontal = sun.horizontalLength

        if horizontal < 1e-8 {
            if sun.z >= 0 {
                return Path()
            }
            return Path(ellipseIn: CGRect(
                x: center.x - radius,
                y: center.y - radius,
                width: radius * 2,
                height: radius * 2
            ))
        }

        let normalizedSun = normalized(sun)
        let hx = -normalizedSun.y / hypot(normalizedSun.x, normalizedSun.y)
        let hy = normalizedSun.x / hypot(normalizedSun.x, normalizedSun.y)

        // Visible point of the terminator closest to the observer.
        let middle = normalized(CelestialSphereVectorV2(
            x: -normalizedSun.z * normalizedSun.x,
            y: -normalizedSun.z * normalizedSun.y,
            z: 1 - normalizedSun.z * normalizedSun.z
        ))

        var path = Path()
        for index in 0...samples {
            let angle = Double(index) / Double(samples) * .pi
            let point = CelestialSphereVectorV2(
                x: hx * cos(angle) + middle.x * sin(angle),
                y: hy * cos(angle) + middle.y * sin(angle),
                z: middle.z * sin(angle)
            )
            let projected = screenPoint(point, center: center, radius: radius)
            if index == 0 {
                path.move(to: projected)
            } else {
                path.addLine(to: projected)
            }
        }

        // Complete the polygon on the night-facing semicircle of the limb.
        let startAngle = atan2(-hy, -hx)
        let positiveMidpoint = startAngle + .pi * 0.5
        let positiveMidpointDot = cos(positiveMidpoint) * normalizedSun.x
            + sin(positiveMidpoint) * normalizedSun.y
        let direction = positiveMidpointDot <= 0 ? 1.0 : -1.0
        for index in 1...samples {
            let angle = startAngle + direction * Double(index) / Double(samples) * .pi
            let point = CelestialSphereVectorV2(x: cos(angle), y: sin(angle), z: 0)
            path.addLine(to: screenPoint(point, center: center, radius: radius))
        }
        path.closeSubpath()
        return path
    }

    static func terminatorPath(
        in rect: CGRect,
        sun: CelestialSphereVectorV2,
        samples: Int = 96
    ) -> Path {
        let radius = min(rect.width, rect.height) * 0.5
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let normalizedSun = normalized(sun)
        let horizontal = normalizedSun.horizontalLength
        guard horizontal >= 1e-8 else { return Path() }

        let hx = -normalizedSun.y / horizontal
        let hy = normalizedSun.x / horizontal
        let middle = normalized(CelestialSphereVectorV2(
            x: -normalizedSun.z * normalizedSun.x,
            y: -normalizedSun.z * normalizedSun.y,
            z: 1 - normalizedSun.z * normalizedSun.z
        ))

        var path = Path()
        for index in 0...samples {
            let angle = Double(index) / Double(samples) * .pi
            let point = CelestialSphereVectorV2(
                x: hx * cos(angle) + middle.x * sin(angle),
                y: hy * cos(angle) + middle.y * sin(angle),
                z: middle.z * sin(angle)
            )
            let projected = screenPoint(point, center: center, radius: radius)
            if index == 0 {
                path.move(to: projected)
            } else {
                path.addLine(to: projected)
            }
        }
        return path
    }

    private static func normalized(_ value: CelestialSphereVectorV2) -> CelestialSphereVectorV2 {
        let length = sqrt(value.x * value.x + value.y * value.y + value.z * value.z)
        guard length > 1e-12 else {
            return CelestialSphereVectorV2(x: 0, y: 0, z: 1)
        }
        return CelestialSphereVectorV2(
            x: value.x / length,
            y: value.y / length,
            z: value.z / length
        )
    }

    private static func screenPoint(
        _ point: CelestialSphereVectorV2,
        center: CGPoint,
        radius: CGFloat
    ) -> CGPoint {
        CGPoint(
            x: center.x + CGFloat(point.x) * radius,
            y: center.y + CGFloat(point.y) * radius
        )
    }
}
