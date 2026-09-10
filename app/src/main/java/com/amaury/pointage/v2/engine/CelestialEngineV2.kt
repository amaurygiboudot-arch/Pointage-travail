package com.amaury.pointage.v2.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure astronomy engine for HoraTrack V2.
 *
 * Goals:
 * - one deterministic source of truth for Sun/Moon positions;
 * - topocentric Moon position (parallax included);
 * - real illuminated fraction and bright-limb position angle;
 * - physical Earth umbra/penumbra geometry for lunar eclipses;
 * - no Android dependency so the maths can be unit-tested.
 */
interface CelestialEngineV2 {
    fun snapshot(
        latitudeDeg: Double,
        longitudeDeg: Double,
        timeMs: Long = System.currentTimeMillis(),
        observerAltitudeMeters: Double = 0.0
    ): CelestialSnapshotV2
}

data class CelestialBodyV2(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val distanceKm: Double,
    /** Relative apparent-size factor. 1.0 = mean apparent diameter. */
    val apparentScale: Double
)

data class LunarPhaseV2(
    /** 0.0 = new Moon, 1.0 = full Moon. */
    val illuminatedFraction: Double,
    /** Sun-Moon-Earth phase angle: 180° at new Moon, 0° at full Moon. */
    val phaseAngleDeg: Double,
    /** Apparent geocentric Sun/Moon elongation seen from Earth. */
    val elongationDeg: Double,
    val waxing: Boolean,
    /** Bright-limb position angle, measured from celestial north through east. */
    val brightLimbPositionAngleDeg: Double
)

enum class LunarEclipseStageV2 { NONE, PENUMBRAL, PARTIAL, TOTAL }

data class LunarEclipseV2(
    val stage: LunarEclipseStageV2,
    /** Approximate NASA-style umbral magnitude: > 1 means totality. */
    val umbralMagnitude: Double,
    /** Penumbral magnitude: > 0 means at least a penumbral eclipse. */
    val penumbralMagnitude: Double,
    /** Distance Moon centre -> Earth shadow axis, expressed in lunar radii. */
    val shadowAxisOffsetMoonRadii: Double,
    /** Physical Earth umbra radius at lunar distance, expressed in lunar radii. */
    val umbraRadiusMoonRadii: Double,
    /** Physical Earth penumbra radius at lunar distance, expressed in lunar radii. */
    val penumbraRadiusMoonRadii: Double,
    /** Direction from Moon centre toward Earth shadow axis, from celestial north through east. */
    val shadowPositionAngleDeg: Double
)

data class CelestialSnapshotV2(
    val atMs: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val sun: CelestialBodyV2,
    val moon: CelestialBodyV2,
    val moonPhase: LunarPhaseV2,
    val lunarEclipse: LunarEclipseV2,
    /** Same civil sunrise/sunset convention used by the legacy UI: Sun centre below -0.833°. */
    val night: Boolean
)

object DefaultCelestialEngineV2 : CelestialEngineV2 {
    private const val AU_KM = 149_597_870.7
    private const val EARTH_EQUATORIAL_RADIUS_KM = 6_378.137
    private const val SUN_RADIUS_KM = 696_340.0
    private const val MOON_RADIUS_KM = 1_737.4
    private const val MEAN_MOON_DISTANCE_EARTH_RADII = 60.2666
    private const val MILLIS_PER_DAY = 86_400_000.0

    private data class Equatorial(
        val rightAscensionDeg: Double,
        val declinationDeg: Double
    )

    private data class SunState(
        val equatorial: Equatorial,
        val distanceAu: Double,
        val eclipticLongitudeDeg: Double
    )

    private data class MoonState(
        val equatorial: Equatorial,
        val distanceEarthRadii: Double,
        val eclipticLongitudeDeg: Double,
        val eclipticLatitudeDeg: Double
    )

