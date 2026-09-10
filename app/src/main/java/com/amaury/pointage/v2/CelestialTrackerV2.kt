package com.amaury.pointage.v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.amaury.pointage.v2.engine.CelestialDeviceFrameV2
import com.amaury.pointage.v2.engine.CelestialLocationQualityV2
import com.amaury.pointage.v2.engine.CelestialScreenGeometryV2
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import com.amaury.pointage.v2.engine.CelestialTrackingPolicyV2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Acquisition Android unique du suivi céleste V2.
 *
 * Cette couche centralise :
 * - localisation réelle, avec mises à jour tant qu'un consommateur est actif ;
 * - orientation du téléphone ;
 * - rotation réelle de l'écran ;
 * - correction Nord magnétique -> Nord vrai ;
 * - stabilisation du cap utilisé par tous les rendus célestes ;
 * - estimation de précision du cap quand TYPE_ROTATION_VECTOR la fournit ;
 * - snapshot astronomique V2.
 *
 * Le rendu n'a donc plus à deviner l'orientation ou la position de l'utilisateur.
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
        val deviceRollDeg: Float,
        val magneticDeclinationDeg: Float,
        /** Incertitude de cap annoncée par TYPE_ROTATION_VECTOR, en degrés. */
        val headingAccuracyDeg: Float?,
        val deviceFrame: CelestialDeviceFrameV2?
    ) {
        val hasRealSky: Boolean
            get() = snapshot != null &&
                locationQuality == CelestialLocationQualityV2.VALID &&
                deviceFrame != null
    }

    private val observers = LinkedHashMap<Any, (State) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    private var snapshot: CelestialSnapshotV2? = null
    private var locationQuality = CelestialLocationQualityV2.UNAVAILABLE
    private var locationAgeMs: Long? = null
    private var locationAccuracyMeters: Float? = null
    private var locationProvider: String? = null
    private var latestLiveLocation: Location? = null

    private var deviceAzimuthDeg = 0f
    private var filteredAzimuthDeg = Float.NaN
    private var devicePitchDeg = 0f
    private var deviceRollDeg = 0f
    private var magneticDeclinationDeg = 0f
    private var headingAccuracyDeg: Float? = null
    private var deviceFrame: CelestialDeviceFrameV2? = null
    private var lastDisplayRotationMatrix: FloatArray? = null

    private var accelValues: FloatArray? = null
    private var magneticValues: FloatArray? = null

    private var lastEmitUptimeMs = 0L
    private var lastEmittedAzimuth = Float.NaN
    private var lastEmittedPitch = Float.NaN
    private var lastEmittedRoll = Float.NaN

    private const val CELESTIAL_REFRESH_MS = 30_000L
    private const val LOCATION_MIN_TIME_MS = 30_000L
    private const val LOCATION_MIN_DISTANCE_M = 25f
    private const val MIN_RENDER_INTERVAL_MS = 90L
    private const val MIN_ORIENTATION_DELTA_DEG = 0.8f
    private const val AZIMUTH_DEAD_ZONE_DEG = 0.40f
    private const val AZIMUTH_SMOOTH_SMALL = 0.34f
    private const val AZIMUTH_SMOOTH_MEDIUM = 0.55f
    private const val AZIMUTH_SMOOTH_LARGE = 0.78f

    private val refreshTask = object : Runnable {
        override fun run() {
            if (observers.isEmpty()) return
            refreshLocationAndAstronomy(notify = true)
            mainHandler.postDelayed(this, CELESTIAL_REFRESH_MS)
        }
    }

    fun subscribe(context: Context, key: Any, observer: (State) -> Unit) {
        ensureContext(context)
        val wasEmpty = observers.isEmpty()
        observers[key] = observer
        if (wasEmpty) {
            startSensors()
            startLocationUpdates()
            refreshLocationAndAstronomy(notify = false)
            mainHandler.removeCallbacks(refreshTask)
            mainHandler.postDelayed(refreshTask, CELESTIAL_REFRESH_MS)
        }
        observer(currentStateInternal())
    }

    fun unsubscribe(key: Any) {
        observers.remove(key)
        if (observers.isEmpty()) stopAcquisitionAndTicker()
    }

    /**
     * Lecture synchrone utile au thème jour/nuit. Elle peut calculer le ciel à
     * partir de la position disponible, mais ne prétend pas avoir une attitude
     * écran réelle tant que les capteurs ne sont pas abonnés.
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
            private val rawRotationMatrix = FloatArray(9)
            private val displayRotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        headingAccuracyDeg = event.values.getOrNull(4)
                            ?.takeIf { it.isFinite() && it >= 0f }
                            ?.let { Math.toDegrees(it.toDouble()).toFloat() }

                        SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)
                        if (remapForDisplay(rawRotationMatrix, displayRotationMatrix)) {
                            SensorManager.getOrientation(displayRotationMatrix, orientation)
                            updateOrientation(displayRotationMatrix, orientation)
                        }
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        accelValues = event.values.copyOf()
                        updateFallbackOrientation(rawRotationMatrix, displayRotationMatrix, orientation)
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magneticValues = event.values.copyOf()
                        updateFallbackOrientation(rawRotationMatrix, displayRotationMatrix, orientation)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR &&
                    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                ) {
                    // La valeur angulaire reste disponible, mais l'état expose une
                    // précision inconnue afin de ne pas prétendre à une calibration.
                    headingAccuracyDeg = null
                }
            }
        }
        sensorListener = listener

        if (rotationSensor != null) {
            manager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (accelSensor != null && magneticSensor != null) {
            // Secours pour les appareils sans capteur de rotation fusionné.
            headingAccuracyDeg = null
            manager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
            manager.registerListener(listener, magneticSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun startLocationUpdates() {
        val context = appContext ?: return
        if (!hasLocationPermission(context)) return

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = manager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latestLiveLocation = location
                applyLocation(location, System.currentTimeMillis(), notify = true)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationListener = listener

        runCatching {
            manager.getProviders(true)
                .filter { it != LocationManager.PASSIVE_PROVIDER }
                .distinct()
                .forEach { provider ->
                    manager.requestLocationUpdates(
                        provider,
                        LOCATION_MIN_TIME_MS,
                        LOCATION_MIN_DISTANCE_M,
                        listener,
                        Looper.getMainLooper()
                    )
                }
        }
    }

    private fun updateFallbackOrientation(
        rawRotationMatrix: FloatArray,
        displayRotationMatrix: FloatArray,
        orientation: FloatArray
    ) {
        val accel = accelValues ?: return
        val magnetic = magneticValues ?: return
        if (SensorManager.getRotationMatrix(rawRotationMatrix, null, accel, magnetic) &&
            remapForDisplay(rawRotationMatrix, displayRotationMatrix)
        ) {
            SensorManager.getOrientation(displayRotationMatrix, orientation)
            updateOrientation(displayRotationMatrix, orientation)
        }
    }

    private fun remapForDisplay(input: FloatArray, output: FloatArray): Boolean {
        val rotation = currentDisplayRotation()
        val axisX: Int
        val axisY: Int
        when (rotation) {
            Surface.ROTATION_90 -> {
                axisX = SensorManager.AXIS_Y
                axisY = SensorManager.AXIS_MINUS_X
            }
            Surface.ROTATION_180 -> {
                axisX = SensorManager.AXIS_MINUS_X
                axisY = SensorManager.AXIS_MINUS_Y
            }
            Surface.ROTATION_270 -> {
                axisX = SensorManager.AXIS_MINUS_Y
                axisY = SensorManager.AXIS_X
            }
            else -> {
                axisX = SensorManager.AXIS_X
                axisY = SensorManager.AXIS_Y
            }
        }
        return SensorManager.remapCoordinateSystem(input, axisX, axisY, output)
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int {
        val context = appContext ?: return Surface.ROTATION_0
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    private fun updateOrientation(rotationMatrix: FloatArray, orientation: FloatArray) {
        lastDisplayRotationMatrix = rotationMatrix.copyOf()
        devicePitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat().coerceIn(-90f, 90f)
        deviceRollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat().coerceIn(-90f, 90f)

        // Le cap ne vient volontairement plus de l'Euler azimuth orientation[0].
        // Près d'une posture verticale, cet angle peut devenir numériquement
        // instable. On construit d'abord le frame Nord vrai, puis on en déduit le
        // prolongement horizontal du haut du cadran, déjà testé pour rester continu.
        val rawTrueNorthFrame = buildTrueNorthFrame(rotationMatrix, magneticDeclinationDeg)
        val rawTrueHeading = CelestialScreenGeometryV2.headingFromFrame(rawTrueNorthFrame).toFloat()
        deviceAzimuthDeg = stabilizeAzimuth(rawTrueHeading)
        deviceFrame = rawTrueNorthFrame.copy(stabilizedHeadingDeg = deviceAzimuthDeg.toDouble())
        emitOrientationIfNeeded()
    }

    private fun rebuildTrueNorthFromLastMatrix() {
        val matrix = lastDisplayRotationMatrix ?: return
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        devicePitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat().coerceIn(-90f, 90f)
        deviceRollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat().coerceIn(-90f, 90f)

        val rawTrueNorthFrame = buildTrueNorthFrame(matrix, magneticDeclinationDeg)
        val rawTrueHeading = CelestialScreenGeometryV2.headingFromFrame(rawTrueNorthFrame).toFloat()
        deviceAzimuthDeg = stabilizeAzimuth(rawTrueHeading)
        deviceFrame = rawTrueNorthFrame.copy(stabilizedHeadingDeg = deviceAzimuthDeg.toDouble())
    }

    private fun buildTrueNorthFrame(
        matrix: FloatArray,
        declinationDeg: Float
    ): CelestialDeviceFrameV2 {
        fun trueAxis(eastMag: Float, northMag: Float, up: Float): Triple<Double, Double, Double> {
            val angle = Math.toRadians(declinationDeg.toDouble())
            val c = cos(angle)
            val s = sin(angle)
            val eastTrue = eastMag * c + northMag * s
            val northTrue = -eastMag * s + northMag * c
            return Triple(eastTrue, northTrue, up.toDouble())
        }

        // Les colonnes de la matrice device->monde sont les axes de l'écran
        // exprimés dans le repère terrestre Android Est / Nord magnétique / Haut.
        val right = trueAxis(matrix[0], matrix[3], matrix[6])
        val top = trueAxis(matrix[1], matrix[4], matrix[7])
        val normal = trueAxis(matrix[2], matrix[5], matrix[8])

        return CelestialDeviceFrameV2(
            rightEast = right.first,
            rightNorth = right.second,
            rightUp = right.third,
            topEast = top.first,
            topNorth = top.second,
            topUp = top.third,
            normalEast = normal.first,
            normalNorth = normal.second,
            normalUp = normal.third
        )
    }

    private fun stabilizeAzimuth(raw: Float): Float {
        val normalizedRaw = normalize(raw)
        if (filteredAzimuthDeg.isNaN()) {
            filteredAzimuthDeg = normalizedRaw
            return filteredAzimuthDeg
        }

        val delta = shortestDelta(filteredAzimuthDeg, normalizedRaw)
        val magnitude = abs(delta)
        if (magnitude < AZIMUTH_DEAD_ZONE_DEG) return filteredAzimuthDeg

        // Petit mouvement : filtre le bruit magnétique. Grande rotation volontaire :
        // rattrapage plus rapide pour que le ciel ne reste pas visiblement en retard.
        val smoothing = when {
            magnitude >= 35f -> AZIMUTH_SMOOTH_LARGE
            magnitude >= 8f -> AZIMUTH_SMOOTH_MEDIUM
            else -> AZIMUTH_SMOOTH_SMALL
        }
        filteredAzimuthDeg = normalize(filteredAzimuthDeg + delta * smoothing)
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
        if (!hasPermission) {
            applyLocation(null, now, notify)
            return
        }

        val lastKnown = bestLastKnownLocation(context)
        val candidate = sequenceOf(latestLiveLocation, lastKnown)
            .filterNotNull()
            .maxByOrNull { it.time }
        applyLocation(candidate, now, notify)
    }

    private fun applyLocation(location: Location?, now: Long, notify: Boolean) {
        val context = appContext ?: return
        val hasPermission = hasLocationPermission(context)
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

        if (locationQuality == CelestialLocationQualityV2.VALID && location != null) {
            magneticDeclinationDeg = runCatching {
                GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    (if (location.hasAltitude()) location.altitude else 0.0).toFloat(),
                    now
                ).declination
            }.getOrDefault(0f)
            rebuildTrueNorthFromLastMatrix()
        }

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
        deviceRollDeg = deviceRollDeg,
        magneticDeclinationDeg = magneticDeclinationDeg,
        headingAccuracyDeg = headingAccuracyDeg,
        deviceFrame = deviceFrame
    )

    private fun notifyObservers() {
        val state = currentStateInternal()
        observers.values.toList().forEach { observer -> observer(state) }
    }

    private fun stopAcquisitionAndTicker() {
        sensorListener?.let { listener -> sensorManager?.unregisterListener(listener) }
        sensorListener = null
        sensorManager = null
        accelValues = null
        magneticValues = null
        lastDisplayRotationMatrix = null
        deviceFrame = null
        headingAccuracyDeg = null
        filteredAzimuthDeg = Float.NaN
        deviceAzimuthDeg = 0f
        lastEmitUptimeMs = 0L
        lastEmittedAzimuth = Float.NaN
        lastEmittedPitch = Float.NaN
        lastEmittedRoll = Float.NaN

        val manager = locationManager
        val listener = locationListener
        if (manager != null && listener != null) {
            runCatching { manager.removeUpdates(listener) }
        }
        locationListener = null
        locationManager = null
        mainHandler.removeCallbacks(refreshTask)
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f

    private fun shortestDelta(from: Float, to: Float): Float =
        ((to - from + 540f) % 360f) - 180f
}
