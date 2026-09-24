import Foundation

enum CelestialDataStatusV2: Equatable, Sendable {
    case fresh
    case stale
    case unavailable
    case invalid
}

struct CelestialDataFreshnessV2: Equatable, Sendable {
    let astronomyAge: TimeInterval
    let locationAge: TimeInterval?
    let headingAge: TimeInterval?
    let weatherAge: TimeInterval?
    let ambientAge: TimeInterval?
    let locationSource: String?
    let weatherSource: String?
    let astronomyStatus: CelestialDataStatusV2
    let locationStatus: CelestialDataStatusV2
    let headingStatus: CelestialDataStatusV2
    let weatherStatus: CelestialDataStatusV2
    let ambientStatus: CelestialDataStatusV2
}

enum CelestialRenderWarningV2: Hashable, Sendable {
    case weatherUnavailable
    case weatherStale
    case ambientLightUnavailable
    case ambientLightStale
    case locationStale
    case orientationUnqualified
    case astronomyStale
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
    let dataFreshness: CelestialDataFreshnessV2
    let warnings: Set<CelestialRenderWarningV2>
}

enum CelestialRenderStateFactoryV2 {
    static func build(
        snapshot: CelestialSnapshotV2,
        weather: CelestialWeatherStateV2?,
        ambient: CelestialAmbientLightStateV2,
        orientationQuality: CelestialHeadingQualityV2,
        locationQuality: CelestialLocationQualityV2 = .unavailable,
        locationAge: TimeInterval? = nil,
        locationSource: String? = nil,
        headingAge: TimeInterval? = nil,
        now: Date = Date()
    ) -> CelestialRenderStateV2 {
        let atmosphere = CelestialAtmosphereV2.resolve(
            snapshot: snapshot,
            weather: weather,
            ambient: ambient
        )

        let astronomyAge = max(0, now.timeIntervalSince(snapshot.date))
        let weatherAge = weather.map { max(0, now.timeIntervalSince($0.fetchedAt)) }
        let ambientAge = ambient.age(at: now)

        let freshness = CelestialDataFreshnessV2(
            astronomyAge: astronomyAge,
            locationAge: locationAge,
            headingAge: headingAge,
            weatherAge: weatherAge,
            ambientAge: ambientAge,
            locationSource: locationSource,
            weatherSource: weather?.source,
            astronomyStatus: astronomyAge <= 5 ? .fresh : .stale,
            locationStatus: locationStatus(locationQuality),
            headingStatus: headingStatus(orientationQuality),
            weatherStatus: {
                guard let weather else { return .unavailable }
                return weather.isFresh(at: now) ? .fresh : .stale
            }(),
            ambientStatus: ambientStatus(ambient.quality)
        )

        var warnings: Set<CelestialRenderWarningV2> = []
        switch freshness.weatherStatus {
        case .fresh:
            break
        case .stale:
            warnings.insert(.weatherStale)
        case .unavailable, .invalid:
            warnings.insert(.weatherUnavailable)
        }
        switch ambient.quality {
        case .unavailable, .invalid:
            warnings.insert(.ambientLightUnavailable)
        case .stale:
            warnings.insert(.ambientLightStale)
        case .valid:
            break
        }
        if locationQuality == .stale {
            warnings.insert(.locationStale)
        }
        if !CelestialHeadingPolicyV2.isUsable(orientationQuality) {
            warnings.insert(.orientationUnqualified)
        }
        if freshness.astronomyStatus == .stale {
            warnings.insert(.astronomyStale)
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
            weatherAge: weatherAge,
            ambientLux: ambient.lux,
            ambientLightQuality: ambient.quality,
            ambientLightAge: ambientAge,
            starsVisibility: atmosphere.starsVisibility,
            constellationsVisibility: atmosphere.constellationsVisibility,
            sunVisibility: atmosphere.sunVisibility,
            moonVisibility: atmosphere.moonVisibility,
            atmosphereOpacity: atmosphere.atmosphereOpacity,
            orientationQuality: orientationQuality,
            dataFreshness: freshness,
            warnings: warnings
        )
    }

    private static func locationStatus(
        _ quality: CelestialLocationQualityV2
    ) -> CelestialDataStatusV2 {
        switch quality {
        case .valid:
            return .fresh
        case .stale:
            return .stale
        case .inaccurate:
            return .invalid
        case .noPermission, .unavailable:
            return .unavailable
        }
    }

    private static func headingStatus(
        _ quality: CelestialHeadingQualityV2
    ) -> CelestialDataStatusV2 {
        switch quality {
        case .valid, .unknownAccuracy:
            return .fresh
        case .stale:
            return .stale
        case .inaccurate, .unreliable:
            return .invalid
        case .unavailable:
            return .unavailable
        }
    }

    private static func ambientStatus(
        _ quality: CelestialAmbientLightQualityV2
    ) -> CelestialDataStatusV2 {
        switch quality {
        case .valid:
            return .fresh
        case .stale:
            return .stale
        case .invalid:
            return .invalid
        case .unavailable:
            return .unavailable
        }
    }
}