    override fun snapshot(
        latitudeDeg: Double,
        longitudeDeg: Double,
        timeMs: Long,
        observerAltitudeMeters: Double
    ): CelestialSnapshotV2 {
        require(latitudeDeg in -90.0..90.0) { "Latitude invalide" }
        require(longitudeDeg in -180.0..180.0) { "Longitude invalide" }
        require(timeMs > 0L) { "Horodatage invalide" }

        val jd = julianDay(timeMs)
        val sunGeo = sunGeocentric(jd)
        val moonGeo = moonGeocentric(jd)

        val sunHorizontal = horizontalFromEquatorial(
            sunGeo.equatorial,
            latitudeDeg,
            longitudeDeg,
            jd
        )
        val moonTopocentric = topocentricMoonEquatorial(
            moonGeo,
            latitudeDeg,
            longitudeDeg,
            observerAltitudeMeters,
            jd
        )
        val moonHorizontal = horizontalFromEquatorial(
            moonTopocentric,
            latitudeDeg,
            longitudeDeg,
            jd
        )

        val sunDistanceKm = sunGeo.distanceAu * AU_KM
        val moonDistanceKm = moonGeo.distanceEarthRadii * EARTH_EQUATORIAL_RADIUS_KM
        val elongationRad = angularSeparation(
            sunGeo.equatorial,
            moonGeo.equatorial
        )
        val phaseAngleRad = atan2(
            sunDistanceKm * sin(elongationRad),
            moonDistanceKm - sunDistanceKm * cos(elongationRad)
        ).let { if (it < 0.0) it + PI else it }
        val illuminatedFraction = ((1.0 + cos(phaseAngleRad)) * 0.5).coerceIn(0.0, 1.0)
        val phaseCycle = norm(moonGeo.eclipticLongitudeDeg - sunGeo.eclipticLongitudeDeg)

        val brightLimbPosition = positionAngle(
            from = moonGeo.equatorial,
            to = sunGeo.equatorial
        )

        val eclipse = lunarEclipse(
            sun = sunGeo,
            moon = moonGeo,
            elongationRad = elongationRad,
            sunDistanceKm = sunDistanceKm,
            moonDistanceKm = moonDistanceKm
        )

        val sunScale = (1.0 / sunGeo.distanceAu).coerceIn(0.97, 1.04)
        val moonScale = (MEAN_MOON_DISTANCE_EARTH_RADII / moonGeo.distanceEarthRadii)
            .coerceIn(0.88, 1.14)

        return CelestialSnapshotV2(
            atMs = timeMs,
            latitudeDeg = latitudeDeg,
            longitudeDeg = longitudeDeg,
            sun = CelestialBodyV2(
                azimuthDeg = sunHorizontal.first,
                altitudeDeg = sunHorizontal.second,
                distanceKm = sunDistanceKm,
                apparentScale = sunScale
            ),
            moon = CelestialBodyV2(
                azimuthDeg = moonHorizontal.first,
                altitudeDeg = moonHorizontal.second,
                distanceKm = moonDistanceKm,
                apparentScale = moonScale
            ),
            moonPhase = LunarPhaseV2(
                illuminatedFraction = illuminatedFraction,
                phaseAngleDeg = Math.toDegrees(phaseAngleRad),
                elongationDeg = Math.toDegrees(elongationRad),
                waxing = phaseCycle in 0.0..180.0,
                brightLimbPositionAngleDeg = brightLimbPosition
            ),
            lunarEclipse = eclipse,
            night = sunHorizontal.second < -0.833
        )
    }

