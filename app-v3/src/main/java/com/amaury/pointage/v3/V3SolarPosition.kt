package com.amaury.pointage.v3

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Calcul solaire local suffisamment précis pour l'UI, sans service réseau. */
object V3SolarPosition {
    data class Position(val azimuthDeg: Float, val altitudeDeg: Float)

    fun calculate(timeMillis: Long, latitude: Double, longitude: Double): Position {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMillis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0 + cal.get(Calendar.SECOND) / 3600.0

        val jd = julianDay(year, month, day, hour)
        val n = jd - 2451545.0

        val meanLongitude = normalize(280.460 + 0.9856474 * n)
        val meanAnomaly = normalize(357.528 + 0.9856003 * n)
        val g = Math.toRadians(meanAnomaly)
        val eclipticLongitude = Math.toRadians(normalize(meanLongitude + 1.915 * sin(g) + 0.020 * sin(2.0 * g)))
        val obliquity = Math.toRadians(23.439 - 0.0000004 * n)

        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))

        val gmst = normalize(280.46061837 + 360.98564736629 * (jd - 2451545.0))
        val lst = Math.toRadians(normalize(gmst + longitude))
        val hourAngle = normalizeRad(lst - rightAscension)
        val lat = Math.toRadians(latitude)

        val sinAlt = (sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        val altitude = asin(sinAlt)

        val cosAz = ((sin(declination) - sin(altitude) * sin(lat)) / (cos(altitude) * cos(lat))).coerceIn(-1.0, 1.0)
        var azimuth = acos(cosAz)
        if (sin(hourAngle) > 0) azimuth = 2.0 * Math.PI - azimuth

        return Position(Math.toDegrees(azimuth).toFloat(), Math.toDegrees(altitude).toFloat())
    }

    private fun julianDay(year: Int, month: Int, day: Int, hourUtc: Double): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        return kotlin.math.floor(365.25 * (y + 4716)) + kotlin.math.floor(30.6001 * (m + 1)) + day + b - 1524.5 + hourUtc / 24.0
    }

    private fun normalize(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeRad(value: Double): Double {
        var v = value % (2.0 * Math.PI)
        if (v > Math.PI) v -= 2.0 * Math.PI
        if (v < -Math.PI) v += 2.0 * Math.PI
        return v
    }
}
