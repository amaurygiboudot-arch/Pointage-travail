import Foundation

struct CelestialBodyV2: Equatable, Sendable {
    let azimuthDegrees: Double
    let altitudeDegrees: Double
    let distanceKilometers: Double
    /// Relative apparent diameter; 1 is the mean apparent diameter.
    let apparentScale: Double
}

struct LunarPhaseV2: Equatable, Sendable {
    /// 0 is new Moon; 1 is full Moon.
    let illuminatedFraction: Double
    /// Sun-Moon-Earth phase angle; 180 degrees at new Moon.
    let phaseAngleDegrees: Double
    let elongationDegrees: Double
    let waxing: Bool
    /// Measured from celestial north through east.
    let brightLimbPositionAngleDegrees: Double
}

enum LunarEclipseStageV2: Equatable, Sendable {
    case none
    case penumbral
    case partial
    case total
}

struct LunarEclipseV2: Equatable, Sendable {
    let stage: LunarEclipseStageV2
    /// Approximate NASA-style umbral magnitude; values above 1 indicate totality.
    let umbralMagnitude: Double
    /// Values above 0 indicate at least a penumbral eclipse.
    let penumbralMagnitude: Double
    /// Moon-centre distance from the Earth-shadow axis, in lunar radii.
    let shadowAxisOffsetMoonRadii: Double
    /// Physical Earth-umbra radius at the Moon's axial distance, in lunar radii.
    let umbraRadiusMoonRadii: Double
    /// Physical Earth-penumbra radius at the Moon's axial distance, in lunar radii.
    let penumbraRadiusMoonRadii: Double
    /// Direction from Moon centre toward the shadow axis, from celestial north through east.
    let shadowPositionAngleDegrees: Double
}

struct CelestialSnapshotV2: Equatable, Sendable {
    let date: Date
    let latitudeDegrees: Double
    let longitudeDegrees: Double
    let sun: CelestialBodyV2
    let moon: CelestialBodyV2
    let moonPhase: LunarPhaseV2
    let lunarEclipse: LunarEclipseV2
    /// Civil sunrise/sunset convention: Sun centre below -0.833 degrees.
    let isNight: Bool
}

enum CelestialEngineError: Error, Equatable {
    case invalidLatitude
    case invalidLongitude
    case invalidDate
    case invalidObserverAltitude
}

/// Canonical, deterministic iOS astronomy source for HoraTrack.
///
/// This is a deliberately bounded low-cost model, ported from the validated
/// Android V2 equations. It provides geometric topocentric positions; visual
/// atmospheric refraction is intentionally not mixed into these values.
enum DefaultCelestialEngineV2 {
    private static let astronomicalUnitKilometers = 149_597_870.7
    private static let earthEquatorialRadiusKilometers = 6_378.137
    private static let sunRadiusKilometers = 696_340.0
    private static let moonRadiusKilometers = 1_737.4
    private static let meanMoonDistanceEarthRadii = 60.2666

    private struct Equatorial {
        let rightAscensionDegrees: Double
        let declinationDegrees: Double
    }

    private struct SunState {
        let equatorial: Equatorial
        let distanceAstronomicalUnits: Double
        let eclipticLongitudeDegrees: Double
    }

    private struct MoonState {
        let equatorial: Equatorial
        let distanceEarthRadii: Double
        let eclipticLongitudeDegrees: Double
    }

    private struct TopocentricMoonState {
        let equatorial: Equatorial
        let distanceEarthRadii: Double
    }