    private fun sunGeocentric(jd: Double): SunState {
        val t = (jd - 2_451_545.0) / 36_525.0
        val l0 = norm(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sind(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sind(2.0 * m) * (0.019993 - 0.000101 * t) +
            sind(3.0 * m) * 0.000289
        val trueAnomaly = m + c
        val radiusAu = (1.000001018 * (1.0 - e * e)) /
            (1.0 + e * cosd(trueAnomaly))
        val omega = 125.04 - 1934.136 * t
        val lambda = norm(l0 + c - 0.00569 - 0.00478 * sind(omega))
        val epsilon0 = 23.0 +
            (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val epsilon = epsilon0 + 0.00256 * cosd(omega)

        val x = cosd(lambda)
        val y = cosd(epsilon) * sind(lambda)
        val z = sind(epsilon) * sind(lambda)
        val ra = norm(Math.toDegrees(atan2(y, x)))
        val dec = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))

        return SunState(Equatorial(ra, dec), radiusAu, lambda)
    }

    /**
     * Corrected low-cost lunar solution.
     *
     * The legacy engine skipped the Moon's 5.1454° orbital inclination and node
     * when building the ecliptic vector. That can move the Moon by tens of degrees
     * on screen. V2 restores the actual orbital plane, the main perturbations and
     * the distance perturbations before converting to equatorial coordinates.
     */
    private fun moonGeocentric(jd: Double): MoonState {
        val d = jd - 2_451_543.5
        val node = norm(125.1228 - 0.0529538083 * d)
        val inclination = 5.1454
        val periapsis = norm(318.0634 + 0.1643573223 * d)
        val semiMajor = MEAN_MOON_DISTANCE_EARTH_RADII
        val eccentricity = 0.054900
        val meanAnomaly = norm(115.3654 + 13.0649929509 * d)

        val eccentricAnomaly = norm(
            meanAnomaly + Math.toDegrees(
                eccentricity * sind(meanAnomaly) *
                    (1.0 + eccentricity * cosd(meanAnomaly))
            )
        )
        val xv = semiMajor * (cosd(eccentricAnomaly) - eccentricity)
        val yv = semiMajor * sqrt(1.0 - eccentricity * eccentricity) * sind(eccentricAnomaly)
        val trueAnomaly = Math.toDegrees(atan2(yv, xv))
        var distance = sqrt(xv * xv + yv * yv)
        val orbitalLongitude = norm(trueAnomaly + periapsis)

        val xh = distance * (
            cosd(node) * cosd(orbitalLongitude) -
                sind(node) * sind(orbitalLongitude) * cosd(inclination)
            )
        val yh = distance * (
            sind(node) * cosd(orbitalLongitude) +
                cosd(node) * sind(orbitalLongitude) * cosd(inclination)
            )
        val zh = distance * sind(orbitalLongitude) * sind(inclination)

        var longitude = norm(Math.toDegrees(atan2(yh, xh)))
        var latitude = Math.toDegrees(atan2(zh, sqrt(xh * xh + yh * yh)))

        val sunMeanAnomaly = norm(356.0470 + 0.9856002585 * d)
        val sunPeriapsis = norm(282.9404 + 4.70935E-5 * d)
        val sunMeanLongitude = norm(sunMeanAnomaly + sunPeriapsis)
        val moonMeanLongitude = norm(node + periapsis + meanAnomaly)
        val elongation = norm(moonMeanLongitude - sunMeanLongitude)
        val argumentLatitude = norm(moonMeanLongitude - node)

        longitude += -1.274 * sind(meanAnomaly - 2.0 * elongation) +
            0.658 * sind(2.0 * elongation) -
            0.186 * sind(sunMeanAnomaly) -
            0.059 * sind(2.0 * meanAnomaly - 2.0 * elongation) -
            0.057 * sind(meanAnomaly - 2.0 * elongation + sunMeanAnomaly) +
            0.053 * sind(meanAnomaly + 2.0 * elongation) +
            0.046 * sind(2.0 * elongation - sunMeanAnomaly) +
            0.041 * sind(meanAnomaly - sunMeanAnomaly) -
            0.035 * sind(elongation) -
            0.031 * sind(meanAnomaly + sunMeanAnomaly) -
            0.015 * sind(2.0 * argumentLatitude - 2.0 * elongation) +
            0.011 * sind(meanAnomaly - 4.0 * elongation)

        latitude += -0.173 * sind(argumentLatitude - 2.0 * elongation) -
            0.055 * sind(meanAnomaly - argumentLatitude - 2.0 * elongation) -
            0.046 * sind(meanAnomaly + argumentLatitude - 2.0 * elongation) +
            0.033 * sind(argumentLatitude + 2.0 * elongation) +
            0.017 * sind(2.0 * meanAnomaly + argumentLatitude)

        distance += -0.58 * cosd(meanAnomaly - 2.0 * elongation) -
            0.46 * cosd(2.0 * elongation)

        longitude = norm(longitude)
        val obliquity = 23.4393 - 3.563E-7 * d
        val lonRad = Math.toRadians(longitude)
        val latRad = Math.toRadians(latitude)
        val epsRad = Math.toRadians(obliquity)
        val xecl = distance * cos(lonRad) * cos(latRad)
        val yecl = distance * sin(lonRad) * cos(latRad)
        val zecl = distance * sin(latRad)
        val xeq = xecl
        val yeq = yecl * cos(epsRad) - zecl * sin(epsRad)
        val zeq = yecl * sin(epsRad) + zecl * cos(epsRad)
        val ra = norm(Math.toDegrees(atan2(yeq, xeq)))
        val dec = Math.toDegrees(atan2(zeq, sqrt(xeq * xeq + yeq * yeq)))

        return MoonState(
            equatorial = Equatorial(ra, dec),
            distanceEarthRadii = distance,
            eclipticLongitudeDeg = longitude,
            eclipticLatitudeDeg = latitude
        )
    }

    private fun topocentricMoonEquatorial(
        moon: MoonState,
        latitudeDeg: Double,
        longitudeDeg: Double,
        observerAltitudeMeters: Double,
        jd: Double
    ): Equatorial {
        val phi = Math.toRadians(latitudeDeg)
        val u = atan(0.99664719 * tan(phi))
        val altitudeEarthRadii = observerAltitudeMeters / (EARTH_EQUATORIAL_RADIUS_KM * 1000.0)
        val rhoSinPhi = 0.99664719 * sin(u) + altitudeEarthRadii * sin(phi)
        val rhoCosPhi = cos(u) + altitudeEarthRadii * cos(phi)
        val localSidereal = Math.toRadians(norm(gmstDeg(jd) + longitudeDeg))

        val ra = Math.toRadians(moon.equatorial.rightAscensionDeg)
        val dec = Math.toRadians(moon.equatorial.declinationDeg)
        val r = moon.distanceEarthRadii

        val moonX = r * cos(dec) * cos(ra)
        val moonY = r * cos(dec) * sin(ra)
        val moonZ = r * sin(dec)
        val observerX = rhoCosPhi * cos(localSidereal)
        val observerY = rhoCosPhi * sin(localSidereal)
        val observerZ = rhoSinPhi

        val x = moonX - observerX
        val y = moonY - observerY
        val z = moonZ - observerZ
        return Equatorial(
            rightAscensionDeg = norm(Math.toDegrees(atan2(y, x))),
            declinationDeg = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        )
    }

    private fun horizontalFromEquatorial(
        equatorial: Equatorial,
        latitudeDeg: Double,
        longitudeDeg: Double,
        jd: Double
    ): Pair<Double, Double> {
        val localSidereal = norm(gmstDeg(jd) + longitudeDeg)
        val hourAngle = Math.toRadians(signed(localSidereal - equatorial.rightAscensionDeg))
        val dec = Math.toRadians(equatorial.declinationDeg)
        val lat = Math.toRadians(latitudeDeg)
        val altitude = asin(
            (sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle))
                .coerceIn(-1.0, 1.0)
        )
        val azimuth = atan2(
            sin(hourAngle),
            cos(hourAngle) * sin(lat) - tan(dec) * cos(lat)
        )
        return norm(Math.toDegrees(azimuth) + 180.0) to Math.toDegrees(altitude)
    }

