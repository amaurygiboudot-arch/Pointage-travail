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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Source unique de vérité céleste + orientation du téléphone.
 *
 * - une seule écoute Rotation Vector ;
 * - aucune zone morte angulaire ;
 * - matrice 3D complète conservée ;
 * - Soleil et Lune présents simultanément dans chaque snapshot ;
 * - ancien GPS fiable réutilisé si le provider devient momentanément indisponible ;
 * - aucun composant graphique ne recalcule l'astronomie.
 */
object CelestialController {
    private const val FRAME_INTERVAL_MS = 16L
    private const val ASTRONOMY_INTERVAL_MS = 15_000L

    private data class Registration(
        val sensorManager: SensorManager,
        val listener: SensorEventListener,
        val astronomyTicker: Runnable
    )

    private val registrations = mutableMapOf<Int, Registration>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(activity: Activity, onSnapshot: (CelestialSnapshot) -> Unit) {
        val key = System.identityHashCode(activity)
        detachOtherActivities(key)
        if (registrations.containsKey(key)) {
            onSnapshot(CelestialStateStore.current())
            return
        }

        val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var orientation = CelestialStateStore.current().orientation
        var referenceLocation = resolveReferenceLocation(activity)
        var sunPosition: CelestialEphemeris.Position? = null
        var moonPosition: CelestialEphemeris.Position? = null
        var lastAstronomyMs = 0L
        var lastEmitUptimeMs = 0L

        fun locationConfidence(location: CelestialLocationState?, nowMs: Long): LocationConfidence {
            location ?: return LocationConfidence.NONE
            val age = (nowMs - location.fixTimeMs).coerceAtLeast(0L)
            val accuracy = location.accuracyMeters
            return when {
                age <= 5 * 60_000L && (accuracy == null || accuracy <= 250f) -> LocationConfidence.FRESH
                age <= 6 * 60 * 60_000L -> LocationConfidence.USABLE
                else -> LocationConfidence.STALE
            }
        }

        fun refreshAstronomy(nowMs: Long = System.currentTimeMillis()) {
            val fresh = lastKnownLocation(activity)?.toState()
            if (fresh != null) referenceLocation = fresh
            if (referenceLocation == null) {
                referenceLocation = CelestialStateStore.current().location
            }

            val location = referenceLocation
            if (location != null) {
                sunPosition = CelestialEphemeris.sun(location.latitude, location.longitude, nowMs)
                moonPosition = CelestialEphemeris.moon(location.latitude, location.longitude, nowMs)
            } else {
                sunPosition = null
                moonPosition = null
            }
            lastAstronomyMs = nowMs
        }

        fun buildSnapshot(nowMs: Long): CelestialSnapshot {
            if (lastAstronomyMs == 0L || nowMs - lastAstronomyMs >= ASTRONOMY_INTERVAL_MS) {
                refreshAstronomy(nowMs)
            }

            val sun = sunPosition
            val moon = moonPosition
            val isNight = sun?.altitude?.let { it < -0.833 } ?: CelestialStateStore.current().isNight
            val moonIllumination = if (sun != null && moon != null) lunarIllumination(sun, moon) else 0f

            val sunState = if (sun == null) {
                CelestialBodyState.EMPTY
            } else {
                CelestialBodyState(
                    azimuthDeg = sun.azimuth,
                    altitudeDeg = sun.altitude,
                    apparentScale = sun.apparentScale,
                    opticalIntensity = solarOpticalIntensity(sun.altitude),
                    available = true
                )
            }

            val moonState = if (moon == null) {
                CelestialBodyState.EMPTY
            } else {
                CelestialBodyState(
                    azimuthDeg = moon.azimuth,
                    altitudeDeg = moon.altitude,
                    apparentScale = moon.apparentScale,
                    opticalIntensity = lunarOpticalIntensity(moon.altitude, moonIllumination),
                    available = true
                )
            }

            return CelestialSnapshot(
                timestampMs = nowMs,
                sun = sunState,
                moon = moonState,
                isNight = isNight,
                location = referenceLocation,
                orientation = orientation,
                locationConfidence = locationConfidence(referenceLocation, nowMs)
            )
        }

        fun publish(force: Boolean = false) {
            val uptime = SystemClock.uptimeMillis()
            if (!force && uptime - lastEmitUptimeMs < FRAME_INTERVAL_MS) return
            lastEmitUptimeMs = uptime
            val snapshot = buildSnapshot(System.currentTimeMillis())
            CelestialStateStore.publish(snapshot)
            onSnapshot(snapshot)
        }

        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientationAngles = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        SensorManager.getOrientation(rotation, orientationAngles)
                        orientation = DeviceOrientationState.fromRotationMatrix(
                            matrix = rotation,
                            azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat(),
                            pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat(),
                            rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                        )
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        // Secours uniquement lorsqu'aucun Rotation Vector n'existe.
                        val ax = event.values[0]
                        val ay = event.values[1]
                        val az = event.values[2]
                        val pitch = Math.toDegrees(
                            kotlin.math.atan2((-ay).toDouble(), kotlin.math.sqrt((ax * ax + az * az).toDouble()))
                        ).toFloat()
                        val roll = Math.toDegrees(kotlin.math.atan2(ax.toDouble(), az.toDouble())).toFloat()
                        orientation = DeviceOrientationState.IDENTITY.copy(
                            azimuthDeg = orientation.azimuthDeg,
                            pitchDeg = pitch,
                            rollDeg = roll
                        )
                    }
                }
                publish()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        lateinit var astronomyTicker: Runnable
        astronomyTicker = object : Runnable {
            override fun run() {
                refreshAstronomy()
                publish(force = true)
                mainHandler.postDelayed(this, ASTRONOMY_INTERVAL_MS)
            }
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelerometer != null) {
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }

        refreshAstronomy()
        publish(force = true)
        mainHandler.postDelayed(astronomyTicker, ASTRONOMY_INTERVAL_MS)
        registrations[key] = Registration(sensorManager, listener, astronomyTicker)
    }

    fun detach(activity: Activity) {
        val registration = registrations.remove(System.identityHashCode(activity)) ?: return
        release(registration)
    }

    fun current(): CelestialSnapshot = CelestialStateStore.current()

    private fun detachOtherActivities(activeKey: Int) {
        registrations.keys.filter { it != activeKey }.forEach { key ->
            registrations.remove(key)?.let(::release)
        }
    }

    private fun release(registration: Registration) {
        registration.sensorManager.unregisterListener(registration.listener)
        mainHandler.removeCallbacks(registration.astronomyTicker)
    }

    private fun resolveReferenceLocation(context: Context): CelestialLocationState? =
        lastKnownLocation(context)?.toState() ?: CelestialStateStore.current().location

    private fun lastKnownLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun Location.toState() = CelestialLocationState(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        fixTimeMs = time
    )

    /** Transition crépusculaire continue, sans interrupteur lumineux brutal. */
    private fun solarOpticalIntensity(altitudeDeg: Double): Float {
        val t = ((altitudeDeg + 12.0) / 42.0).toFloat().coerceIn(0f, 1f)
        return smoothStep(t)
    }

    /** La Lune dépend à la fois de son altitude et de sa fraction éclairée. */
    private fun lunarOpticalIntensity(altitudeDeg: Double, illumination: Float): Float {
        if (altitudeDeg <= -5.0) return 0f
        val altitudeFactor = ((altitudeDeg + 5.0) / 50.0).toFloat().coerceIn(0f, 1f)
        val phaseFactor = illumination.coerceIn(0f, 1f).powGamma(0.72f)
        return (0.42f * smoothStep(altitudeFactor) * phaseFactor).coerceIn(0f, 0.42f)
    }

    private fun lunarIllumination(
        sun: CelestialEphemeris.Position,
        moon: CelestialEphemeris.Position
    ): Float {
        val sunAz = Math.toRadians(sun.azimuth)
        val sunAlt = Math.toRadians(sun.altitude)
        val moonAz = Math.toRadians(moon.azimuth)
        val moonAlt = Math.toRadians(moon.altitude)
        val cosSeparation = (
            sin(sunAlt) * sin(moonAlt) +
                cos(sunAlt) * cos(moonAlt) * cos(sunAz - moonAz)
            ).coerceIn(-1.0, 1.0)
        val separation = acos(cosSeparation)
        return ((1.0 - cos(separation)) * 0.5).toFloat().coerceIn(0f, 1f)
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun Float.powGamma(gamma: Float): Float =
        kotlin.math.pow(this.toDouble(), gamma.toDouble()).toFloat()
}
