import Foundation
import SwiftUI

/// Orthographic Earth view whose centre is always the qualified GPS position.
/// It is intentionally vector based so it does not depend on a remote map or
/// texture, and Canvas renders asynchronously without a display link.
struct CelestialGlobeViewV2: View {
    let snapshot: CelestialSnapshotV2
    var showsObserverMarker = true

    var body: some View {
        Canvas(opaque: false, colorMode: .linear, rendersAsynchronously: true) { context, size in
            drawGlobe(context: &context, size: size)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Globe terrestre centré sur la position GPS")
        .accessibilityValue(accessibilityValue)
    }

    private func drawGlobe(context: inout GraphicsContext, size: CGSize) {
        let diameter = min(size.width, size.height)
        guard diameter > 2 else { return }
        let rect = CGRect(
            x: (size.width - diameter) * 0.5,
            y: (size.height - diameter) * 0.5,
            width: diameter,
            height: diameter
        )
        let sphere = Path(ellipseIn: rect)
        let basis = EarthBasis(
            latitudeDegrees: snapshot.latitudeDegrees,
            longitudeDegrees: snapshot.longitudeDegrees
        )

        context.clip(to: sphere)
        context.fill(
            sphere,
            with: .radialGradient(
                Gradient(colors: [
                    Color(red: 0.13, green: 0.57, blue: 0.86),
                    Color(red: 0.025, green: 0.20, blue: 0.40)
                ]),
                center: CGPoint(x: rect.midX - diameter * 0.12, y: rect.midY - diameter * 0.16),
                startRadius: 0,
                endRadius: diameter * 0.62
            )
        )

        drawLand(context: &context, rect: rect, basis: basis)
        drawGraticule(context: &context, rect: rect, basis: basis)

        let sun = localSunVector
        let night = CelestialSphereLightingV2.nightPath(in: rect, sun: sun)
        context.fill(night, with: .color(Color.black.opacity(0.72)))

        let terminator = CelestialSphereLightingV2.terminatorPath(in: rect, sun: sun)
        context.stroke(
            terminator,
            with: .color(Color(red: 0.42, green: 0.66, blue: 0.88).opacity(0.28)),
            lineWidth: max(0.7, diameter * 0.005)
        )

        context.stroke(
            sphere,
            with: .color(.white.opacity(0.70)),
            lineWidth: max(1, diameter * 0.012)
        )

        if showsObserverMarker {
            let markerDiameter = max(7, diameter * 0.065)
            let markerRect = CGRect(
                x: rect.midX - markerDiameter * 0.5,
                y: rect.midY - markerDiameter * 0.5,
                width: markerDiameter,
                height: markerDiameter
            )
            context.fill(Path(ellipseIn: markerRect.insetBy(dx: -3, dy: -3)), with: .color(.white.opacity(0.22)))
            context.fill(Path(ellipseIn: markerRect), with: .color(.red))
            context.stroke(Path(ellipseIn: markerRect), with: .color(.white), lineWidth: 1.5)
        }
    }

    private func drawLand(context: inout GraphicsContext, rect: CGRect, basis: EarthBasis) {
        for polygon in EarthGeographyV2.landPolygons {
            for path in projectedVisiblePaths(polygon, rect: rect, basis: basis, closesPath: true) {
                context.fill(
                    path,
                    with: .linearGradient(
                        Gradient(colors: [
                            Color(red: 0.30, green: 0.64, blue: 0.31),
                            Color(red: 0.13, green: 0.39, blue: 0.20)
                        ]),
                        startPoint: CGPoint(x: rect.minX, y: rect.minY),
                        endPoint: CGPoint(x: rect.maxX, y: rect.maxY)
                    )
                )
                context.stroke(path, with: .color(.white.opacity(0.26)), lineWidth: max(0.4, rect.width * 0.003))
            }
        }
    }

    private func drawGraticule(context: inout GraphicsContext, rect: CGRect, basis: EarthBasis) {
        let shading = GraphicsContext.Shading.color(.white.opacity(0.14))
        let width = max(0.35, rect.width * 0.002)

        for latitude in stride(from: -60.0, through: 60.0, by: 30.0) {
            let points = stride(from: -180.0, through: 180.0, by: 3.0).map {
                EarthCoordinate(latitude: latitude, longitude: $0)
            }
            for path in projectedVisiblePaths(points, rect: rect, basis: basis, closesPath: false) {
                context.stroke(path, with: shading, lineWidth: width)
            }
        }
        for longitude in stride(from: -180.0, to: 180.0, by: 30.0) {
            let points = stride(from: -90.0, through: 90.0, by: 3.0).map {
                EarthCoordinate(latitude: $0, longitude: longitude)
            }
            for path in projectedVisiblePaths(points, rect: rect, basis: basis, closesPath: false) {
                context.stroke(path, with: shading, lineWidth: width)
            }
        }
    }

    private func projectedVisiblePaths(
        _ coordinates: [EarthCoordinate],
        rect: CGRect,
        basis: EarthBasis,
        closesPath: Bool
    ) -> [Path] {
        guard !coordinates.isEmpty else { return [] }
        let radius = rect.width * 0.5
        let center = CGPoint(x: rect.midX, y: rect.midY)
        var result: [Path] = []
        var current = Path()
        var visibleCount = 0

        func finishRun() {
            guard visibleCount >= (closesPath ? 3 : 2) else {
                current = Path()
                visibleCount = 0
                return
            }
            if closesPath { current.closeSubpath() }
            result.append(current)
            current = Path()
            visibleCount = 0
        }

        for coordinate in coordinates {
            let projection = basis.project(coordinate)
            guard projection.z >= 0 else {
                finishRun()
                continue
            }
            let point = CGPoint(
                x: center.x + CGFloat(projection.x) * radius,
                y: center.y + CGFloat(projection.y) * radius
            )
            if visibleCount == 0 {
                current.move(to: point)
            } else {
                current.addLine(to: point)
            }
            visibleCount += 1
        }
        finishRun()
        return result
    }

    private var localSunVector: CelestialSphereVectorV2 {
        let azimuth = snapshot.sun.azimuthDegrees * .pi / 180
        let altitude = snapshot.sun.altitudeDegrees * .pi / 180
        let horizontal = cos(altitude)
        return CelestialSphereVectorV2(
            x: sin(azimuth) * horizontal,
            y: -cos(azimuth) * horizontal,
            z: sin(altitude)
        )
    }

    private var accessibilityValue: String {
        let latitude = abs(snapshot.latitudeDegrees)
        let longitude = abs(snapshot.longitudeDegrees)
        let northSouth = snapshot.latitudeDegrees >= 0 ? "nord" : "sud"
        let eastWest = snapshot.longitudeDegrees >= 0 ? "est" : "ouest"
        let daylight = snapshot.isNight ? "position actuellement du côté nuit" : "position actuellement du côté jour"
        return String(
            format: "Latitude %.2f degrés %@, longitude %.2f degrés %@, %@.",
            latitude,
            northSouth,
            longitude,
            eastWest,
            daylight
        )
    }
}

private struct EarthCoordinate {
    let latitude: Double
    let longitude: Double
}

private struct EarthVector {
    let x: Double
    let y: Double
    let z: Double