    private fun lunarEclipse(
        sun: SunState,
        moon: MoonState,
        elongationRad: Double,
        sunDistanceKm: Double,
        moonDistanceKm: Double
    ): LunarEclipseV2 {
        val antiSolarSeparation = abs(PI - elongationRad)
        val shadowAxisDistanceKm = moonDistanceKm * sin(antiSolarSeparation)
        val umbraRadiusKm = (
            EARTH_EQUATORIAL_RADIUS_KM -
                moonDistanceKm * (SUN_RADIUS_KM - EARTH_EQUATORIAL_RADIUS_KM) / sunDistanceKm
            ).coerceAtLeast(0.0)
        val penumbraRadiusKm = EARTH_EQUATORIAL_RADIUS_KM +
            moonDistanceKm * (SUN_RADIUS_KM + EARTH_EQUATORIAL_RADIUS_KM) / sunDistanceKm

        val umbralMagnitude = (
            umbraRadiusKm + MOON_RADIUS_KM - shadowAxisDistanceKm
            ) / (2.0 * MOON_RADIUS_KM)
        val penumbralMagnitude = (
            penumbraRadiusKm + MOON_RADIUS_KM - shadowAxisDistanceKm
            ) / (2.0 * MOON_RADIUS_KM)

        val stage = when {
            penumbralMagnitude <= 0.0 -> LunarEclipseStageV2.NONE
            umbralMagnitude <= 0.0 -> LunarEclipseStageV2.PENUMBRAL
            umbralMagnitude >= 1.0 -> LunarEclipseStageV2.TOTAL
            else -> LunarEclipseStageV2.PARTIAL
        }

        val antiSun = Equatorial(
            rightAscensionDeg = norm(sun.equatorial.rightAscensionDeg + 180.0),
            declinationDeg = -sun.equatorial.declinationDeg
        )

        return LunarEclipseV2(
            stage = stage,
            umbralMagnitude = umbralMagnitude,
            penumbralMagnitude = penumbralMagnitude,
            shadowAxisOffsetMoonRadii = shadowAxisDistanceKm / MOON_RADIUS_KM,
            umbraRadiusMoonRadii = umbraRadiusKm / MOON_RADIUS_KM,
            penumbraRadiusMoonRadii = penumbraRadiusKm / MOON_RADIUS_KM,
            shadowPositionAngleDeg = positionAngle(moon.equatorial, antiSun)
        )
    }

