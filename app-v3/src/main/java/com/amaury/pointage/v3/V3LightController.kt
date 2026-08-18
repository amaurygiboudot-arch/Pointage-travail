package com.amaury.pointage.v3

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

object V3LightController {
    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null

    fun attach(activity: Activity, onAngle: (Float) -> Unit) {
        detach()
        val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        var smooth = -55f

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val target = ((-Math.toDegrees(orientation[0].toDouble()).toFloat() - 55f) % 360f + 360f) % 360f
                val delta = ((target - smooth + 540f) % 360f) - 180f
                smooth = ((smooth + delta * .12f) % 360f + 360f) % 360f
                activity.runOnUiThread { onAngle(smooth) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        manager = sm; listener = l
    }

    fun detach() {
        val m = manager; val l = listener
        if (m != null && l != null) m.unregisterListener(l)
        manager = null; listener = null
    }
}
