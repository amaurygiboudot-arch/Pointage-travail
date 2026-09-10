package com.amaury.pointage

import android.app.Activity
import android.content.Context
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.engine.CelestialScreenGeometryV2
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Adaptateur d'éclairage de l'interface vers le suivi céleste V2.
 *
 * GPS et capteurs ne sont plus acquis ici : CelestialTrackerV2 est l'unique
 * source Android partagée avec SunIndicatorView. La direction lumineuse écran
 * utilise la même projection 3D que l'horloge céleste.
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

    private data class Registration(val trackerKey: Any)

    private val registrations = mutableMapOf<Int, Registration>()
    private const val FIXED_FALLBACK_LIGHT_ANGLE = -55f

    private fun detachOtherActivities(activeKey: Int) {
        val obsoleteKeys = registrations.keys.filter { it != activeKey }
        obsoleteKeys.forEach { key ->
            registrations.remove(key)?.let { CelestialTrackerV2.unsubscribe(it.trackerKey) }
        }
    }

    fun attach(activity: Activity, onLightingChanged: (LightingState) -> Unit) {
        val activityKey = System.identityHashCode(activity)
        detachOtherActivities(activityKey)
        if (registrations.containsKey(activityKey)) return

        val trackerKey = Any()
        registrations[activityKey] = Registration(trackerKey)

        CelestialTrackerV2.subscribe(activity, trackerKey) { tracking ->
            if (activity.isFinishing || activity.isDestroyed) {
                detach(activity)
                return@subscribe
            }

            val snapshot = tracking.snapshot
            val night = snapshot?.night ?: fallbackNightByClock()
            val active = snapshot?.let { if (it.night) it.moon else it.sun }
            val activeProjection = if (active != null && tracking.deviceFrame != null) {
                CelestialScreenGeometryV2.projectInDeviceSky(active, tracking.deviceFrame)
            } else {
                null
            }
            val celestialAngle = activeProjection?.let {
                screenAngle(it.xRadiusFraction, it.yRadiusFraction)
            }
            val lightAngle = celestialAngle ?: FIXED_FALLBACK_LIGHT_ANGLE
            val elevation = active?.altitudeDeg?.toFloat()?.coerceIn(-10f, 90f)
                ?: if (night) 25f else 45f
            val intensity = if (active == null) {
                if (night) .24f else .72f
            } else if (night) {
                ((active.altitudeDeg + 10.0) / 45.0).toFloat().coerceIn(.18f, .42f)
            } else {
                ((active.altitudeDeg + 6.0) / 58.0).toFloat().coerceIn(.38f, 1f)
            }

            val sunProjection = if (snapshot != null && tracking.deviceFrame != null) {
                CelestialScreenGeometryV2.projectInDeviceSky(snapshot.sun, tracking.deviceFrame)
            } else {
                null
            }
            if (sunProjection != null) {
                val x = sunProjection.xRadiusFraction.toFloat()
                val y = sunProjection.yRadiusFraction.toFloat()
                val length = sqrt(x * x + y * y)
                if (length > 0.0001f) {
                    CelestialLightingState.updateSunDirection(x, y)
                } else {
                    CelestialLightingState.clearSunDirection()
                }
            } else {
                CelestialLightingState.clearSunDirection()
            }

            CelestialLightingState.updateOpticalLight(
                intensity = intensity,
                elevationDegrees = elevation,
                night = night
            )

            val diamondPitch = tracking.devicePitchDeg.coerceIn(-55f, 55f)
            val diamondRoll = tracking.deviceRollDeg.coerceIn(-55f, 55f)
            val diamondElevation = elevation.coerceIn(if (night) 12f else 20f, 90f)
            RedDiamondFinalButton.updateGlobalNaturalLight(
                lightAngle,
                diamondPitch,
                diamondRoll,
                intensity.coerceIn(.12f, 1f),
                false,
                diamondElevation
            )

            onLightingChanged(
                LightingState(
                    lightAngle = lightAngle,
                    celestialAngle = celestialAngle,
                    celestialElevation = elevation,
                    night = night,
                    deviceAzimuth = tracking.deviceAzimuthDeg,
                    devicePitch = tracking.devicePitchDeg
                )
            )
        }
    }

    fun detach(activity: Activity) {
        val registration = registrations.remove(System.identityHashCode(activity)) ?: return
        CelestialTrackerV2.unsubscribe(registration.trackerKey)
    }

    fun isNight(context: Context): Boolean =
        CelestialTrackerV2.currentState(context).snapshot?.night ?: fallbackNightByClock()

    private fun fallbackNightByClock(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 7 || hour >= 20
    }

    private fun screenAngle(x: Double, y: Double): Float {
        if (kotlin.math.abs(x) < 1e-9 && kotlin.math.abs(y) < 1e-9) return 0f
        return normalize(Math.toDegrees(atan2(x, -y)).toFloat())
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
}
