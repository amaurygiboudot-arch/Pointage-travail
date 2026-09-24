package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.engine.CelestialLocationQualityV2
import com.amaury.pointage.v2.engine.LocalStarPositionV2
import com.amaury.pointage.v2.engine.StarSkyProjectionV2
import com.amaury.pointage.v2.ui.ConstellationPathV2
import com.amaury.pointage.v2.ui.StarSkyCatalogLoaderV2
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Rendu du ciel réel injecté à l'intérieur du cadran Android.
 *
 * Le propriétaire de l'abonnement GPS/capteurs reste HpAnalogClockView :
 * ce renderer reçoit l'état déjà publié et n'ouvre aucun second abonnement.
 */
class CelestialStarLayerRendererV2(
    context: Context,
    private val onInvalidated: () -> Unit
) {
    private data class LocalStar(
        val hr: Int,
        val magnitude: Double,
        val position: LocalStarPositionV2
    )

    private data class LocalSky(
        val key: String,
        val stars: List<LocalStar>,
        val paths: List<ConstellationPathV2>
    )

    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val generation = AtomicLong(0)
    @Volatile private var localSky: LocalSky? = null
    private var requestedKey: String? = null

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(0.6f * density, 1f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 9f * context.resources.displayMetrics.scaledDensity
        color = Color.WHITE
    }

    fun update(state: CelestialTrackerV2.State) {
        val snapshot = state.snapshot ?: return
        if (state.locationQuality != CelestialLocationQualityV2.VALID) return
        val bucket = snapshot.atMs / LOCAL_SKY_REFRESH_MS
        val key = "%.4f:%.4f:%d".format(snapshot.latitudeDeg, snapshot.longitudeDeg, bucket)
        if (key == requestedKey) return
        requestedKey = key
        val requestGeneration = generation.incrementAndGet()

        executor.execute {
            val catalog = runCatching { StarSkyCatalogLoaderV2.load(appContext) }.getOrNull()
                ?: return@execute
            val prepared = catalog.stars.mapNotNull { (hr, star) ->
                runCatching {
                    val position = StarSkyProjectionV2.horizontal(
                        star = star,
                        latitudeDeg = snapshot.latitudeDeg,
                        longitudeDeg = snapshot.longitudeDeg,
                        timeMs = snapshot.atMs
                    )
                    if (!position.aboveApparentHorizon) return@runCatching null
                    LocalStar(
                        hr = hr,
                        magnitude = star.visualMagnitude,
                        position = position
                    )
                }.getOrNull()
            }
            val result = LocalSky(
                key = key,
                stars = prepared,
                paths = catalog.constellationPaths
            )
            if (requestGeneration == generation.get()) {
                localSky = result
                onInvalidated()
            }
        }
    }

    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        state: CelestialTrackerV2.State?
    ) {
        val current = state ?: return
        val snapshot = current.snapshot ?: return
        if (current.locationQuality != CelestialLocationQualityV2.VALID || radius <= 1f) return

        val starOpacity = StarSkyProjectionV2.nightSkyOpacity(snapshot.sun.altitudeDeg)
        // Constellation lines are a positional overlay, not a claim that the
        // connecting lines exist physically in the sky. Keep them readable in
        // daylight while stars themselves still follow real solar visibility.
        val constellationOpacity = 0.42 + 0.58 * starOpacity
        val sky = localSky ?: return

        val projected = HashMap<Int, PointF>(sky.stars.size)
        val visibleStars = ArrayList<Pair<LocalStar, PointF>>(sky.stars.size)
        for (star in sky.stars) {
            val point = if (current.hasRealSky && current.deviceFrame != null) {
                StarSkyProjectionV2.projectToDevice(star.position, current.deviceFrame)
            } else {
                StarSkyProjectionV2.projectToZenithMap(star.position)
            } ?: continue
            val screen = PointF(
                cx + (point.x * radius).toFloat(),
                cy + (point.y * radius).toFloat()
            )
            projected[star.hr] = screen
            visibleStars += star to screen
        }

        linePaint.alpha = (22.0 + 34.0 * constellationOpacity).toInt().coerceIn(0, 255)
        labelPaint.alpha = 0
        for (path in sky.paths) {
            var visibleCount = 0
            var sumX = 0f
            var sumY = 0f
            var previous: PointF? = null
            for (hr in path.hrNumbers) {
                val point = projected[hr]
                if (point == null) {
                    previous = null
                    continue
                }
                previous?.let { canvas.drawLine(it.x, it.y, point.x, point.y, linePaint) }
                previous = point
                visibleCount++
                sumX += point.x
                sumY += point.y
            }
            // Aucun nom permanent : l'Accueil privilégie le ciel étoilé.
            // Les abréviations restent disponibles dans les données, pas dans le fond visuel.
        }

        if (starOpacity > 0.01) {
            for ((star, point) in visibleStars) {
                if (star.magnitude > DIAL_MAX_VISUAL_MAGNITUDE) continue
                val brightness = ((6.6 - star.magnitude) / 7.5).coerceIn(0.08, 1.0)
                starPaint.alpha = (starOpacity * (110.0 + 145.0 * brightness)).toInt().coerceIn(0, 255)
                val starRadius = (0.60 + brightness * 2.15).toFloat() * density
                canvas.drawCircle(point.x, point.y, starRadius, starPaint)
            }
        }
    }

    fun clear() {
        generation.incrementAndGet()
        requestedKey = null
        localSky = null
    }

    companion object {
        private const val LOCAL_SKY_REFRESH_MS = 30_000L
        private const val DIAL_MAX_VISUAL_MAGNITUDE = 4.5
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-StarSky").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}
