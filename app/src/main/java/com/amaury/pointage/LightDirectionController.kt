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
import kotlin.math.atan2
import kotlin.math.sqrt

object LightDirectionController {
    data class LightingState(
        val lightAngle: Float,
        val celestialAngle: Float?,
        val celestialElevation: Float,
        val night: Boolean,
        val deviceAzimuth: Float,
        val devicePitch: Float
    )

    private data class Registration(
        val manager: SensorManager,
        val listener: SensorEventListener,
        val ticker: Runnable
    )

    private val registrations = mutableMapOf<Int, Registration>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity, onLightingChanged: (LightingState) -> Unit) {
        val key = System.identityHashCode(activity)
        if (registrations.containsKey(key)) return

        val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var target = -55f
        var celestialAngle: Float? = null
        var night = isNight(activity)
        var intensity = if (night) .24f else .78f
        var elevation = if (night) 25f else 45f
        var azimuth = 0f
        var pitch = 0f
        var roll = 0f

        fun state(angle: Float): LightingState {
            CarbonCompositeDrawable.updateGlobalLight(angle, night)
            val diamondPitch = pitch.coerceIn(-55f, 55f)
            val diamondRoll = roll.coerceIn(-55f, 55f)
            val diamondIntensity = if (night) intensity.coerceIn(.34f, .48f) else intensity.coerceIn(.72f, 1f)
            val diamondElevation = elevation.coerceIn(if (night) 12f else 20f, 90f)
            RedDiamondFinalButton.updateGlobalNaturalLight(
                angle,
                diamondPitch,
                diamondRoll,
                diamondIntensity,
                night,
                diamondElevation
            )
            return LightingState(angle, celestialAngle, elevation, night, azimuth, pitch)
        }

        fun recompute() {
            val now = System.currentTimeMillis()
            val loc = lastKnownLocation(activity)
            if (loc != null) {
                val sun = CelestialEphemeris.sun(loc.latitude, loc.longitude, now)
                val moon = CelestialEphemeris.moon(loc.latitude, loc.longitude, now)
                night = sun.altitude < -0.833
                val active = if (night) moon else sun
                elevation = active.altitude.toFloat().coerceIn(-10f, 90f)
                val screen = screenAngle(azimuth, active.azimuth)
                celestialAngle = screen
                intensity = if (night) {
                    ((active.altitude + 10.0) / 45.0).toFloat().coerceIn(.18f, .42f)
                } else {
                    ((sun.altitude + 6.0) / 58.0).toFloat().coerceIn(.38f, 1f)
                }
                val tilt = if (night) roll * .18f + pitch * .08f else roll * .30f + pitch * .12f
                target = normalize(screen + tilt)
            } else {
                night = fallbackNightByClock()
                elevation = if (night) 25f else 45f
                celestialAngle = normalize(-azimuth - 90f)
                intensity = if (night) .24f else .72f
                target = normalize(celestialAngle!! + roll * .24f + pitch * .10f)
            }
        }

        fun emitImmediately() {
            recompute()
            onLightingChanged(state(target))
        }

        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        SensorManager.getOrientation(rotation, orientation)
                        azimuth = normalize(Math.toDegrees(orientation[0].toDouble()).toFloat())
                        pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                        roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        val ax = event.values[0]
                        val ay = event.values[1]
                        val az = event.values[2]
                        pitch = Math.toDegrees(atan2((-ay).toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
                        roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
                    }
                }
                emitImmediately()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        lateinit var ticker: Runnable
        ticker = object : Runnable {
            override fun run() {
                emitImmediately()
                mainHandler.postDelayed(this, 30_000L)
            }
        }

        if (rotationSensor != null) {
            sm.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST)
        } else if (accelSensor != null) {
            sm.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        emitImmediately()
        mainHandler.postDelayed(ticker, 30_000L)
        registrations[key] = Registration(sm, listener, ticker)
    }

    fun detach(activity: Activity) {
        val r = registrations.remove(System.identityHashCode(activity)) ?: return
        r.manager.unregisterListener(r.listener)
        mainHandler.removeCallbacks(r.ticker)
    }

    fun isNight(context: Context): Boolean {
        val l = lastKnownLocation(context)
        return if (l != null) {
            CelestialEphemeris.sun(l.latitude, l.longitude).altitude < -0.833
        } else {
            fallbackNightByClock()
        }
    }

    private fun fallbackNightByClock(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h < 7 || h >= 20
    }

    private fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val m = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            m.getProviders(true)
                .mapNotNull { p -> runCatching { m.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun screenAngle(deviceAzimuth: Float, celestialAzimuth: Double) =
        normalize(shortestDelta(deviceAzimuth, celestialAzimuth.toFloat()) - 90f)

    private fun normalize(v: Float) = ((v % 360f) + 360f) % 360f

    private fun shortestDelta(from: Float, to: Float) = ((to - from + 540f) % 360f) - 180f
}
