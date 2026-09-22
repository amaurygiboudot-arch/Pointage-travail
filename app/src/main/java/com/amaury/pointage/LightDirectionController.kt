package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.CelestialTrackerV2.State
import com.amaury.pointage.v2.engine.CelestialScreenGeometryV2
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Registre pur des consommateurs d'une ressource partagée.
 *
 * Une activité peut posséder plusieurs consommateurs indépendants. Les ressources
 * ne démarrent que lorsque l'activité est visible et le retrait d'un consommateur
 * n'arrête jamais ceux qui restent.
 */
internal class ConsumerSubscriptionRegistry<Owner : Any, Callback>(
    private val start: (Owner, Any, Callback) -> Unit,
    private val stop: (Any) -> Unit
) {
    private data class Entry<Callback>(
        val trackerKey: Any = Any(),
        var callback: Callback,
        var active: Boolean = false
    )

    private data class OwnerState<Callback>(
        var visible: Boolean = false,
        val entries: MutableMap<Any, Entry<Callback>> = LinkedHashMap()
    )

    private val owners = LinkedHashMap<Owner, OwnerState<Callback>>()

    fun register(owner: Owner, consumerKey: Any, callback: Callback) {
        val ownerState = owners.getOrPut(owner) { OwnerState() }
        val existing = ownerState.entries[consumerKey]
        if (existing != null) {
            if (existing.active) {
                stop(existing.trackerKey)
                existing.active = false
            }
            existing.callback = callback
            if (ownerState.visible) activate(owner, existing)
            return
        }

        val entry = Entry(callback = callback)
        ownerState.entries[consumerKey] = entry
        if (ownerState.visible) activate(owner, entry)
    }

    fun unregister(owner: Owner, consumerKey: Any) {
        val ownerState = owners[owner] ?: return
        val entry = ownerState.entries.remove(consumerKey) ?: return
        if (entry.active) stop(entry.trackerKey)
        if (ownerState.entries.isEmpty() && !ownerState.visible) owners.remove(owner)
    }

    fun setVisible(owner: Owner, visible: Boolean) {
        val ownerState = owners.getOrPut(owner) { OwnerState() }
        if (ownerState.visible == visible) return
        ownerState.visible = visible
        ownerState.entries.values.forEach { entry ->
            if (visible) activate(owner, entry) else deactivate(entry)
        }
        if (!visible && ownerState.entries.isEmpty()) owners.remove(owner)
    }

    fun clear(owner: Owner) {
        val ownerState = owners.remove(owner) ?: return
        ownerState.entries.values.forEach(::deactivate)
    }

    internal fun activeCount(owner: Owner): Int =
        owners[owner]?.entries?.values?.count { it.active } ?: 0

    private fun activate(owner: Owner, entry: Entry<Callback>) {
        if (entry.active) return
        entry.active = true
        try {
            start(owner, entry.trackerKey, entry.callback)
        } catch (failure: Throwable) {
            entry.active = false
            throw failure
        }
    }

    private fun deactivate(entry: Entry<Callback>) {
        if (!entry.active) return
        entry.active = false
        stop(entry.trackerKey)
    }
}

