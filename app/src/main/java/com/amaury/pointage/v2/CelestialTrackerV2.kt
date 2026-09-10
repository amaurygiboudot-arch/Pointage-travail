package com.amaury.pointage.v2

import android.Manifest
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
import com.amaury.pointage.v2.engine.CelestialLocationQualityV2
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import com.amaury.pointage.v2.engine.CelestialTrackingPolicyV2
import kotlin.math.abs

/**
 * Acquisition Android unique du suivi céleste V2.
 *
 * Le moteur astronomique reste pur dans CelestialEngineV2. Cette classe ne fait
 * que centraliser la dernière position qualifiée, l'orientation du téléphone et
 * le rafraîchissement du snapshot, afin que la vue céleste et l'éclairage UI ne
 * créent plus chacun leurs propres abonnements GPS/capteurs.
 */
object CelestialTrackerV2 {

    data class State(
        val snapshot: CelestialSnapshotV2?,
        val locationQuality: CelestialLocationQualityV2,
        val locationAgeMs: Long?,
        val locationAccuracyMeters: Float?,
        val locationProvider: String?,
        val deviceAzimuthDeg: Float,
        val devicePitchDeg: Float,
        val deviceRollDeg: Float
    ) {
        val hasRealSky: Boolean
            get() = snapshot != null && locationQuality == CelestialLocationQualityV2.VALID
    }

    private val observers = LinkedHashMap<Any, (State) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null

    private var snapshot: CelestialSnapshotV2? = null
    private var locationQuality = CelestialLocationQualityV2.UNAVAILABLE
    private var locationAgeMs: Long? = null
    private var locationAccuracyMeters: Float? = null
    private var locationProvider: String? = null

    private var deviceAzimuthDeg = 0f
    private var filteredAzimuthDeg = Float.NaN
    private var devicePitchDeg = 0f
    private var deviceRollDeg = 0f

    private var accelValues: FloatArray? = null
    private var magneticValues: FloatArray? = null

    private var lastEmitUptimeMs = 0L
    private var lastEmittedAzimuth = Float.NaN
    private var lastEmittedPitch = Float.NaN
    private var lastEmittedRoll = Float.NaN

    private const val CELESTIAL_REFRESH_MS = 30_000L
    private const val MIN_RENDER_INTERVAL_MS = 90L
    private const val MIN_ORIENTATION_DELTA_DEG = 0.8f
    private const val AZIMUTH_DEAD_ZONE_DEG = 2.5f
    private const val AZIMUTH_SMOOTHING = 0.35f

    private val refreshTask = object : Runnable {
        override fun run() {
            if (observers.isEmpty()) return
            refreshLocationAndAstronomy(notify = true)
            mainHandler.postDelayed(this, CELESTIAL_REFRESH_MS)
        }
    }

    fun subscribe(context: Context, key: Any, observer: (State) -> Unit) {
        ensureContext(context)
        observers[key] = observer
        if (observers.size == 1) {
            startSensors()
            refreshLocationAndAstronomy(notify = false)
            mainHandler.removeCallbacks(refreshTask)
            mainHandler.postDelayed(refreshTask, CELESTIAL_REFRESH_MS)
        }
        observer(currentStateInternal())
    }

    fun unsubscribe(key: Any) {
        observers.remove(key)
        if (observers.isEmpty()) stopSensorsAndTicker()
    }

    /**
     * Lecture synchrone utile pour le thème jour/nuit avant qu'une vue ne soit
     * abonnée. Toute acquisition de localisation reste centralisée ici.
     */
    fun currentState(context: Context): State {
        ensureContext(context)
        if (observers.isEmpty()) refreshLocationAndAstronomy(notify = false)
        return currentStateInternal()
    }

