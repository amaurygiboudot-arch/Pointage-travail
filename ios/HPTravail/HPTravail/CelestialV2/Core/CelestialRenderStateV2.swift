import Foundation

enum CelestialRenderWarningV2: Hashable, Sendable {
    case weatherUnavailable
    case ambientLightUnavailable
    case ambientLightStale
    case orientationUnqualified
}

struct CelestialRenderStateV2: Equatable, Sendable {
    let date: Date
    let latitudeDegrees: Double
    let longitudeDegrees: Double
    let sunAzimuthDegrees: Double
    let sunAltitudeDegrees: Double
    let moonAzimuthDegrees: Double
    let moonAltitudeDegrees: Double
    let moonIlluminatedFraction: Double
    let solarLightLevel: Double
    let twilightLevel: Double
    let nightLevel: Double
    let cloudCoverage: Double?
    let weatherType: CelestialWeatherTypeV2
    let weatherAge: TimeInterval?
    let ambientLux: Double?
    let ambientLightQuality: CelestialAmbientLightQualityV2
    let ambientLightAge: TimeInterval?
    let starsVisibility: Double
    let constellationsVisibility: Double
    let sunVisibility: Double
    let moonVisibility: Double
    let atmosphereOpacity: Double
    let orientationQuality: CelestialHeadingQualityV2
    let warnings: Set<CelestialRenderWarningV2>
}

enum CelestialRenderStateFactoryV2 {
    static func build(
        snapshot: CelestialSnapshotV2,
        weather: CelestialWeatherStateV2?,
        ambient: CelestialAmbientLightStateV2,
        orientationQuality: CelestialHeadingQualityV2,
        now: Date = Date()
    ) -> CelestialRenderStateV2 {
        let atmosphere = CelestialAtmosphereV2.resolve(
            snapshot: snapshot,
            weather: weather,
            ambient: ambient
        )
        var warnings: Set<CelestialRenderWarningV2> = []
        if weather == nil { warnings.insert(.weatherUnavailable) }
        switch ambient.quality {
        case .unavailable, .invalid:
            warnings.insert(.ambientLightUnavailable)
        case .stale:
            warnings.insert(.ambientLightStale)
        case .valid:
            break
        }
        if !CelestialHeadingPolicyV2.isUsable(orientationQuality) {
            warnings.insert(.orientationUnqualified)
        }

        return CelestialRenderStateV2(
            date: snapshot.date,
            latitudeDegrees: snapshot.latitudeDegrees,
            longitudeDegrees: snapshot.longitudeDegrees,
            sunAzimuthDegrees: snapshot.sun.azimuthDegrees,
            sunAltitudeDegrees: snapshot.sun.altitudeDegrees,
            moonAzimuthDegrees: snapshot.moon.azimuthDegrees,
            moonAltitudeDegrees: snapshot.moon.altitudeDegrees,
            moonIlluminatedFraction: snapshot.moonPhase.illuminatedFraction,
            solarLightLevel: atmosphere.solarLightLevel,
            twilightLevel: atmosphere.twilightLevel,
            nightLevel: atmosphere.nightLevel,
            cloudCoverage: atmosphere.cloudCoverage,
            weatherType: atmosphere.weatherType,
            weatherAge: weather.map { max(0, now.timeIntervalSince($0.fetchedAt)) },
            ambientLux: ambient.lux,
            ambientLightQuality: ambient.quality,
            ambientLightAge: ambient.age(at: now),
            starsVisibility: atmosphere.starsVisibility,
            constellationsVisibility: atmosphere.constellationsVisibility,
            sunVisibility: atmosphere.sunVisibility,
            moonVisibility: atmosphere.moonVisibility,
            atmosphereOpacity: atmosphere.atmosphereOpacity,
            orientationQuality: orientationQuality,
            warnings: warnings
        )
    }
}