    static func snapshot(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        date: Date = Date(),
        observerAltitudeMeters: Double = 0
    ) throws -> CelestialSnapshotV2 {
        guard latitudeDegrees.isFinite, (-90.0...90.0).contains(latitudeDegrees) else {
            throw CelestialEngineError.invalidLatitude
        }
        guard longitudeDegrees.isFinite, (-180.0...180.0).contains(longitudeDegrees) else {
            throw CelestialEngineError.invalidLongitude
        }
        guard date.timeIntervalSince1970.isFinite, date.timeIntervalSince1970 > 0 else {
            throw CelestialEngineError.invalidDate
        }
        guard observerAltitudeMeters.isFinite,
              (-1_000.0...100_000.0).contains(observerAltitudeMeters) else {
            throw CelestialEngineError.invalidObserverAltitude
        }

        let julianDay = date.timeIntervalSince1970 / 86_400 + 2_440_587.5
        let sunGeocentric = sunGeocentric(julianDay: julianDay)
        let moonGeocentric = moonGeocentric(julianDay: julianDay)

        let sunHorizontal = horizontal(
            equatorial: sunGeocentric.equatorial,
            latitudeDegrees: latitudeDegrees,
            longitudeDegrees: longitudeDegrees,
            julianDay: julianDay
        )
        let moonTopocentric = topocentricMoon(
            moon: moonGeocentric,
            latitudeDegrees: latitudeDegrees,
            longitudeDegrees: longitudeDegrees,
            observerAltitudeMeters: observerAltitudeMeters,
            julianDay: julianDay
        )
        let moonHorizontal = horizontal(
            equatorial: moonTopocentric.equatorial,
            latitudeDegrees: latitudeDegrees,
            longitudeDegrees: longitudeDegrees,
            julianDay: julianDay
        )

        let sunDistance = sunGeocentric.distanceAstronomicalUnits * astronomicalUnitKilometers
        let moonDistance = moonGeocentric.distanceEarthRadii * earthEquatorialRadiusKilometers
        let elongation = angularSeparation(sunGeocentric.equatorial, moonGeocentric.equatorial)
        var phaseAngle = atan2(
            sunDistance * sin(elongation),
            moonDistance - sunDistance * cos(elongation)
        )
        if phaseAngle < 0 { phaseAngle += .pi }
        let illuminatedFraction = clamp((1 + cos(phaseAngle)) * 0.5, minimum: 0, maximum: 1)
        let phaseCycle = normalizedDegrees(
            moonGeocentric.eclipticLongitudeDegrees - sunGeocentric.eclipticLongitudeDegrees
        )
        let lunarEclipse = lunarEclipse(
            sun: sunGeocentric,
            moon: moonGeocentric,
            elongationRadians: elongation,
            sunDistanceKilometers: sunDistance,
            moonDistanceKilometers: moonDistance
        )

        return CelestialSnapshotV2(
            date: date,
            latitudeDegrees: latitudeDegrees,
            longitudeDegrees: longitudeDegrees,
            sun: CelestialBodyV2(
                azimuthDegrees: sunHorizontal.azimuth,
                altitudeDegrees: sunHorizontal.altitude,
                distanceKilometers: sunDistance,
                apparentScale: clamp(1 / sunGeocentric.distanceAstronomicalUnits, minimum: 0.97, maximum: 1.04)
            ),
            moon: CelestialBodyV2(
                azimuthDegrees: moonHorizontal.azimuth,
                altitudeDegrees: moonHorizontal.altitude,
                distanceKilometers: moonTopocentric.distanceEarthRadii * earthEquatorialRadiusKilometers,
                apparentScale: clamp(
                    meanMoonDistanceEarthRadii / moonTopocentric.distanceEarthRadii,
                    minimum: 0.88,
                    maximum: 1.14
                )
            ),
            moonPhase: LunarPhaseV2(
                illuminatedFraction: illuminatedFraction,
                phaseAngleDegrees: radiansToDegrees(phaseAngle),
                elongationDegrees: radiansToDegrees(elongation),
                waxing: (0...180).contains(phaseCycle),
                brightLimbPositionAngleDegrees: positionAngle(
                    from: moonGeocentric.equatorial,
                    to: sunGeocentric.equatorial
                )
            ),
            lunarEclipse: lunarEclipse,
            isNight: sunHorizontal.altitude < AtmosphericRefractionV2.standardSolarDiskHorizonDegrees
        )
    }

    private static func sunGeocentric(julianDay: Double) -> SunState {
        let t = (julianDay - 2_451_545) / 36_525
        let l0 = normalizedDegrees(280.46646 + t * (36_000.76983 + t * 0.0003032))
        let meanAnomaly = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        let eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        let equationOfCenter = sineDegrees(meanAnomaly) * (1.914602 - t * (0.004817 + 0.000014 * t))
            + sineDegrees(2 * meanAnomaly) * (0.019993 - 0.000101 * t)
            + sineDegrees(3 * meanAnomaly) * 0.000289
        let trueAnomaly = meanAnomaly + equationOfCenter
        let radius = (1.000001018 * (1 - eccentricity * eccentricity))
            / (1 + eccentricity * cosineDegrees(trueAnomaly))
        let omega = 125.04 - 1934.136 * t
        let longitude = normalizedDegrees(l0 + equationOfCenter - 0.00569 - 0.00478 * sineDegrees(omega))
        let meanObliquity = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        let obliquity = meanObliquity + 0.00256 * cosineDegrees(omega)

        let x = cosineDegrees(longitude)
        let y = cosineDegrees(obliquity) * sineDegrees(longitude)
        let z = sineDegrees(obliquity) * sineDegrees(longitude)
        return SunState(
            equatorial: Equatorial(
                rightAscensionDegrees: normalizedDegrees(radiansToDegrees(atan2(y, x))),
                declinationDegrees: radiansToDegrees(asin(clamp(z, minimum: -1, maximum: 1)))
            ),
            distanceAstronomicalUnits: radius,
            eclipticLongitudeDegrees: longitude
        )
    }

