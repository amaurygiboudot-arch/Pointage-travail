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
import com.amaury.pointage.v2.engine.CelestialHeadingPolicyV2
import com.amaury.pointage.v2.engine.CelestialHeadingQualityV2
import com.amaury.pointage.v2.engine.CelestialLocationQualityV2
import com.amaury.pointage.v2.engine.CelestialScreenGeometryV2
import com.amaury.pointage.v2.engine.CelestialSensorFallbackPolicyV2
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
 * - arbitrage entre les providers sans laisser une mesure non qualifiée écraser
 *   une position encore valide ;
 * - orientation du téléphone ;
 * - rotation réelle de l'écran ;
 * - correction Nord magnétique -> Nord vrai ;
 * - stabilisation du cap utilisé par tous les rendus célestes ;
 * - qualification explicite de la fiabilité et de la fraîcheur du cap ;
 * - snapshot astronomique V2 rafraîchi indépendamment du GPS.
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
        /** Age monotone du dernier repère d'orientation effectivement produit. */
        val headingAgeMs: Long?,
        val headingQuality: CelestialHeadingQualityV2,
        val deviceFrame: CelestialDeviceFrameV2?
    ) {
        val hasRealSky: Boolean
            get() = snapshot != null &&
                locationQuality == CelestialLocationQualityV2.VALID &&
                deviceFrame != null &&
                CelestialHeadingPolicyV2.isUsable(headingQuality)
    }

    private val observers = LinkedHashMap<Any, (State) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null
    private var fallbackAccelerometer: Sensor? = null
    private var fallbackMagnetometer: Sensor? = null
    private var rotationVectorRegistered = false
    private var fallbackSensorsRegistered = false
    private var lastRotationVectorElapsedMs = Long.MIN_VALUE
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    private var snapshot: CelestialSnapshotV2? = null
    private var locationQuality = CelestialLocationQualityV2.UNAVAILABLE
    private var locationAgeMs: Long? = null
    private var locationAccuracyMeters: Float? = null
    private var locationProvider: String? = null
    private var latestLiveLocation: Location? = null
    private var resolvedLocation: Location? = null
    private var lastLocationRecheckElapsedMs = Long.MIN_VALUE
    private var lastLocationRegistrationAttemptElapsedMs = Long.MIN_VALUE

    private var deviceAzimuthDeg = 0f
    private var filteredAzimuthDeg = Float.NaN
    private var devicePitchDeg = 0f
    private var deviceRollDeg = 0f
    private var magneticDeclinationDeg = 0f
    private var headingAccuracyDeg: Float? = null
    private var headingSensorReportedUnreliable = false
    private var lastOrientationElapsedMs = Long.MIN_VALUE
    private var deviceFrame: CelestialDeviceFrameV2? = null
    private var lastDisplayRotationMatrix: FloatArray? = null

    private var accelValues: FloatArray? = null
    private var magneticValues: FloatArray? = null

    private var lastEmitUptimeMs = 0L
    private var lastEmittedAzimuth = Float.NaN
    private var lastEmittedPitch = Float.NaN
    private var lastEmittedRoll = Float.NaN

    /**
     * Le ciel évolue continuellement avec le temps : on recalcule l'éphéméride
     * chaque seconde, sans pour autant relire tous les providers GPS chaque seconde.
     */
    private const val ASTRONOMY_REFRESH_MS = 1_000L
    private const val LOCATION_RECHECK_MS = 30_000L
    private const val LOCATION_MIN_TIME_MS = 30_000L
    private const val LOCATION_MIN_DISTANCE_M = 25f
    private const val LOCATION_REGISTRATION_RETRY_MS = 5_000L
    private const val MIN_RENDER_INTERVAL_MS = 90L
    private const val MIN_ORIENTATION_DELTA_DEG = 0.8f
    private const val AZIMUTH_DEAD_ZONE_DEG = 0.40f
    private const val AZIMUTH_SMOOTH_SMALL = 0.34f
    private const val AZIMUTH_SMOOTH_MEDIUM = 0.55f
    private const val AZIMUTH_SMOOTH_LARGE = 0.78f

    private val refreshTask = object : Runnable {
        override fun run() {
            if (observers.isEmpty()) return

            val elapsedNow = SystemClock.elapsedRealtime()
            val permissionGranted = appContext?.let(::hasLocationPermission) == true
            val acquisitionStateChanged =
                (permissionGranted && locationListener == null &&
                    shouldRetryLocationRegistration(elapsedNow)) ||
                    (!permissionGranted && locationListener != null)
            if (acquisitionStateChanged || lastLocationRecheckElapsedMs == Long.MIN_VALUE ||
                elapsedNow - lastLocationRecheckElapsedMs >= LOCATION_RECHECK_MS
            ) {
                refreshLocationAndAstronomy(notify = true)
                lastLocationRecheckElapsedMs = elapsedNow
            } else {
                refreshAstronomyOnly(notify = true)
            }
            mainHandler.postDelayed(this, ASTRONOMY_REFRESH_MS)
        }
    }

    /**
     * Active le secours accelerometre + magnetometre si un constructeur accepte
     * le capteur fusionne mais ne livre plus d'evenements. Le couple de secours
     * est retire des que le rotation vector redevient fiable, pour la batterie.
     */
    private val sensorFallbackWatchdog = object : Runnable {
        override fun run() {
            if (observers.isEmpty()) return
            val ageMs = lastRotationVectorElapsedMs
                .takeIf { it != Long.MIN_VALUE }
                ?.let { SystemClock.elapsedRealtime() - it }
            if (CelestialSensorFallbackPolicyV2.shouldUseFallback(
                    rotationVectorRegistered = rotationVectorRegistered,
                    rotationVectorReportedUnreliable = headingSensorReportedUnreliable,
                    lastRotationVectorAgeMs = ageMs
                )
            ) {
                ensureFallbackSensorsRegistered()
            }
            mainHandler.postDelayed(
                this,
                CelestialSensorFallbackPolicyV2.ROTATION_VECTOR_TIMEOUT_MS
            )
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
            lastLocationRecheckElapsedMs = SystemClock.elapsedRealtime()
            mainHandler.removeCallbacks(refreshTask)
            mainHandler.postDelayed(refreshTask, ASTRONOMY_REFRESH_MS)
        }
        observer(currentStateInternal())
    }

    fun unsubscribe(key: Any) {
        observers.remove(key)
        if (observers.isEmpty()) stopAcquisitionAndTicker()
    }

    /**
     * Lecture strictement non bloquante du dernier etat publie. Elle ne consulte
     * jamais LocationManager : les vues visibles doivent s'abonner, et aucun draw
     * ne doit provoquer une lecture last-known synchrone sur le thread principal.
     */
    fun currentState(context: Context): State {
        ensureContext(context)
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
                        val unreliable =
                            event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                        headingSensorReportedUnreliable = unreliable
                        if (unreliable) {
                            headingAccuracyDeg = null
                            ensureFallbackSensorsRegistered()
                            return
                        }
                        lastRotationVectorElapsedMs = SystemClock.elapsedRealtime()
                        stopFallbackSensors()
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
                        headingSensorReportedUnreliable =
                            event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                        headingAccuracyDeg = null
                        magneticValues = event.values.copyOf()
                        updateFallbackOrientation(rawRotationMatrix, displayRotationMatrix, orientation)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR ||
                    sensor?.type == Sensor.TYPE_MAGNETIC_FIELD
                ) {
                    headingSensorReportedUnreliable =
                        accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                    if (headingSensorReportedUnreliable) {
                        // On conserve séparément le diagnostic d'Android : null peut
                        // aussi vouloir dire « précision numérique non fournie ».
                        headingAccuracyDeg = null
                        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                            ensureFallbackSensorsRegistered()
                        }
                    }
                    notifyObservers()
                }
            }
        }
        sensorListener = listener

        fallbackAccelerometer = accelSensor
        fallbackMagnetometer = magneticSensor
        rotationVectorRegistered = rotationSensor != null &&
            manager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        if (!rotationVectorRegistered) ensureFallbackSensorsRegistered()
        mainHandler.removeCallbacks(sensorFallbackWatchdog)
        mainHandler.postDelayed(
            sensorFallbackWatchdog,
            CelestialSensorFallbackPolicyV2.ROTATION_VECTOR_TIMEOUT_MS
        )
    }

    private fun ensureFallbackSensorsRegistered() {
        if (fallbackSensorsRegistered) return
        val manager = sensorManager ?: return
        val listener = sensorListener ?: return
        val accelerometer = fallbackAccelerometer ?: return
        val magnetometer = fallbackMagnetometer ?: return
        headingAccuracyDeg = null
        val accelRegistered = manager.registerListener(
            listener,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
        val magneticRegistered = manager.registerListener(
            listener,
            magnetometer,
            SensorManager.SENSOR_DELAY_UI
        )
        fallbackSensorsRegistered = accelRegistered && magneticRegistered
        if (!fallbackSensorsRegistered) {
            if (accelRegistered) manager.unregisterListener(listener, accelerometer)
            if (magneticRegistered) manager.unregisterListener(listener, magnetometer)
        }
    }

    private fun stopFallbackSensors() {
        if (!fallbackSensorsRegistered) return
        val manager = sensorManager ?: return
        val listener = sensorListener ?: return
        fallbackAccelerometer?.let { manager.unregisterListener(listener, it) }
        fallbackMagnetometer?.let { manager.unregisterListener(listener, it) }
        fallbackSensorsRegistered = false
        accelValues = null
        magneticValues = null
    }

    private fun startLocationUpdates() {
        val context = appContext ?: return
        if (!hasLocationPermission(context)) return
        lastLocationRegistrationAttemptElapsedMs = SystemClock.elapsedRealtime()

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val now = System.currentTimeMillis()
                latestLiveLocation = selectPreferredLocation(
                    sequenceOf(latestLiveLocation, location).filterNotNull(),
                    now
                )
                lastLocationRecheckElapsedMs = SystemClock.elapsedRealtime()
                applyLocation(latestLiveLocation, now, notify = true)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                stopLocationUpdatesOnly()
                lastLocationRegistrationAttemptElapsedMs = Long.MIN_VALUE
            }

            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        // Un provider défaillant ne doit pas empêcher les autres de s'enregistrer.
        var atLeastOneProviderRegistered = false
        runCatching { manager.getProviders(true) }
            .getOrDefault(emptyList())
            .filter { it != LocationManager.PASSIVE_PROVIDER }
            .distinct()
            .forEach { provider ->
                val registered = runCatching {
                    manager.requestLocationUpdates(
                        provider,
                        LOCATION_MIN_TIME_MS,
                        LOCATION_MIN_DISTANCE_M,
                        listener,
                        Looper.getMainLooper()
                    )
                }.isSuccess
                atLeastOneProviderRegistered = atLeastOneProviderRegistered || registered
            }
        if (atLeastOneProviderRegistered) {
            locationManager = manager
            locationListener = listener
        }
    }

    private fun shouldRetryLocationRegistration(nowElapsedMs: Long): Boolean =
        lastLocationRegistrationAttemptElapsedMs == Long.MIN_VALUE ||
            nowElapsedMs - lastLocationRegistrationAttemptElapsedMs >=
            LOCATION_REGISTRATION_RETRY_MS

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
        lastOrientationElapsedMs = SystemClock.elapsedRealtime()
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

    /**
     * Relit périodiquement la meilleure position disponible puis recalcule le ciel.
     * Le temps astronomique reste un instant Unix réel : il est indépendant du fuseau.
     */
    private fun refreshLocationAndAstronomy(notify: Boolean) {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val hasPermission = hasLocationPermission(context)
        if (!hasPermission) {
            stopLocationUpdatesOnly()
            applyLocation(null, now, notify)
            return
        }

        // L'abonnement peut avoir commence avant l'octroi de permission. Le
        // ticker detecte alors l'autorisation sans exiger de changer d'onglet.
        if (locationListener == null && observers.isNotEmpty() &&
            shouldRetryLocationRegistration(SystemClock.elapsedRealtime())
        ) {
            startLocationUpdates()
        }

        val lastKnown = bestLastKnownLocation(context, now)
        val candidate = selectPreferredLocation(
            sequenceOf(latestLiveLocation, lastKnown).filterNotNull(),
            now
        )
        applyLocation(candidate, now, notify)
    }

    /** Recalcule seulement l'éphéméride avec la position déjà qualifiée. */
    private fun refreshAstronomyOnly(notify: Boolean) {
        val now = System.currentTimeMillis()
        updateLocationQuality(resolvedLocation, now)
        snapshot = buildSnapshot(resolvedLocation, now)
        if (notify) notifyObservers()
    }

    private fun applyLocation(location: Location?, now: Long, notify: Boolean) {
        resolvedLocation = location
        updateLocationQuality(location, now)

        if (locationQuality == CelestialLocationQualityV2.VALID && location != null) {
            val declination = runCatching {
                GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    (if (location.hasAltitude()) location.altitude else 0.0).toFloat(),
                    now
                ).declination
            }.getOrNull()?.takeIf { it.isFinite() }
            if (declination != null) {
                magneticDeclinationDeg = declination
                rebuildTrueNorthFromLastMatrix()
            } else {
                // Ne jamais présenter le Nord magnétique comme Nord vrai avec
                // une déclinaison de secours inventée à zéro.
                deviceFrame = null
                filteredAzimuthDeg = Float.NaN
            }
        } else {
            deviceFrame = null
            filteredAzimuthDeg = Float.NaN
        }

        snapshot = buildSnapshot(location, now)
        if (notify) notifyObservers()
    }

    private fun updateLocationQuality(location: Location?, nowWallMs: Long) {
        val context = appContext ?: return
        val hasPermission = hasLocationPermission(context)
        val accuracy = location?.takeIf { it.hasAccuracy() }?.accuracy
        val ageMs = locationAgeMs(location, nowWallMs)

        locationQuality = CelestialTrackingPolicyV2.classifyAge(
            hasPermission = hasPermission,
            hasLocation = location?.let(::hasValidCoordinates) == true,
            locationAgeMs = ageMs,
            accuracyMeters = accuracy
        )
        locationAgeMs = ageMs
        locationAccuracyMeters = accuracy
        locationProvider = location?.provider
    }

    /**
     * Utilise l'horloge monotone Android pour mesurer l'âge GPS quand le provider
     * l'a fourni. Le fallback wall-clock ne sert qu'aux très anciennes locations
     * artificielles/appareils où elapsedRealtimeNanos n'est pas exploitable.
     */
    private fun locationAgeMs(location: Location?, nowWallMs: Long): Long? {
        location ?: return null
        val sampleElapsedNanos = location.elapsedRealtimeNanos
        if (sampleElapsedNanos > 0L) {
            return (SystemClock.elapsedRealtimeNanos() - sampleElapsedNanos) / 1_000_000L
        }
        return location.time
            .takeIf { it > 0L }
            ?.let { nowWallMs - it }
    }

    /**
     * Sélectionne la meilleure mesure sans confondre « plus récente » et
     * « utilisable ». Une mesure hors tolérance ne peut donc plus faire disparaître
     * le ciel si une autre position qualifiée est encore disponible.
     */
    private fun selectPreferredLocation(
        locations: Sequence<Location>,
        nowWallMs: Long
    ): Location? {
        var selected: Location? = null
        locations.filter(::hasValidCoordinates).forEach { candidate ->
            val current = selected
            if (current == null) {
                selected = candidate
            } else {
                val replace = CelestialTrackingPolicyV2.shouldReplaceLocation(
                    currentAgeMs = locationAgeMs(current, nowWallMs),
                    currentAccuracyMeters = current.takeIf { it.hasAccuracy() }?.accuracy,
                    candidateAgeMs = locationAgeMs(candidate, nowWallMs),
                    candidateAccuracyMeters = candidate.takeIf { it.hasAccuracy() }?.accuracy
                )
                if (replace) selected = candidate
            }
        }
        return selected
    }

    private fun hasValidCoordinates(location: Location): Boolean =
        CelestialTrackingPolicyV2.hasValidCoordinates(
            latitudeDeg = location.latitude,
            longitudeDeg = location.longitude,
            altitudeMeters = location.takeIf { it.hasAltitude() }?.altitude
        )

    private fun buildSnapshot(location: Location?, now: Long): CelestialSnapshotV2? {
        if (locationQuality != CelestialLocationQualityV2.VALID ||
            location == null ||
            !HoraTrackV2.ENABLED
        ) return null

        return runCatching {
            HoraTrackV2.celestial.snapshot(
                latitudeDeg = location.latitude,
                longitudeDeg = location.longitude,
                timeMs = now,
                observerAltitudeMeters = if (location.hasAltitude()) location.altitude else 0.0
            )
        }.getOrNull()
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun bestLastKnownLocation(context: Context, nowWallMs: Long): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locations = runCatching { manager.getProviders(true) }
            .getOrDefault(emptyList())
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
        return selectPreferredLocation(locations.asSequence(), nowWallMs)
    }

    private fun currentStateInternal(): State {
        val headingAgeMs = lastOrientationElapsedMs
            .takeIf { it != Long.MIN_VALUE }
            ?.let { SystemClock.elapsedRealtime() - it }
        val headingQuality = CelestialHeadingPolicyV2.classify(
            hasOrientation = deviceFrame != null,
            headingAgeMs = headingAgeMs,
            sensorReportedUnreliable = headingSensorReportedUnreliable,
            headingAccuracyDeg = headingAccuracyDeg
        )

        return State(
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
            headingAgeMs = headingAgeMs,
            headingQuality = headingQuality,
            deviceFrame = deviceFrame
        )
    }

    private fun notifyObservers() {
        val state = currentStateInternal()
        observers.values.toList().forEach { observer -> observer(state) }
    }

    private fun stopAcquisitionAndTicker() {
        mainHandler.removeCallbacks(sensorFallbackWatchdog)
        sensorListener?.let { listener -> sensorManager?.unregisterListener(listener) }
        sensorListener = null
        sensorManager = null
        fallbackAccelerometer = null
        fallbackMagnetometer = null
        rotationVectorRegistered = false
        fallbackSensorsRegistered = false
        lastRotationVectorElapsedMs = Long.MIN_VALUE
        accelValues = null
        magneticValues = null
        lastDisplayRotationMatrix = null
        deviceFrame = null
        headingAccuracyDeg = null
        headingSensorReportedUnreliable = false
        lastOrientationElapsedMs = Long.MIN_VALUE
        filteredAzimuthDeg = Float.NaN
        deviceAzimuthDeg = 0f
        devicePitchDeg = 0f
        deviceRollDeg = 0f
        lastEmitUptimeMs = 0L
        lastEmittedAzimuth = Float.NaN
        lastEmittedPitch = Float.NaN
        lastEmittedRoll = Float.NaN

        stopLocationUpdatesOnly()
        latestLiveLocation = null
        resolvedLocation = null
        snapshot = null
        locationQuality = CelestialLocationQualityV2.UNAVAILABLE
        locationAgeMs = null
        locationAccuracyMeters = null
        locationProvider = null
        magneticDeclinationDeg = 0f
        lastLocationRecheckElapsedMs = Long.MIN_VALUE
        mainHandler.removeCallbacks(refreshTask)
    }

    private fun stopLocationUpdatesOnly() {
        val manager = locationManager
        val listener = locationListener
        if (manager != null && listener != null) {
            runCatching { manager.removeUpdates(listener) }
        }
        locationListener = null
        locationManager = null
        latestLiveLocation = null
        lastLocationRegistrationAttemptElapsedMs = Long.MIN_VALUE
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f

    private fun shortestDelta(from: Float, to: Float): Float =
        ((to - from + 540f) % 360f) - 180f
}
