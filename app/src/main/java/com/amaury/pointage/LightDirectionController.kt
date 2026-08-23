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

/**
 * Contrôleur unique de l'éclairage céleste.
 * Jour : le Soleil est la source. Nuit : la Lune.
 * La direction réelle est projetée sur l'écran puis corrigée par l'inclinaison
 * du téléphone. Les trois diamants de pointage reçoivent aussi l'intensité.
 */
object LightDirectionController {
    data class LightingState(
        val lightAngle: Float,
        val celestialAngle: Float?,
        val night: Boolean,
        val deviceAzimuth: Float,
        val devicePitch: Float
    )

    private data class Registration(
        val manager: SensorManager,
        val listener: SensorEventListener,
        val animator: Runnable,
        val ticker: Runnable
    )

    private val registrations = mutableMapOf<Int, Registration>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity, onLightingChanged: (LightingState) -> Unit) {
        val key = System.identityHashCode(activity)
        if (registrations.containsKey(key)) return

        val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var targetLightAngle = -55f
        var displayedLightAngle = -55f
        var celestialAngle: Float? = null
        var currentNight = isNight(activity)
        var currentIntensity = if (currentNight) .24f else .78f
        var animationRunning = false
        var deviceAzimuth = 0f
        var pitch = 0f
        var roll = 0f
        var ax = 0f
        var ay = 0f
        var az = 9.81f

        fun state(angle: Float): LightingState {
            CarbonCompositeDrawable.updateGlobalLight(angle, currentNight)
            RedDiamondFinalButton.updateGlobalNaturalLight(
                angle = angle,
                pitch = pitch,
                roll = roll,
                intensity = currentIntensity,
                night = currentNight
            )
            return LightingState(
                lightAngle = angle,
                celestialAngle = celestialAngle,
                night = currentNight,
                deviceAzimuth = deviceAzimuth,
                devicePitch = pitch
            )
        }

        fun recomputeCelestial() {
            val now = System.currentTimeMillis()
            val location = lastKnownLocation(activity)

            if (location != null) {
                val sun = CelestialEphemeris.sun(location.latitude, location.longitude, now)
                val moon = CelestialEphemeris.moon(location.latitude, location.longitude, now)
                currentNight = sun.altitude < -0.833
                val active = if (currentNight) moon else sun
                val activeScreenAngle = screenAngle(deviceAzimuth, active.azimuth)
                celestialAngle = activeScreenAngle

                // Le soleil haut produit des éclats francs. Près de l'horizon,
                // la lumière reste visible mais plus douce. La lune reste subtile.
                currentIntensity = if (currentNight) {
                    ((active.altitude + 10.0) / 45.0).toFloat().coerceIn(.18f, .42f)
                } else {
                    ((sun.altitude + 6.0) / 58.0).toFloat().coerceIn(.38f, 1f)
                }

                val tiltInfluence = if (currentNight) {
                    roll * .18f + pitch * .08f
                } else {
                    roll * .30f + pitch * .12f
                }
                targetLightAngle = normalize(activeScreenAngle + tiltInfluence)
            } else {
                currentNight = fallbackNightByClock()
                celestialAngle = normalize(-deviceAzimuth - 90f)
                currentIntensity = if (currentNight) .24f else .72f
                val tiltInfluence = roll * .24f + pitch * .10f
                targetLightAngle = normalize(celestialAngle!! + tiltInfluence)
            }
        }

        lateinit var animator: Runnable
        animator = object : Runnable {
            override fun run() {
                val delta = shortestDelta(displayedLightAngle, targetLightAngle)
                val absDelta = kotlin.math.abs(delta)
                val factor = when {
                    absDelta > 90f -> .18f
                    absDelta > 45f -> .15f
                    absDelta > 15f -> .12f
                    else -> .09f
                }
                val maxStep = when {
                    absDelta > 90f -> 9f
                    absDelta > 45f -> 6f
                    else -> 3.8f
                }

                displayedLightAngle = normalize(displayedLightAngle + (delta * factor).coerceIn(-maxStep, maxStep))
                onLightingChanged(state(displayedLightAngle))

                if (absDelta < .35f) {
                    displayedLightAngle = targetLightAngle
                    onLightingChanged(state(displayedLightAngle))
                    animationRunning = false
                } else {
                    mainHandler.postDelayed(this, 16L)
                }
            }
        }

        fun scheduleAnimation() {
            if (!animationRunning) {
                animationRunning = true
                mainHandler.post(animator)
            }
        }

        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        SensorManager.getOrientation(rotation, orientation)
                        deviceAzimuth = normalize(Math.toDegrees(orientation[0].toDouble()).toFloat())
                        pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                        roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val k = .14f
                        ax += (event.values[0] - ax) * k
                        ay += (event.values[1] - ay) * k
                        az += (event.values[2] - az) * k
                        pitch = Math.toDegrees(atan2((-ay).toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
                        roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
                    }
                }
                recomputeCelestial()
                scheduleAnimation()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        lateinit var ticker: Runnable
        ticker = object : Runnable {
            override fun run() {
                recomputeCelestial()
                scheduleAnimation()
                mainHandler.postDelayed(this, 30_000L)
            }
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        recomputeCelestial()
        onLightingChanged(state(targetLightAngle))
        mainHandler.postDelayed(ticker, 30_000L)
        registrations[key] = Registration(sensorManager, listener, animator, ticker)
    }

    fun detach(activity: Activity) {
        val key = System.identityHashCode(activity)
        val registration = registrations.remove(key) ?: return
        registration.manager.unregisterListener(registration.listener)
        mainHandler.removeCallbacks(registration.animator)
        mainHandler.removeCallbacks(registration.ticker)
    }

    fun isNight(context: Context): Boolean {
        val location = lastKnownLocation(context)
        return if (location != null) {
            CelestialEphemeris.sun(location.latitude, location.longitude).altitude < -0.833
        } else fallbackNightByClock()
    }

    private fun fallbackNightByClock(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 7 || hour >= 20
    }

    private fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun screenAngle(deviceAzimuth: Float, celestialAzimuth: Double): Float {
        val relativeBearing = shortestDelta(deviceAzimuth, celestialAzimuth.toFloat())
        return normalize(relativeBearing - 90f)
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
}