    private static func moonGeocentric(julianDay: Double) -> MoonState {
        let days = julianDay - 2_451_543.5
        let node = normalizedDegrees(125.1228 - 0.0529538083 * days)
        let inclination = 5.1454
        let periapsis = normalizedDegrees(318.0634 + 0.1643573223 * days)
        let semiMajor = meanMoonDistanceEarthRadii
        let eccentricity = 0.054900
        let meanAnomaly = normalizedDegrees(115.3654 + 13.0649929509 * days)
        let eccentricAnomaly = normalizedDegrees(
            meanAnomaly + radiansToDegrees(
                eccentricity * sineDegrees(meanAnomaly) * (1 + eccentricity * cosineDegrees(meanAnomaly))
            )
        )
        let xv = semiMajor * (cosineDegrees(eccentricAnomaly) - eccentricity)
        let yv = semiMajor * sqrt(1 - eccentricity * eccentricity) * sineDegrees(eccentricAnomaly)
        let trueAnomaly = radiansToDegrees(atan2(yv, xv))
        var distance = sqrt(xv * xv + yv * yv)
        let orbitalLongitude = normalizedDegrees(trueAnomaly + periapsis)

        let xh = distance * (
            cosineDegrees(node) * cosineDegrees(orbitalLongitude)
                - sineDegrees(node) * sineDegrees(orbitalLongitude) * cosineDegrees(inclination)
        )
        let yh = distance * (
            sineDegrees(node) * cosineDegrees(orbitalLongitude)
                + cosineDegrees(node) * sineDegrees(orbitalLongitude) * cosineDegrees(inclination)
        )
        let zh = distance * sineDegrees(orbitalLongitude) * sineDegrees(inclination)
        var longitude = normalizedDegrees(radiansToDegrees(atan2(yh, xh)))
        var latitude = radiansToDegrees(atan2(zh, sqrt(xh * xh + yh * yh)))

        let sunMeanAnomaly = normalizedDegrees(356.0470 + 0.9856002585 * days)
        let sunPeriapsis = normalizedDegrees(282.9404 + 4.70935e-5 * days)
        let sunMeanLongitude = normalizedDegrees(sunMeanAnomaly + sunPeriapsis)
        let moonMeanLongitude = normalizedDegrees(node + periapsis + meanAnomaly)
        let elongation = normalizedDegrees(moonMeanLongitude - sunMeanLongitude)
        let argumentLatitude = normalizedDegrees(moonMeanLongitude - node)

        longitude += -1.274 * sineDegrees(meanAnomaly - 2 * elongation)
            + 0.658 * sineDegrees(2 * elongation)
            - 0.186 * sineDegrees(sunMeanAnomaly)
            - 0.059 * sineDegrees(2 * meanAnomaly - 2 * elongation)
            - 0.057 * sineDegrees(meanAnomaly - 2 * elongation + sunMeanAnomaly)
            + 0.053 * sineDegrees(meanAnomaly + 2 * elongation)
            + 0.046 * sineDegrees(2 * elongation - sunMeanAnomaly)
            + 0.041 * sineDegrees(meanAnomaly - sunMeanAnomaly)
            - 0.035 * sineDegrees(elongation)
            - 0.031 * sineDegrees(meanAnomaly + sunMeanAnomaly)
            - 0.015 * sineDegrees(2 * argumentLatitude - 2 * elongation)
            + 0.011 * sineDegrees(meanAnomaly - 4 * elongation)

        latitude += -0.173 * sineDegrees(argumentLatitude - 2 * elongation)
            - 0.055 * sineDegrees(meanAnomaly - argumentLatitude - 2 * elongation)
            - 0.046 * sineDegrees(meanAnomaly + argumentLatitude - 2 * elongation)
            + 0.033 * sineDegrees(argumentLatitude + 2 * elongation)
            + 0.017 * sineDegrees(2 * meanAnomaly + argumentLatitude)

        distance += -0.58 * cosineDegrees(meanAnomaly - 2 * elongation)
            - 0.46 * cosineDegrees(2 * elongation)
        longitude = normalizedDegrees(longitude)

        let obliquity = degreesToRadians(23.4393 - 3.563e-7 * days)
        let longitudeRadians = degreesToRadians(longitude)
        let latitudeRadians = degreesToRadians(latitude)
        let xecliptic = distance * cos(longitudeRadians) * cos(latitudeRadians)
        let yecliptic = distance * sin(longitudeRadians) * cos(latitudeRadians)
        let zecliptic = distance * sin(latitudeRadians)
        let xequatorial = xecliptic
        let yequatorial = yecliptic * cos(obliquity) - zecliptic * sin(obliquity)
        let zequatorial = yecliptic * sin(obliquity) + zecliptic * cos(obliquity)

        return MoonState(
            equatorial: Equatorial(
                rightAscensionDegrees: normalizedDegrees(radiansToDegrees(atan2(yequatorial, xequatorial))),
                declinationDegrees: radiansToDegrees(
                    atan2(zequatorial, sqrt(xequatorial * xequatorial + yequatorial * yequatorial))
                )
            ),
            distanceEarthRadii: distance,
            eclipticLongitudeDegrees: longitude
        )
    }