    func dot(_ other: EarthVector) -> Double {
        x * other.x + y * other.y + z * other.z
    }
}

private struct EarthProjection {
    let x: Double
    let y: Double
    let z: Double
}

private struct EarthBasis {
    let east: EarthVector
    let north: EarthVector
    let up: EarthVector

    init(latitudeDegrees: Double, longitudeDegrees: Double) {
        let latitude = latitudeDegrees * .pi / 180
        let longitude = longitudeDegrees * .pi / 180
        east = EarthVector(x: -sin(longitude), y: cos(longitude), z: 0)
        north = EarthVector(
            x: -sin(latitude) * cos(longitude),
            y: -sin(latitude) * sin(longitude),
            z: cos(latitude)
        )
        up = EarthVector(
            x: cos(latitude) * cos(longitude),
            y: cos(latitude) * sin(longitude),
            z: sin(latitude)
        )
    }

    func project(_ coordinate: EarthCoordinate) -> EarthProjection {
        let latitude = coordinate.latitude * .pi / 180
        let longitude = coordinate.longitude * .pi / 180
        let point = EarthVector(
            x: cos(latitude) * cos(longitude),
            y: cos(latitude) * sin(longitude),
            z: sin(latitude)
        )
        return EarthProjection(
            x: point.dot(east),
            y: -point.dot(north),
            z: point.dot(up)
        )
    }
}

private enum EarthGeographyV2 {
    // Deliberately simplified coastlines. Their only source of movement is the
    // observer projection; they remain deterministic and available offline.
    static let landPolygons: [[EarthCoordinate]] = [
        // North America
        [
            (-168, 66), (-150, 71), (-136, 69), (-128, 57), (-123, 49), (-124, 39),
            (-117, 32), (-106, 23), (-97, 20), (-90, 19), (-83, 24), (-80, 26),
            (-81, 31), (-75, 36), (-68, 44), (-60, 48), (-55, 53), (-63, 59),
            (-78, 62), (-94, 67), (-115, 70), (-140, 72), (-168, 66)
        ].map { EarthCoordinate(longitude: $0.0, latitude: $0.1) },
        // South America
        [
            (-81, 12), (-72, 12), (-62, 9), (-52, 4), (-46, -5), (-36, -8),
            (-39, -18), (-48, -28), (-53, -35), (-62, -44), (-68, -55),
            (-73, -51), (-75, -39), (-80, -28), (-78, -15), (-81, -2), (-81, 12)
        ].map { EarthCoordinate(longitude: $0.0, latitude: $0.1) },
        // Greenland
        [
            (-73, 60), (-58, 59), (-43, 65), (-22, 70), (-18, 79), (-36, 84),
            (-55, 82), (-68, 75), (-73, 60)
        ].map { EarthCoordinate(longitude: $0.0, latitude: $0.1) },
        // Europe and Asia
        [
            (-10, 36), (-10, 44), (-5, 48), (4, 52), (14, 55), (24, 60),
            (31, 70), (48, 70), (70, 73), (100, 77), (135, 71), (170, 65),
            (180, 58), (165, 50), (150, 45), (140, 35), (126, 34), (121, 23),
            (108, 18), (100, 8), (88, 21), (78, 8), (68, 23), (55, 26),
            (44, 35), (35, 36), (29, 41), (20, 40), (12, 44), (3, 43), (-10, 36)
        ].map { EarthCoordinate(longitude: $0.0, latitude: $0.1) },
        // Africa
        [
            (-17, 36), (3, 37), (12, 34), (25, 32), (34, 28), (42, 12),
            (51, 11), (44, -2), (40, -15), (32, -29), (18, -35), (10, -30),
            (2, -18), (-8, -5), (-16, 14), (-17, 36)
        ].map { EarthCoordinate(longitude: $0.0, latitude: $0.1) },
        // Australia
        [
            (113, -22), (121, -14), (136, -12), (146, -18), (153, -28),
            (146, -39), (132, -43), (116, -35), (113, -22)
        ].map { EarthCoordinate(longitude: $0.0, latitude: $0.1) },
        // Madagascar
        [(49, -12), (51, -17), (49, -26), (44, -25), (44, -17), (49, -12)]
            .map { EarthCoordinate(longitude: $0.0, latitude: $0.1) },
        // Antarctica
        stride(from: -180.0, through: 180.0, by: 12.0)
            .map { EarthCoordinate(latitude: -78 - 5 * cos($0 * .pi / 90), longitude: $0) }
    ]
}

private extension EarthCoordinate {
    init(longitude: Double, latitude: Double) {
        self.init(latitude: latitude, longitude: longitude)
    }
}
