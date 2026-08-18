package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

object LightDirectionController {
    private data class Registration(
        val manager: SensorManager,
        val listener: SensorEventListener,
        val animator: Runnable
    )

    private data class SolarPosition(val azimuth: Double, val altitude: Double)

    private val registrations = mutableMapOf<Int, Registration>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity, onLightingChanged: (Float, Boolean) -> Unit) {
        val key = System.identityHashCode(activity)
        if (registrations.containsKey(key)) return

        val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        val location = lastKnownLocation(activity)

        var targetAngle = Float.NaN
        var displayedAngle = Float.NaN
        var currentNight = computeNightMode(activity, location)
        var animationRunning = false

        lateinit var animator: Runnable
        animator = object : Runnable {
            override fun run() {
                if (targetAngle.isNaN()) {
                    animationRunning = false
                    return
                }

                if (displayedAngle.isNaN()) displayedAngle = targetAngle

                val delta = shortestDelta(displayedAngle, targetAngle)
                val absDelta = kotlin.math.abs(delta)
                val factor = when {
                    absDelta > 90f -> 0.18f
                    absDelta > 45f -> 0.15f
                    absDelta > 15f -> 0.12f
                    else -> 0.09f
                }
                val maxStep = when {
                    absDelta > 90f -> 9f
                    absDelta > 45f -> 6f
                    else -> 3.8f
                }
                val step = (delta * factor).coerceIn(-maxStep, maxStep)

                displayedAngle = normalize(displayedAngle + step)
                onLightingChanged(displayedAngle, currentNight)

                if (absDelta < 0.35f) {
                    displayedAngle = targetAngle
                    onLightingChanged(displayedAngle, currentNight)
                    animationRunning = false
                } else {
                    mainHandler.postDelayed(this, 16L)
                }
            }
        }

        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val deviceAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

                val solar = location?.let { solarPosition(it.latitude, it.longitude, System.currentTimeMillis()) }
                val base = if (solar != null) solar.azimuth.toFloat() - deviceAzimuth else -deviceAzimuth
                val newNight = solar?.altitude?.let { it < -0.833 } ?: fallbackNightByClock()
                val tiltInfluence = (roll * 0.45f) + (pitch * 0.20f)
                val newTarget = normalize(base + tiltInfluence - 90f)

                if (newNight != currentNight) {
                    currentNight = newNight
                    if (!displayedAngle.isNaN()) onLightingChanged(displayedAngle, currentNight)
                }

                if (targetAngle.isNaN() || kotlin.math.abs(shortestDelta(targetAngle, newTarget)) >= 0.6f) {
                    targetAngle = newTarget
                    if (!animationRunning) {
                        animationRunning = true
                        mainHandler.post(animator)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        registrations[key] = Registration(sensorManager, listener, animator)
    }

    fun detach(activity: Activity) {
        val key = System.identityHashCode(activity)
        val registration = registrations.remove(key) ?: return
        registration.manager.unregisterListener(registration.listener)
        mainHandler.removeCallbacks(registration.animator)
    }

    fun isNight(context: Context): Boolean = computeNightMode(context, lastKnownLocation(context))

    private fun computeNightMode(context: Context, location: Location?): Boolean {
        val altitude = location?.let { solarPosition(it.latitude, it.longitude, System.currentTimeMillis()).altitude }
        return altitude?.let { it < -0.833 } ?: fallbackNightByClock()
    }

    private fun fallbackNightByClock(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 7 || hour >= 20
    }

    private fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            lm.getProviders(true)
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun solarPosition(latitude: Double, longitude: Double, timeMs: Long): SolarPosition {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0 + cal.get(Calendar.SECOND) / 3600.0

        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        val jd = kotlin.math.floor(365.25 * (y + 4716)) + kotlin.math.floor(30.6001 * (m + 1)) + day + b - 1524.5 + hour / 24.0
        val t = (jd - 2451545.0) / 36525.0

        val l0 = normalizeDouble(280.46646 + t * (36000.76983 + t * 0.0003032))
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sinDeg(meanAnomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sinDeg(2 * meanAnomaly) * (0.019993 - 0.000101 * t) +
            sinDeg(3 * meanAnomaly) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sinDeg(omega)
        val epsilon0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val epsilon = epsilon0 + 0.00256 * cosDeg(omega)
        val decl = asin(sinDeg(epsilon) * sinDeg(lambda))

        val yTerm = tan(Math.toRadians(epsilon / 2.0)).let { it * it }
        val eqTime = 4.0 * Math.toDegrees(
            yTerm * sin(2 * Math.toRadians(l0)) - 2 * e * sin(Math.toRadians(meanAnomaly)) +
                4 * e * yTerm * sin(Math.toRadians(meanAnomaly)) * cos(2 * Math.toRadians(l0)) -
                0.5 * yTerm * yTerm * sin(4 * Math.toRadians(l0)) -
                1.25 * e * e * sin(2 * Math.toRadians(meanAnomaly))
        )

        val trueSolarMinutes = ((hour * 60.0 + eqTime + 4.0 * longitude) % 1440.0 + 1440.0) % 1440.0
        val hourAngleDeg = trueSolarMinutes / 4.0 - 180.0
        val ha = Math.toRadians(hourAngleDeg)
        val lat = Math.toRadians(latitude)

        val az = atan2(sin(ha), cos(ha) * sin(lat) - tan(decl) * cos(lat))
        val altitude = asin(
            (sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha)).coerceIn(-1.0, 1.0)
        )

        return SolarPosition(
            azimuth = normalizeDouble(Math.toDegrees(az) + 180.0),
            altitude = Math.toDegrees(altitude)
        )
    }

    private fun sinDeg(v: Double) = sin(Math.toRadians(v))
    private fun cosDeg(v: Double) = cos(Math.toRadians(v))
    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun normalizeDouble(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f
}
