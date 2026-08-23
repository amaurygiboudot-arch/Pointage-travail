package com.amaury.pointage

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Capteurs dédiés aux trois boutons principaux.
 * Aucun timer, aucune dead zone et aucun lissage : chaque événement est transmis immédiatement.
 */
internal object DiamondMotionController : SensorEventListener {
    private const val PREFS = "appearance_settings"
    private const val PREF_SOLAR = "solar_lighting_enabled"
    private const val FIXED_LIGHT_ANGLE = -55f

    private val clients = AtomicInteger(0)
    private var manager: SensorManager? = null
    private var sensor: Sensor? = null
    private var appContext: Context? = null
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    fun attach(context: Context) {
        if (clients.incrementAndGet() != 1) return
        val app = context.applicationContext
        appContext = app
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        manager = sm
        sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun detach() {
        val remaining = clients.decrementAndGet().coerceAtLeast(0)
        if (remaining > 0) return
        clients.set(0)
        manager?.unregisterListener(this)
        manager = null
        sensor = null
        appContext = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        var pitch: Float
        var roll: Float

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        } else {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            pitch = Math.toDegrees(
                atan2((-ay).toDouble(), sqrt((ax * ax + az * az).toDouble()))
            ).toFloat()
            roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
        }

        pitch = pitch.coerceIn(-90f, 90f)
        roll = roll.coerceIn(-90f, 90f)

        val solarEnabled = appContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(PREF_SOLAR, false)
            ?: false

        if (solarEnabled) {
            // Soleil/lune décide de la direction de lumière ; ce hub fournit uniquement le mouvement.
            RedDiamondFinalButton.updateGlobalDevicePose(pitch, roll)
        } else {
            // Sans soleil/lune, le mouvement reste actif autour d'une lumière fixe et neutre.
            RedDiamondFinalButton.updateGlobalNaturalLight(
                FIXED_LIGHT_ANGLE,
                pitch,
                roll,
                .78f,
                false,
                45f
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
