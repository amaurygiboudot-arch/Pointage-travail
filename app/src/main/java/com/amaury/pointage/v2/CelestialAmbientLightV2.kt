package com.amaury.pointage.v2

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

enum class CelestialAmbientLightQualityV2 {
    VALID,
    STALE,
    UNAVAILABLE,
    INVALID
}

data class CelestialAmbientLightStateV2(
    val lux: Double?,
    val quality: CelestialAmbientLightQualityV2,
    val measuredAtElapsedMs: Long?
) {
    fun ageMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? =
        measuredAtElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
}

/**
 * Propriétaire Android de la luminosité ambiante Céleste.
 *
 * Le capteur de lumière est uniquement un signal complémentaire. Son absence ne
 * bloque jamais Céleste et une mesure périmée n'est jamais traitée comme actuelle.
 */
object CelestialAmbientLightV2 {
    private val observers = LinkedHashMap<Any, (CelestialAmbientLightStateV2) -> Unit>()
    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var listener: SensorEventListener? = null
    private var filteredLux: Double? = null
    private var lastPublishedLux: Double? = null
    private var lastPublishedElapsedMs: Long = Long.MIN_VALUE
    private var rawState = CelestialAmbientLightStateV2(
        lux = null,
        quality = CelestialAmbientLightQualityV2.UNAVAILABLE,
        measuredAtElapsedMs = null
    )

    @Synchronized
    fun subscribe(
        context: Context,
        key: Any,
        observer: (CelestialAmbientLightStateV2) -> Unit
    ) {
        observers[key] = observer
        if (observers.size == 1) start(context.applicationContext)
        observer(currentState())
    }

    @Synchronized
    fun unsubscribe(key: Any) {
        observers.remove(key)
        if (observers.isEmpty()) stop()
    }

    @Synchronized
    fun currentState(
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): CelestialAmbientLightStateV2 {
        val state = rawState
        if (state.quality == CelestialAmbientLightQualityV2.VALID) {
            val age = state.ageMs(nowElapsedMs)
            if (age != null && age > MAX_SAMPLE_AGE_MS) {
                return state.copy(quality = CelestialAmbientLightQualityV2.STALE)
            }
        }
        return state
    }

    private fun start(context: Context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lightSensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        sensorManager = manager
        sensor = lightSensor
        filteredLux = null

        if (manager == null || lightSensor == null) {
            publish(
                CelestialAmbientLightStateV2(
                    lux = null,
                    quality = CelestialAmbientLightQualityV2.UNAVAILABLE,
                    measuredAtElapsedMs = null
                )
            )
            return
        }

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val raw = event.values.firstOrNull()?.toDouble()
                if (raw == null || !raw.isFinite() || raw < 0.0) {
                    publish(
                        CelestialAmbientLightStateV2(
                            lux = null,
                            quality = CelestialAmbientLightQualityV2.INVALID,
                            measuredAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                    )
                    return
                }

                val bounded = raw.coerceAtMost(MAX_REASONABLE_LUX)
                val previous = filteredLux
                val filtered = if (previous == null) {
                    bounded
                } else {
                    previous + FILTER_ALPHA * (bounded - previous)
                }
                filteredLux = filtered
                val now = SystemClock.elapsedRealtime()
                val previousPublished = lastPublishedLux
                val absoluteDelta = previousPublished?.let { abs(filtered - it) } ?: Double.POSITIVE_INFINITY
                val relativeDelta = previousPublished?.let {
                    absoluteDelta / max(abs(it), 1.0)
                } ?: Double.POSITIVE_INFINITY
                val enoughTime = lastPublishedElapsedMs == Long.MIN_VALUE ||
                    now - lastPublishedElapsedMs >= MIN_PUBLISH_INTERVAL_MS
                val meaningfulChange = absoluteDelta >= MIN_ABSOLUTE_LUX_DELTA ||
                    relativeDelta >= MIN_RELATIVE_LUX_DELTA

                if (enoughTime || meaningfulChange) {
                    lastPublishedLux = filtered
                    lastPublishedElapsedMs = now
                    publish(
                        CelestialAmbientLightStateV2(
                            lux = filtered,
                            quality = CelestialAmbientLightQualityV2.VALID,
                            measuredAtElapsedMs = now
                        )
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    publish(
                        CelestialAmbientLightStateV2(
                            lux = filteredLux,
                            quality = CelestialAmbientLightQualityV2.INVALID,
                            measuredAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                    )
                }
            }
        }
        listener = sensorListener
        val registered = manager.registerListener(
            sensorListener,
            lightSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        if (!registered) {
            listener = null
            publish(
                CelestialAmbientLightStateV2(
                    lux = null,
                    quality = CelestialAmbientLightQualityV2.UNAVAILABLE,
                    measuredAtElapsedMs = null
                )
            )
        }
    }

    private fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        sensor = null
        sensorManager = null
        filteredLux = null
        lastPublishedLux = null
        lastPublishedElapsedMs = Long.MIN_VALUE
        rawState = CelestialAmbientLightStateV2(
            lux = null,
            quality = CelestialAmbientLightQualityV2.UNAVAILABLE,
            measuredAtElapsedMs = null
        )
    }

    @Synchronized
    private fun publish(state: CelestialAmbientLightStateV2) {
        rawState = state
        val snapshot = observers.values.toList()
        snapshot.forEach { it(currentState()) }
    }

    private const val MAX_SAMPLE_AGE_MS = 10_000L
    private const val MAX_REASONABLE_LUX = 200_000.0
    private const val FILTER_ALPHA = 0.18
    private const val MIN_PUBLISH_INTERVAL_MS = 1_000L
    private const val MIN_ABSOLUTE_LUX_DELTA = 2.0
    private const val MIN_RELATIVE_LUX_DELTA = 0.08
}
