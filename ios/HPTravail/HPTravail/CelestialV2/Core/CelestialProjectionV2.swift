import Foundation

struct CelestialDialPointV2: Equatable, Sendable {
    /// Normalized horizontal offset from dial centre; east/right is positive.
    let x: Double
    /// Normalized vertical offset from dial centre; south/down is positive.
    let y: Double
}

/// Pure 360-degree topocentric dial projection shared by UI and tests.
enum CelestialDialProjectionV2 {
    static let civilHorizonDegrees = -0.833
    static let protectedZenithRadiusFraction = 0.34

    static func project(
        azimuthDegrees: Double,
        altitudeDegrees: Double,
        trueHeadingDegrees: Double
    ) -> CelestialDialPointV2? {
        guard azimuthDegrees.isFinite,
              altitudeDegrees.isFinite,
              trueHeadingDegrees.isFinite,
              altitudeDegrees >= civilHorizonDegrees else {
            return nil
        }

        let relativeAzimuth = (azimuthDegrees - trueHeadingDegrees) * .pi / 180
        let apparentAltitude = AtmosphericRefractionV2.apparentAltitudeDegrees(
            geometricAltitudeDegrees: altitudeDegrees
        )
        let visibleAltitude = min(90, max(0, apparentAltitude))
        let altitudeFraction = visibleAltitude / 90
        let radius = 1 - (1 - protectedZenithRadiusFraction) * altitudeFraction
        return CelestialDialPointV2(
            x: sin(relativeAzimuth) * radius,
            y: -cos(relativeAzimuth) * radius
        )
    }
}


enum CelestialGlobeModeV2: String, Sendable {
    case local
    case world

    static let preferenceKey = "celestial_globe_mode"
}

struct CelestialEarthPointV2: Equatable, Sendable {
    let latitudeDegrees: Double
    let longitudeDegrees: Double
}

struct CelestialGlobeSceneV2: Equatable, Sendable {
    let viewLatitudeDegrees: Double
    let viewLongitudeDegrees: Double
    let sunLatitudeDegrees: Double
    let sunLongitudeDegrees: Double
    let userLatitudeDegrees: Double
    let userLongitudeDegrees: Double
}

struct CelestialGlobeProjectionPointV2: Equatable, Sendable {
    let x: Double
    let y: Double
    let depth: Double

    var isVisible: Bool { depth >= 0 }
}

/// Geometry shared by the iOS globe and tests. World mode deliberately centres
/// the real terminator instead of inventing a decorative day/night split.
enum CelestialGlobeProjectionV2 {
    static func scene(
        snapshot: CelestialSnapshotV2,
        mode: CelestialGlobeModeV2
    ) -> CelestialGlobeSceneV2 {
        let sun = subsolarPoint(snapshot: snapshot)
        return CelestialGlobeSceneV2(
            viewLatitudeDegrees: mode == .local ? snapshot.latitudeDegrees : 0,
            viewLongitudeDegrees: mode == .local
                ? snapshot.longitudeDegrees
                : normalizedLongitude(sun.longitudeDegrees + 90),
            sunLatitudeDegrees: sun.latitudeDegrees,
            sunLongitudeDegrees: sun.longitudeDegrees,
            userLatitudeDegrees: snapshot.latitudeDegrees,
            userLongitudeDegrees: snapshot.longitudeDegrees
        )
    }

    static func project(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        viewLatitudeDegrees: Double,
        viewLongitudeDegrees: Double
    ) -> CelestialGlobeProjectionPointV2 {
        let latitude = latitudeDegrees * .pi / 180
        let viewLatitude = viewLatitudeDegrees * .pi / 180
        let longitudeDelta = normalizedLongitude(longitudeDegrees - viewLongitudeDegrees) * .pi / 180
        let cosLatitude = cos(latitude)
        let x = cosLatitude * sin(longitudeDelta)
        let north = cos(viewLatitude) * sin(latitude)
            - sin(viewLatitude) * cosLatitude * cos(longitudeDelta)
        let depth = sin(viewLatitude) * sin(latitude)
            + cos(viewLatitude) * cosLatitude * cos(longitudeDelta)
        return CelestialGlobeProjectionPointV2(x: x, y: -north, depth: depth)
    }

    private static func subsolarPoint(snapshot: CelestialSnapshotV2) -> CelestialEarthPointV2 {
        let observerLatitude = snapshot.latitudeDegrees * .pi / 180
        let observerLongitude = snapshot.longitudeDegrees * .pi / 180
        let sunAzimuth = snapshot.sun.azimuthDegrees * .pi / 180
        let sunAltitude = snapshot.sun.altitudeDegrees * .pi / 180

        let eastComponent = cos(sunAltitude) * sin(sunAzimuth)
        let northComponent = cos(sunAltitude) * cos(sunAzimuth)
        let upComponent = sin(sunAltitude)

        let eastX = -sin(observerLongitude)
        let eastY = cos(observerLongitude)
        let northX = -sin(observerLatitude) * cos(observerLongitude)
        let northY = -sin(observerLatitude) * sin(observerLongitude)
        let northZ = cos(observerLatitude)
        let upX = cos(observerLatitude) * cos(observerLongitude)
        let upY = cos(observerLatitude) * sin(observerLongitude)
        let upZ = sin(observerLatitude)

        let worldX = eastComponent * eastX + northComponent * northX + upComponent * upX
        let worldY = eastComponent * eastY + northComponent * northY + upComponent * upY
        let worldZ = northComponent * northZ + upComponent * upZ

        return CelestialEarthPointV2(
            latitudeDegrees: asin(min(1, max(-1, worldZ))) * 180 / .pi,
            longitudeDegrees: normalizedLongitude(atan2(worldY, worldX) * 180 / .pi)
        )
    }

    private static func normalizedLongitude(_ value: Double) -> Double {
        var normalized = value.truncatingRemainder(dividingBy: 360)
        if normalized > 180 { normalized -= 360 }
        if normalized <= -180 { normalized += 360 }
        return normalized
    }
}
