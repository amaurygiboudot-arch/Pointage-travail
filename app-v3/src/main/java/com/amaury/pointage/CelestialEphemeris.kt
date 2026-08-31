package com.amaury.pointage

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object CelestialEphemeris {
    data class Position(
        val azimuth: Double,
        val altitude: Double,
        /** Relative apparent-size factor. 1.0 = mean apparent diameter. */
        val apparentScale: Double = 1.0
    )

    fun sun(latitude: Double, longitude: Double, timeMs: Long = System.currentTimeMillis()): Position {
        val jd = julianDay(timeMs)
        val t = (jd - 2451545.0) / 36525.0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0 + cal.get(Calendar.SECOND) / 3600.0

        val l0 = norm(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sind(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sind(2 * m) * (0.019993 - 0.000101 * t) + sind(3 * m) * 0.000289
        val trueAnomaly = m + c
        val radiusAu = (1.000001018 * (1.0 - e * e)) / (1.0 + e * cos(Math.toRadians(trueAnomaly)))
        val apparentScale = (1.0 / radiusAu).coerceIn(0.97, 1.04)

        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sind(omega)
        val epsilon0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val epsilon = epsilon0 + 0.00256 * cosd(omega)
        val decl = asin(sind(epsilon) * sind(lambda))
        val y = tan(Math.toRadians(epsilon / 2.0)).let { it * it }
        val eqTime = 4.0 * Math.toDegrees(
            y * sin(2 * Math.toRadians(l0)) - 2 * e * sin(Math.toRadians(m)) +
                4 * e * y * sin(Math.toRadians(m)) * cos(2 * Math.toRadians(l0)) -
                0.5 * y * y * sin(4 * Math.toRadians(l0)) - 1.25 * e * e * sin(2 * Math.toRadians(m))
        )
        val solarMinutes = ((hour * 60.0 + eqTime + 4.0 * longitude) % 1440.0 + 1440.0) % 1440.0
        val ha = Math.toRadians(solarMinutes / 4.0 - 180.0)
        val lat = Math.toRadians(latitude)
        val az = atan2(sin(ha), cos(ha) * sin(lat) - tan(decl) * cos(lat))
        val alt = asin((sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha)).coerceIn(-1.0, 1.0))
        return Position(norm(Math.toDegrees(az) + 180.0), Math.toDegrees(alt), apparentScale)
    }

    fun moon(latitude: Double, longitude: Double, timeMs: Long = System.currentTimeMillis()): Position {
        val jd = julianDay(timeMs)
        val d = jd - 2451543.5
        val n = norm(125.1228 - 0.0529538083 * d)
        val w = norm(318.0634 + 0.1643573223 * d)
        val m = norm(115.3654 + 13.0649929509 * d)
        val e = 0.054900
        val a = 60.2666
        val eccentric = norm(m + Math.toDegrees(e * sind(m) * (1.0 + e * cosd(m))))
        val xv = a * (cosd(eccentric) - e)
        val yv = a * sqrt(1.0 - e * e) * sind(eccentric)
        val v = Math.toDegrees(atan2(yv, xv))
        val r = sqrt(xv * xv + yv * yv)
        // Mean lunar distance in Earth radii is about 60.27. Apparent diameter varies inversely with distance.
        val apparentScale = (60.2666 / r).coerceIn(0.88, 1.14)

        var lon = norm(v + w)
        var latMoon = 0.0
        val sunM = norm(356.0470 + 0.9856002585 * d)
        val sunW = norm(282.9404 + 4.70935E-5 * d)
        val sunL = norm(sunM + sunW)
        val moonL = norm(n + w + m)
        val elong = norm(moonL - sunL)
        val f = norm(moonL - n)
        lon += -1.274 * sind(m - 2 * elong) + 0.658 * sind(2 * elong) - 0.186 * sind(sunM)
        lon += -0.059 * sind(2 * m - 2 * elong) - 0.057 * sind(m - 2 * elong + sunM)
        lon += 0.053 * sind(m + 2 * elong) + 0.046 * sind(2 * elong - sunM)
        lon += 0.041 * sind(m - sunM) - 0.035 * sind(elong) - 0.031 * sind(m + sunM)
        latMoon += -0.173 * sind(f - 2 * elong) - 0.055 * sind(m - f - 2 * elong)
        latMoon += -0.046 * sind(m + f - 2 * elong) + 0.033 * sind(f + 2 * elong) + 0.017 * sind(2 * m + f)

        val lonR = Math.toRadians(norm(lon))
        val latR = Math.toRadians(latMoon)
        val eps = Math.toRadians(23.4393 - 3.563E-7 * d)
        val xecl = r * cos(lonR) * cos(latR)
        val yecl = r * sin(lonR) * cos(latR)
        val zecl = r * sin(latR)
        val xeq = xecl
        val yeq = yecl * cos(eps) - zecl * sin(eps)
        val zeq = yecl * sin(eps) + zecl * cos(eps)
        val ra = norm(Math.toDegrees(atan2(yeq, xeq)))
        val dec = Math.toDegrees(atan2(zeq, sqrt(xeq * xeq + yeq * yeq)))
        val gmst = 18.697374558 + 24.06570982441908 * (jd - 2451545.0)
        val lst = norm(gmst * 15.0 + longitude)
        val ha = Math.toRadians(signed(lst - ra))
        val decR = Math.toRadians(dec)
        val obsLat = Math.toRadians(latitude)
        val alt = asin((sin(obsLat) * sin(decR) + cos(obsLat) * cos(decR) * cos(ha)).coerceIn(-1.0, 1.0))
        val az = atan2(sin(ha), cos(ha) * sin(obsLat) - tan(decR) * cos(obsLat))
        return Position(norm(Math.toDegrees(az) + 180.0), Math.toDegrees(alt), apparentScale)
    }

    private fun julianDay(timeMs: Long): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        var y = cal.get(Calendar.YEAR)
        var m = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0 + cal.get(Calendar.SECOND) / 3600.0
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5 + hour / 24.0
    }

    private fun sind(v: Double) = sin(Math.toRadians(v))
    private fun cosd(v: Double) = cos(Math.toRadians(v))
    private fun norm(v: Double): Double = ((v % 360.0) + 360.0) % 360.0
    private fun signed(v: Double): Double = ((v + 540.0) % 360.0) - 180.0
}
