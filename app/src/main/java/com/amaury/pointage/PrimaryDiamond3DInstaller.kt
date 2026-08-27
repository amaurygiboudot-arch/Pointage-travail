package com.amaury.pointage

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.util.WeakHashMap
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Branche les trois boutons de pointage permanents sur le moteur OpenGL.
 * Les couleurs sont envoyées directement au shader : aucun filtre n'est posé
 * sur la TextureView, donc le fond reste transparent.
 */
object PrimaryDiamond3DInstaller {
    private const val TAG = "hp_primary_diamond_3d_v3_tilt"
    private val hosts = WeakHashMap<Button, True3DButtonHost>()
    private var currentLightAngle = -55f

    fun install(root: View, lightAngle: Float = currentLightAngle) {
        currentLightAngle = lightAngle
        val buttons = ArrayList<Button>(3)
        collectPrimary(root, buttons)
        buttons.forEach { button ->
            val existing = hosts[button]
            if (existing != null) {
                button.alpha = 0f
                existing.setLightAngle(currentLightAngle)
                existing.setBaseColor(colorFor(button))
                PrimaryDiamondTiltHub.attach(existing)
            } else {
                wrap(button, currentLightAngle)
            }
        }
    }

    fun updateLight(root: View, lightAngle: Float) {
        currentLightAngle = lightAngle
        hosts.entries.toList().forEach { (button, host) ->
            if (button.rootView === root.rootView) {
                host.setBaseColor(colorFor(button))
                host.setLightAngle(lightAngle)
            }
        }
    }

    fun updateAllLight(lightAngle: Float) {
        currentLightAngle = lightAngle
        hosts.entries.toList().forEach { (button, host) ->
            host.setBaseColor(colorFor(button))
            host.setLightAngle(lightAngle)
        }
    }

    private fun collectPrimary(view: View, out: MutableList<Button>) {
        if (view is Button && isPrimary(view) && view.getTag(R.id.true3d_internal_tag) != TAG) out.add(view)
        if (view is ViewGroup && view !is True3DButtonHost) {
            for (i in 0 until view.childCount) collectPrimary(view.getChildAt(i), out)
        }
    }

    private fun isPrimary(button: Button): Boolean {
        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return name == "entryButton" || name == "pauseButton" || name == "exitButton"
    }

    private fun colorFor(button: Button): Int {
        val name = runCatching { button.resources.getResourceEntryName(button.id) }.getOrNull().orEmpty()
        return when (name) {
            "entryButton" -> Color.rgb(9, 223, 62)
            "pauseButton" -> Color.rgb(246, 108, 6)
            "exitButton" -> Color.rgb(244, 9, 11)
            else -> Color.rgb(45, 135, 235)
        }
    }

    private fun wrap(button: Button, lightAngle: Float) {
        val parent = button.parent as? ViewGroup ?: return
        if (parent is True3DButtonHost) return

        val index = parent.indexOfChild(button)
        val originalLayoutParams = button.layoutParams
        parent.removeViewAt(index)

        val host = True3DButtonHost(button.context)
        host.layoutParams = originalLayoutParams
        host.setTag(R.id.true3d_internal_tag, TAG)
        host.clipChildren = false
        host.clipToPadding = false
        host.cameraDistance = 18_000f * button.resources.displayMetrics.density
        host.scaleX = .97f
        host.scaleY = .97f
        parent.addView(host, index)

        host.attachButton(button, DiamondTuningStore.load(button.context), lightAngle)
        host.setBaseColor(colorFor(button))
        button.setTag(R.id.true3d_internal_tag, TAG)
        button.alpha = 0f
        hosts[button] = host

        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = PrimaryDiamondTiltHub.attach(host)
            override fun onViewDetachedFromWindow(v: View) = PrimaryDiamondTiltHub.detach(host)
        })
        PrimaryDiamondTiltHub.attach(host)
    }
}

/**
 * Donne aux trois diamants principaux une inclinaison nettement visible selon
 * l'orientation réelle du téléphone. Le moteur OpenGL continue de recevoir sa
 * propre pose, et ce second niveau incline le host avec une perspective Android.
 * La première pose reçue sert de position neutre pour éviter un bouton déjà
 * penché quand le téléphone est tenu naturellement.
 */
private object PrimaryDiamondTiltHub : SensorEventListener {
    private data class PoseState(
        var refPitch: Float? = null,
        var refRoll: Float? = null,
        var smoothPitch: Float = 0f,
        var smoothRoll: Float = 0f
    )

    private val hosts = WeakHashMap<True3DButtonHost, PoseState>()
    private var manager: SensorManager? = null
    private var sensor: Sensor? = null
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var ax = 0f
    private var ay = 0f
    private var az = 0f

    fun attach(host: True3DButtonHost) {
        if (!hosts.containsKey(host)) hosts[host] = PoseState()
        if (manager != null) return

        val sm = host.context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        manager = sm
        sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun detach(host: True3DButtonHost) {
        hosts.remove(host)
        if (hosts.isEmpty()) {
            manager?.unregisterListener(this)
            manager = null
            sensor = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val pitch: Float
        val roll: Float

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        } else {
            val k = .18f
            ax += (event.values[0] - ax) * k
            ay += (event.values[1] - ay) * k
            az += (event.values[2] - az) * k
            pitch = Math.toDegrees(atan2(-ay, sqrt(ax * ax + az * az).toDouble())).toFloat()
            roll = Math.toDegrees(atan2(ax, az.toDouble())).toFloat()
        }

        hosts.entries.toList().forEach { (host, state) ->
            if (state.refPitch == null || state.refRoll == null) {
                state.refPitch = pitch
                state.refRoll = roll
            }

            val pitchDelta = angleDelta(state.refPitch ?: pitch, pitch).coerceIn(-32f, 32f)
            val rollDelta = angleDelta(state.refRoll ?: roll, roll).coerceIn(-32f, 32f)

            // Réponse volontairement visible : ~22° maximum sur chaque axe.
            val targetX = (-pitchDelta * .70f).coerceIn(-22f, 22f)
            val targetY = (rollDelta * .70f).coerceIn(-22f, 22f)
            state.smoothPitch += (targetX - state.smoothPitch) * .28f
            state.smoothRoll += (targetY - state.smoothRoll) * .28f

            host.rotationX = state.smoothPitch
            host.rotationY = state.smoothRoll
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun angleDelta(reference: Float, value: Float): Float =
        ((value - reference + 540f) % 360f) - 180f
}