    private fun ensureContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun startSensors() {
        val context = appContext ?: return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = manager

        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magneticSensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        updateOrientation(orientation)
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        accelValues = event.values.copyOf()
                        updateFallbackOrientation(rotationMatrix, orientation)
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magneticValues = event.values.copyOf()
                        updateFallbackOrientation(rotationMatrix, orientation)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListener = listener

        if (rotationSensor != null) {
            manager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (accelSensor != null && magneticSensor != null) {
            manager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
            manager.registerListener(listener, magneticSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun updateFallbackOrientation(rotationMatrix: FloatArray, orientation: FloatArray) {
        val accel = accelValues ?: return
        val magnetic = magneticValues ?: return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accel, magnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientation)
            updateOrientation(orientation)
        }
    }

    private fun updateOrientation(orientation: FloatArray) {
        val rawAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        deviceAzimuthDeg = stabilizeAzimuth(rawAzimuth)
        devicePitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat().coerceIn(-90f, 90f)
        deviceRollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat().coerceIn(-90f, 90f)
        emitOrientationIfNeeded()
    }

    private fun stabilizeAzimuth(raw: Float): Float {
        val normalizedRaw = normalize(raw)
        if (filteredAzimuthDeg.isNaN()) {
            filteredAzimuthDeg = normalizedRaw
            return filteredAzimuthDeg
        }
        val delta = shortestDelta(filteredAzimuthDeg, normalizedRaw)
        if (abs(delta) < AZIMUTH_DEAD_ZONE_DEG) return filteredAzimuthDeg
        filteredAzimuthDeg = normalize(filteredAzimuthDeg + delta * AZIMUTH_SMOOTHING)
        return filteredAzimuthDeg
    }

    private fun emitOrientationIfNeeded() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEmitUptimeMs < MIN_RENDER_INTERVAL_MS) return

        val azimuthDelta = if (lastEmittedAzimuth.isNaN()) 360f else abs(shortestDelta(lastEmittedAzimuth, deviceAzimuthDeg))
        val pitchDelta = if (lastEmittedPitch.isNaN()) 180f else abs(lastEmittedPitch - devicePitchDeg)
        val rollDelta = if (lastEmittedRoll.isNaN()) 180f else abs(lastEmittedRoll - deviceRollDeg)
        if (azimuthDelta < MIN_ORIENTATION_DELTA_DEG &&
            pitchDelta < MIN_ORIENTATION_DELTA_DEG &&
            rollDelta < MIN_ORIENTATION_DELTA_DEG
        ) return

        lastEmitUptimeMs = now
        lastEmittedAzimuth = deviceAzimuthDeg
        lastEmittedPitch = devicePitchDeg
        lastEmittedRoll = deviceRollDeg
        notifyObservers()
    }

    private fun refreshLocationAndAstronomy(notify: Boolean) {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val hasPermission = hasLocationPermission(context)
        val location = if (hasPermission) bestLastKnownLocation(context) else null
        val accuracy = location?.takeIf { it.hasAccuracy() }?.accuracy

        locationQuality = CelestialTrackingPolicyV2.classify(
            hasPermission = hasPermission,
            hasLocation = location != null,
            nowMs = now,
            locationTimeMs = location?.time,
            accuracyMeters = accuracy
        )
        locationAgeMs = location?.time?.let { now - it }
        locationAccuracyMeters = accuracy
        locationProvider = location?.provider

        snapshot = if (locationQuality == CelestialLocationQualityV2.VALID && location != null && HoraTrackV2.ENABLED) {
            runCatching {
                HoraTrackV2.celestial.snapshot(
                    latitudeDeg = location.latitude,
                    longitudeDeg = location.longitude,
                    timeMs = now,
                    observerAltitudeMeters = if (location.hasAltitude()) location.altitude else 0.0
                )
            }.getOrNull()
        } else {
            null
        }

        if (notify) notifyObservers()
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun bestLastKnownLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun currentStateInternal(): State = State(
        snapshot = snapshot,
        locationQuality = locationQuality,
        locationAgeMs = locationAgeMs,
        locationAccuracyMeters = locationAccuracyMeters,
        locationProvider = locationProvider,
        deviceAzimuthDeg = deviceAzimuthDeg,
        devicePitchDeg = devicePitchDeg,
        deviceRollDeg = deviceRollDeg
    )

    private fun notifyObservers() {
        val state = currentStateInternal()
        observers.values.toList().forEach { observer -> observer(state) }
    }

    private fun stopSensorsAndTicker() {
        sensorListener?.let { listener -> sensorManager?.unregisterListener(listener) }
        sensorListener = null
        sensorManager = null
        accelValues = null
        magneticValues = null
        mainHandler.removeCallbacks(refreshTask)
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f

    private fun shortestDelta(from: Float, to: Float): Float =
        ((to - from + 540f) % 360f) - 180f
}
