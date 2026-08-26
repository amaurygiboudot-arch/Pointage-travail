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
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Contrôleur de transition vers l'architecture céleste unique.
 *
 * Pendant la migration, l'ancienne API LightingState reste disponible pour ne rien
 * casser dans les vues existantes. En parallèle, chaque calcul publie désormais un
 * CelestialSnapshot complet et atomique dans CelestialStateStore.
 */
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

    // Conservés provisoirement pour ne pas modifier le comportement visuel avant
    // que les consommateurs soient migrés sur la matrice 3D complète.
    private const val MIN_RENDER_INTERVAL_MS = 90L
    private const val MIN_ORIENTATION_DELTA = 0.8f
    private const val AZIMUTH_DEAD_ZONE_DEG = 2.5f
    private const val AZIMUTH_SMOOTHING = 0.35f

    private fun releaseRegistration(registration: Registration) {
        registration.manager.unregisterListener(registration.listener)
        mainHandler.removeCallbacks(registration.ticker)
    }

    private fun detachOtherActivities(activeKey: Int) {
        val obsoleteKeys = registrations.keys.filter { it != activeKey }
        obsoleteKeys.forEach { key -> registrations.remove(key)?.let(::releaseRegistration) }
    }

    fun attach(activity: Activity, onLightingChanged: (LightingState) -> Unit) {
        val key = System.identityHashCode(activity)
        detachOtherActivities(key)
        if (registrations.containsKey(key)) return

        val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var target = -55f
        var celestialAngle: Float? = null
        var celestialAzimuth: Double? = null
        var locationBased = false
        var night = isNight(activity)
        var intensity = if (night) .24f else .78f
        var elevation = if (night) 25f else 45f
        var azimuth = 0f
        var filteredAzimuth = Float.NaN
        var pitch = 0f
        var roll = 0f
        var lastEmitMs = 0L
        var lastEmittedAngle = Float.NaN
        var lastEmittedPitch = Float.NaN
        var lastEmittedRoll = Float.NaN

        var orientationState = DeviceOrientationState.IDENTITY
        var sunPosition: CelestialEphemeris.Position? = null
        var moonPosition: CelestialEphemeris.Position? = null
        var referenceLocation: Location? = null
        var astronomyTimestampMs = 0L

        fun stabilizeAzimuth(raw: Float): Float {
            val normalizedRaw = normalize(raw)
            if (filteredAzimuth.isNaN()) {
                filteredAzimuth = normalizedRaw
                return filteredAzimuth
            }
            val delta = shortestDelta(filteredAzimuth, normalizedRaw)
            if (abs(delta) < AZIMUTH_DEAD_ZONE_DEG) return filteredAzimuth
            filteredAzimuth = normalize(filteredAzimuth + delta * AZIMUTH_SMOOTHING)
            return filteredAzimuth
        }

        fun locationConfidence(location: Location?, now: Long): LocationConfidence {
            location ?: return LocationConfidence.NONE
            val ageMs = (now - location.time).coerceAtLeast(0L)
            return when {
                ageMs <= 5 * 60_000L && (!location.hasAccuracy() || location.accuracy <= 250f) -> LocationConfidence.FRESH
                ageMs <= 6 * 60 * 60_000L -> LocationConfidence.USABLE
                else -> LocationConfidence.STALE
            }
        }

        fun bodyState(
            position: CelestialEphemeris.Position?,
            opticalIntensity: Float
        ): CelestialBodyState = if (position == null) {
            CelestialBodyState.EMPTY
        } else {
            CelestialBodyState(
                azimuthDeg = position.azimuth,
                altitudeDeg = position.altitude,
                apparentScale = position.apparentScale,
                opticalIntensity = opticalIntensity.coerceIn(0f, 1f),
                available = true
            )
        }

        fun screenDirection(position: CelestialEphemeris.Position?): ScreenDirection? {
            position ?: return null
            val delta = Math.toRadians(shortestDelta(orientationState.azimuthDeg, position.azimuth.toFloat()).toDouble())
            return ScreenDirection(
                x = sin(delta).toFloat(),
                y = -cos(delta).toFloat()
            )
        }

        fun publishSnapshot() {
            val now = if (astronomyTimestampMs > 0L) astronomyTimestampMs else System.currentTimeMillis()
            val sun = sunPosition
            val moon = moonPosition
            val sunIntensity = if (sun == null) 0f else {
                ((sun.altitude + 6.0) / 58.0).toFloat().coerceIn(0f, 1f)
            }
            val moonIntensity = if (moon == null) 0f else {
                ((moon.altitude + 10.0) / 45.0).toFloat().coerceIn(0f, .42f)
            }
            val loc = referenceLocation
            CelestialStateStore.publish(
                CelestialSnapshot(
                    timestampMs = now,
                    sun = bodyState(sun, sunIntensity),
                    moon = bodyState(moon, moonIntensity),
                    isNight = night,
                    location = loc?.let {
                        CelestialLocationState(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            accuracyMeters = if (it.hasAccuracy()) it.accuracy else null,
                            fixTimeMs = it.time
                        )
                    },
                    orientation = orientationState,
                    locationConfidence = locationConfidence(loc, now),
                    sunScreenDirection = screenDirection(sun),
                    moonScreenDirection = screenDirection(moon)
                )
            )
        }

        fun state(angle: Float): LightingState {
            // Ancien pont conservé pendant la migration. Il disparaîtra lorsque le
            // moteur 80 facettes consommera directement CelestialStateStore.
            val diamondPitch = pitch.coerceIn(-55f, 55f)
            val diamondRoll = roll.coerceIn(-55f, 55f)
            val diamondIntensity = 1f
            val diamondElevation = elevation.coerceIn(if (night) 12f else 20f, 90f)

            RedDiamondFinalButton.updateGlobalNaturalLight(
                angle,
                diamondPitch,
                diamondRoll,
                diamondIntensity,
                false,
                diamondElevation
            )
            return LightingState(angle, celestialAngle, elevation, night, azimuth, pitch)
        }

        fun updateTargetFromOrientation() {
            if (locationBased && celestialAzimuth != null) {
                val screen = screenAngle(azimuth, celestialAzimuth!!)
                celestialAngle = screen
                target = screen
            } else {
                celestialAngle = normalize(-azimuth)
                target = celestialAngle!!
            }
        }

        fun recomputeCelestial() {
            val now = System.currentTimeMillis()
            val loc = lastKnownLocation(activity)
            astronomyTimestampMs = now
            referenceLocation = loc
            if (loc != null) {
                val sun = CelestialEphemeris.sun(loc.latitude, loc.longitude, now)
                val moon = CelestialEphemeris.moon(loc.latitude, loc.longitude, now)
                sunPosition = sun
                moonPosition = moon
                night = sun.altitude < -0.833
                val active = if (night) moon else sun
                locationBased = true
                celestialAzimuth = active.azimuth
                elevation = active.altitude.toFloat().coerceIn(-10f, 90f)
                intensity = if (night) {
                    ((active.altitude + 10.0) / 45.0).toFloat().coerceIn(.18f, .42f)
                } else {
                    ((sun.altitude + 6.0) / 58.0).toFloat().coerceIn(.38f, 1f)
                }
            } else {
                // Le comportement visuel historique reste provisoirement identique.
                // Le remplacement par le dernier snapshot fiable interviendra dans
                // l'étape de migration du fallback.
                sunPosition = null
                moonPosition = null
                locationBased = false
                celestialAzimuth = null
                night = fallbackNightByClock()
                elevation = if (night) 25f else 45f
                intensity = if (night) .24f else .72f
            }
            updateTargetFromOrientation()
            publishSnapshot()
        }

        fun emit(force: Boolean = false) {
            updateTargetFromOrientation()
            val now = SystemClock.uptimeMillis()
            val angleDelta = if (lastEmittedAngle.isNaN()) 360f else abs(shortestDelta(lastEmittedAngle, target))
            val pitchDelta = if (lastEmittedPitch.isNaN()) 180f else abs(lastEmittedPitch - pitch)
            val rollDelta = if (lastEmittedRoll.isNaN()) 180f else abs(lastEmittedRoll - roll)

            if (!force) {
                if (now - lastEmitMs < MIN_RENDER_INTERVAL_MS) return
                if (angleDelta < MIN_ORIENTATION_DELTA && pitchDelta < MIN_ORIENTATION_DELTA && rollDelta < MIN_ORIENTATION_DELTA) return
            }

            lastEmitMs = now
            lastEmittedAngle = target
            lastEmittedPitch = pitch
            lastEmittedRoll = roll
            publishSnapshot()
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
                        val rawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        azimuth = stabilizeAzimuth(rawAzimuth)
                        pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                        roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                        orientationState = DeviceOrientationState.fromRotationMatrix(
                            rotation,
                            azimuthDeg = azimuth,
                            pitchDeg = pitch,
                            rollDeg = roll
                        )
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val ax = event.values[0]
                        val ay = event.values[1]
                        val az = event.values[2]
                        pitch = Math.toDegrees(atan2((-ay).toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
                        roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
                        orientationState = DeviceOrientationState.IDENTITY.copy(
                            azimuthDeg = azimuth,
                            pitchDeg = pitch,
                            rollDeg = roll
                        )
                    }
                }
                emit()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        lateinit var ticker: Runnable
        ticker = object : Runnable {
            override fun run() {
                recomputeCelestial()
                emit(force = true)
                mainHandler.postDelayed(this, 30_000L)
            }
        }

        if (rotationSensor != null) {
            sm.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (accelSensor != null) {
            sm.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }

        recomputeCelestial()
        emit(force = true)
        mainHandler.postDelayed(ticker, 30_000L)
        registrations[key] = Registration(sm, listener, ticker)
    }

    fun detach(activity: Activity) {
        val r = registrations.remove(System.identityHashCode(activity)) ?: return
        releaseRegistration(r)
    }

    fun isNight(context: Context): Boolean {
        val l = lastKnownLocation(context)
        return if (l != null) CelestialEphemeris.sun(l.latitude, l.longitude).altitude < -0.833 else fallbackNightByClock()
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
            m.getProviders(true).mapNotNull { p -> runCatching { m.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun screenAngle(deviceAzimuth: Float, celestialAzimuth: Double) =
        normalize(shortestDelta(deviceAzimuth, celestialAzimuth.toFloat()))

    private fun normalize(v: Float) = ((v % 360f) + 360f) % 360f
    private fun shortestDelta(from: Float, to: Float) = ((to - from + 540f) % 360f) - 180f
}