    private fun angularSeparation(a: Equatorial, b: Equatorial): Double {
        val raA = Math.toRadians(a.rightAscensionDeg)
        val decA = Math.toRadians(a.declinationDeg)
        val raB = Math.toRadians(b.rightAscensionDeg)
        val decB = Math.toRadians(b.declinationDeg)
        val cosine = (
            sin(decA) * sin(decB) +
                cos(decA) * cos(decB) * cos(raA - raB)
            ).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(cosine)
    }

    private fun positionAngle(from: Equatorial, to: Equatorial): Double {
        val raFrom = Math.toRadians(from.rightAscensionDeg)
        val decFrom = Math.toRadians(from.declinationDeg)
        val raTo = Math.toRadians(to.rightAscensionDeg)
        val decTo = Math.toRadians(to.declinationDeg)
        val deltaRa = raTo - raFrom
        val y = cos(decTo) * sin(deltaRa)
        val x = sin(decTo) * cos(decFrom) -
            cos(decTo) * sin(decFrom) * cos(deltaRa)
        return norm(Math.toDegrees(atan2(y, x)))
    }

    private fun gmstDeg(jd: Double): Double {
        val t = (jd - 2_451_545.0) / 36_525.0
        return norm(
            280.46061837 +
                360.98564736629 * (jd - 2_451_545.0) +
                0.000387933 * t * t -
                t * t * t / 38_710_000.0
        )
    }

    private fun julianDay(timeMs: Long): Double =
        timeMs / MILLIS_PER_DAY + 2_440_587.5

    private fun sind(value: Double): Double = sin(Math.toRadians(value))
    private fun cosd(value: Double): Double = cos(Math.toRadians(value))
    private fun norm(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun signed(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
}