    private static func topocentricMoon(
        moon: MoonState,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        observerAltitudeMeters: Double,
        julianDay: Double
    ) -> TopocentricMoonState {
        let latitude = degreesToRadians(latitudeDegrees)
        let reducedLatitude = atan(0.99664719 * tan(latitude))
        let altitudeEarthRadii = observerAltitudeMeters / (earthEquatorialRadiusKilometers * 1_000)
        let rhoSinLatitude = 0.99664719 * sin(reducedLatitude) + altitudeEarthRadii * sin(latitude)
        let rhoCosLatitude = cos(reducedLatitude) + altitudeEarthRadii * cos(latitude)
        let localSidereal = degreesToRadians(normalizedDegrees(gmstDegrees(julianDay) + longitudeDegrees))
        let rightAscension = degreesToRadians(moon.equatorial.rightAscensionDegrees)
        let declination = degreesToRadians(moon.equatorial.declinationDegrees)

        let moonX = moon.distanceEarthRadii * cos(declination) * cos(rightAscension)
        let moonY = moon.distanceEarthRadii * cos(declination) * sin(rightAscension)
        let moonZ = moon.distanceEarthRadii * sin(declination)
        let x = moonX - rhoCosLatitude * cos(localSidereal)
        let y = moonY - rhoCosLatitude * sin(localSidereal)
        let z = moonZ - rhoSinLatitude
        return TopocentricMoonState(
            equatorial: Equatorial(
                rightAscensionDegrees: normalizedDegrees(radiansToDegrees(atan2(y, x))),
                declinationDegrees: radiansToDegrees(atan2(z, sqrt(x * x + y * y)))
            ),
            distanceEarthRadii: sqrt(x * x + y * y + z * z)
        )
    }