/**
 * Adaptateur d'éclairage de l'interface vers le suivi céleste V2.
 *
 * GPS et capteurs ne sont plus acquis ici : CelestialTrackerV2 est l'unique
 * source Android partagée avec SunIndicatorView. La direction lumineuse écran
 * utilise la même carte 360° Terre au centre que l'horloge céleste et n'est
 * appliquée que lorsque le cap est qualifié comme exploitable.
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

    private object LegacyConsumerKey

    private val mainHandler = Handler(Looper.getMainLooper())
    private val registrations = ConsumerSubscriptionRegistry<Activity, (LightingState) -> Unit>(
        start = ::subscribeConsumer,
        stop = CelestialTrackerV2::unsubscribe
    )

    @Volatile
    private var cachedNight: Boolean? = null

    private const val FIXED_FALLBACK_LIGHT_ANGLE = -55f

    /** Compatibilité du consommateur historique d'éclairage des boutons. */
    fun attach(activity: Activity, onLightingChanged: (LightingState) -> Unit) {
        attach(activity, LegacyConsumerKey, onLightingChanged)
    }

    /** Inscription indépendante : une clé ne peut détacher aucun autre consommateur. */
    fun attach(activity: Activity, consumerKey: Any, onLightingChanged: (LightingState) -> Unit) {
        onMainThread { registrations.register(activity, consumerKey, onLightingChanged) }
    }

    /** Compatibilité du consommateur historique d'éclairage des boutons. */
    fun detach(activity: Activity) {
        detach(activity, LegacyConsumerKey)
    }

    fun detach(activity: Activity, consumerKey: Any) {
        onMainThread { registrations.unregister(activity, consumerKey) }
    }

    /** Suspend l'acquisition sans oublier les consommateurs à reprendre. */
    fun setActivityVisible(activity: Activity, visible: Boolean) {
        onMainThread { registrations.setVisible(activity, visible) }
    }

    /** Libère définitivement tous les consommateurs d'une activité détruite. */
    fun detachAll(activity: Activity) {
        onMainThread { registrations.clear(activity) }
    }

    /**
     * Lecture non bloquante pour les drawables et thèmes. Elle ne consulte jamais
     * CelestialTrackerV2 et ne peut donc ni lire le GPS ni recalculer l'éphéméride.
     */
    fun isNight(context: Context): Boolean {
        cachedNight?.let { return it }
        val preferences = context.applicationContext.getSharedPreferences(
            AppThemeCatalog.PREFS,
            Context.MODE_PRIVATE
        )
        return if (preferences.contains(AppThemeCatalog.KEY_CELESTIAL_NIGHT)) {
            preferences.getBoolean(AppThemeCatalog.KEY_CELESTIAL_NIGHT, false)
        } else {
            fallbackNightByClock()
        }.also { cachedNight = it }
    }

    private fun subscribeConsumer(
        activity: Activity,
        trackerKey: Any,
        onLightingChanged: (LightingState) -> Unit
    ) {
        CelestialTrackerV2.subscribe(activity, trackerKey) { tracking ->
            if (activity.isFinishing || activity.isDestroyed) {
                detachAll(activity)
                return@subscribe
            }
            onLightingChanged(updateLighting(tracking))
        }
    }

    private fun updateLighting(tracking: State): LightingState {
        val snapshot = tracking.snapshot
        val night = snapshot?.night ?: cachedNight ?: fallbackNightByClock()
        cachedNight = night
        val active = snapshot?.let { if (it.night) it.moon else it.sun }
        val frame = tracking.deviceFrame.takeIf { tracking.hasRealSky }
        val activeProjection = if (active != null && frame != null) {
            CelestialScreenGeometryV2.projectInDeviceSky(active, frame)
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

        /*
         * L'éclairage de la Terre centrale dépend du vrai Soleil même quand
         * celui-ci est sous l'horizon local. Le cap doit toutefois rester qualifié.
         */
        if (snapshot != null && frame != null) {
            val heading = CelestialScreenGeometryV2.headingFromFrame(frame)
            val theta = Math.toRadians(shortestDelta(heading, snapshot.sun.azimuthDeg))
            CelestialLightingState.updateSunDirection(
                sin(theta).toFloat(),
                (-cos(theta)).toFloat()
            )
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

        return LightingState(
            lightAngle = lightAngle,
            celestialAngle = celestialAngle,
            celestialElevation = elevation,
            night = night,
            deviceAzimuth = tracking.deviceAzimuthDeg,
            devicePitch = tracking.devicePitchDeg
        )
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun fallbackNightByClock(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 7 || hour >= 20
    }

    private fun screenAngle(x: Double, y: Double): Float {
        if (kotlin.math.abs(x) < 1e-9 && kotlin.math.abs(y) < 1e-9) return 0f
        return normalize(Math.toDegrees(atan2(x, -y)).toFloat())
    }

    private fun shortestDelta(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
}