    private static func horizontal(
        equatorial: Equatorial,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        julianDay: Double
    ) -> (azimuth: Double, altitude: Double) {
        let localSidereal = normalizedDegrees(gmstDegrees(julianDay) + longitudeDegrees)
        let hourAngle = degreesToRadians(signedDegrees(localSidereal - equatorial.rightAscensionDegrees))
        let declination = degreesToRadians(equatorial.declinationDegrees)
        let latitude = degreesToRadians(latitudeDegrees)
        let altitude = asin(clamp(
            sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle),
            minimum: -1,
            maximum: 1
        ))
        let azimuth = atan2(
            sin(hourAngle),
            cos(hourAngle) * sin(latitude) - tan(declination) * cos(latitude)
        )
        return (
            normalizedDegrees(radiansToDegrees(azimuth) + 180),
            radiansToDegrees(altitude)
        )
    }

    private static func lunarEclipse(
        sun: SunState,
        moon: MoonState,
        elongationRadians: Double,
        sunDistanceKilometers: Double,
        moonDistanceKilometers: Double
    ) -> LunarEclipseV2 {
        let antiSolarSeparation = abs(Double.pi - elongationRadians)

        // Earth's shadow is an anti-solar half-ray, not an infinite line. A
        // Moon on the Sun-facing side must never be projected onto the rear
        // extension of that line and misclassified as a lunar eclipse.
        let shadowAxialDistanceKilometers = moonDistanceKilometers * cos(antiSolarSeparation)
        let moonIsBehindEarth = shadowAxialDistanceKilometers > 0
        let shadowAxisDistanceKilometers = moonIsBehindEarth
            ? moonDistanceKilometers * sin(antiSolarSeparation)
            : moonDistanceKilometers
        let physicalShadowDistanceKilometers = max(0, shadowAxialDistanceKilometers)

        let umbraRadiusKilometers = max(
            0,
            earthEquatorialRadiusKilometers
                - physicalShadowDistanceKilometers
                * (sunRadiusKilometers - earthEquatorialRadiusKilometers)
                / sunDistanceKilometers
        )
        let penumbraRadiusKilometers = earthEquatorialRadiusKilometers
            + physicalShadowDistanceKilometers
            * (sunRadiusKilometers + earthEquatorialRadiusKilometers)
            / sunDistanceKilometers

        let umbralMagnitude = (
            umbraRadiusKilometers + moonRadiusKilometers - shadowAxisDistanceKilometers
        ) / (2 * moonRadiusKilometers)
        let penumbralMagnitude = (
            penumbraRadiusKilometers + moonRadiusKilometers - shadowAxisDistanceKilometers
        ) / (2 * moonRadiusKilometers)

        let stage: LunarEclipseStageV2
        if penumbralMagnitude <= 0 {
            stage = .none
        } else if umbralMagnitude <= 0 {
            stage = .penumbral
        } else if umbralMagnitude >= 1 {
            stage = .total
        } else {
            stage = .partial
        }

        let antiSun = Equatorial(
            rightAscensionDegrees: normalizedDegrees(sun.equatorial.rightAscensionDegrees + 180),
            declinationDegrees: -sun.equatorial.declinationDegrees
        )
        return LunarEclipseV2(
            stage: stage,
            umbralMagnitude: umbralMagnitude,
            penumbralMagnitude: penumbralMagnitude,
            shadowAxisOffsetMoonRadii: shadowAxisDistanceKilometers / moonRadiusKilometers,
            umbraRadiusMoonRadii: umbraRadiusKilometers / moonRadiusKilometers,
            penumbraRadiusMoonRadii: penumbraRadiusKilometers / moonRadiusKilometers,
            shadowPositionAngleDegrees: positionAngle(from: moon.equatorial, to: antiSun)
        )
    }

    private static func angularSeparation(_ a: Equatorial, _ b: Equatorial) -> Double {
        let rightAscensionA = degreesToRadians(a.rightAscensionDegrees)
        let declinationA = degreesToRadians(a.declinationDegrees)
        let rightAscensionB = degreesToRadians(b.rightAscensionDegrees)
        let declinationB = degreesToRadians(b.declinationDegrees)
        let cosine = clamp(
            sin(declinationA) * sin(declinationB)
                + cos(declinationA) * cos(declinationB) * cos(rightAscensionA - rightAscensionB),
            minimum: -1,
            maximum: 1
        )
        return acos(cosine)
    }

    private static func positionAngle(from: Equatorial, to: Equatorial) -> Double {
        let rightAscensionFrom = degreesToRadians(from.rightAscensionDegrees)
        let declinationFrom = degreesToRadians(from.declinationDegrees)
        let rightAscensionTo = degreesToRadians(to.rightAscensionDegrees)
        let declinationTo = degreesToRadians(to.declinationDegrees)
        let deltaRightAscension = rightAscensionTo - rightAscensionFrom
        let y = cos(declinationTo) * sin(deltaRightAscension)
        let x = sin(declinationTo) * cos(declinationFrom)
            - cos(declinationTo) * sin(declinationFrom) * cos(deltaRightAscension)
        return normalizedDegrees(radiansToDegrees(atan2(y, x)))
    }

    private static func gmstDegrees(_ julianDay: Double) -> Double {
        let t = (julianDay - 2_451_545) / 36_525
        return normalizedDegrees(
            280.46061837
                + 360.98564736629 * (julianDay - 2_451_545)
                + 0.000387933 * t * t
                - t * t * t / 38_710_000
        )
    }

    private static func sineDegrees(_ value: Double) -> Double { sin(degreesToRadians(value)) }
    private static func cosineDegrees(_ value: Double) -> Double { cos(degreesToRadians(value)) }
    private static func degreesToRadians(_ value: Double) -> Double { value * .pi / 180 }
    private static func radiansToDegrees(_ value: Double) -> Double { value * 180 / .pi }

    private static func normalizedDegrees(_ value: Double) -> Double {
        let remainder = value.truncatingRemainder(dividingBy: 360)
        return remainder < 0 ? remainder + 360 : remainder
    }

    private static func signedDegrees(_ value: Double) -> Double {
        normalizedDegrees(value + 180) - 180
    }

    private static func clamp(_ value: Double, minimum: Double, maximum: Double) -> Double {
        min(maximum, max(minimum, value))
    }
}
