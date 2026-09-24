package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Shader
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
 * Ciel réel plein écran réservé à l'onglet Accueil.
 *
 * Les étoiles proviennent exclusivement du catalogue embarqué BSC5P et les
 * traits de constellations de ConstellationLines. Aucun point décoratif aléatoire
 * n'est ajouté. Les constellations restent volontairement secondaires :
 * étoiles d'abord, traits fins ensuite, aucun libellé permanent.
 */
class CelestialHomeSkyBackgroundRendererV2(
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

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(0.45f * density, 0.8f)
        strokeCap = Paint.Cap.ROUND
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
                    LocalStar(hr = hr, magnitude = star.visualMagnitude, position = position)
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
        width: Float,
        height: Float,
        state: CelestialTrackerV2.State?
    ) {
        if (width <= 1f || height <= 1f) return
        val snapshot = state?.snapshot
        val nightOpacity = snapshot?.let {
            StarSkyProjectionV2.nightSkyOpacity(it.sun.altitudeDeg)
        } ?: 1.0

        drawAtmosphericBase(canvas, width, height, nightOpacity)

        val current = state ?: return
        if (current.locationQuality != CelestialLocationQualityV2.VALID) return
        val sky = localSky ?: return

        // Les positions restent astronomiquement réelles. En journée, une faible
        // opacité minimale garde la carte stellaire lisible sans prétendre que les
        // étoiles sont visibles à l'oeil nu.
        val starOpacity = 0.20 + 0.80 * nightOpacity
        val constellationOpacity = 0.10 + 0.16 * nightOpacity

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val scaleX = width * 0.52f
        val scaleY = height * 0.52f

        val projected = HashMap<Int, PointF>(sky.stars.size)
        val visibleStars = ArrayList<Pair<LocalStar, PointF>>(sky.stars.size)
        for (star in sky.stars) {
            val p = if (current.hasRealSky && current.deviceFrame != null) {
                StarSkyProjectionV2.projectToDevice(star.position, current.deviceFrame)
            } else {
                StarSkyProjectionV2.projectToZenithMap(star.position)
            } ?: continue
            val point = PointF(
                centerX + (p.x * scaleX).toFloat(),
                centerY + (p.y * scaleY).toFloat()
            )
            if (point.x < -24f || point.x > width + 24f ||
                point.y < -24f || point.y > height + 24f
            ) continue
            projected[star.hr] = point
            visibleStars += star to point
        }

        linePaint.alpha = (255.0 * constellationOpacity).toInt().coerceIn(0, 255)
        for (path in sky.paths) {
            var previous: PointF? = null
            for (hr in path.hrNumbers) {
                val point = projected[hr]
                if (point == null) {
                    previous = null
                    continue
                }
                previous?.let { canvas.drawLine(it.x, it.y, point.x, point.y, linePaint) }
                previous = point
            }
        }

        for ((star, point) in visibleStars) {
            val brightness = ((6.6 - star.magnitude) / 7.5).coerceIn(0.08, 1.0)
            starPaint.alpha = (255.0 * starOpacity * (0.34 + 0.66 * brightness))
                .toInt().coerceIn(0, 255)
            val radius = (0.65 + brightness * 2.25).toFloat() * density
            canvas.drawCircle(point.x, point.y, radius, starPaint)
        }
    }

    private fun drawAtmosphericBase(
        canvas: Canvas,
        width: Float,
        height: Float,
        nightOpacity: Double
    ) {
        val night = nightOpacity.toFloat().coerceIn(0f, 1f)
        val top = blend(Color.rgb(8, 28, 56), Color.rgb(1, 5, 14), night)
        val bottom = blend(Color.rgb(2, 9, 22), Color.rgb(0, 1, 7), night)
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, height,
            top, bottom, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        backgroundPaint.shader = null
    }

    private fun blend(day: Int, night: Int, amount: Float): Int {
        fun channel(color: Int, shift: Int) = (color shr shift) and 0xff
        val inverse = 1f - amount
        val r = (channel(day, 16) * inverse + channel(night, 16) * amount).toInt()
        val g = (channel(day, 8) * inverse + channel(night, 8) * amount).toInt()
        val b = (channel(day, 0) * inverse + channel(night, 0) * amount).toInt()
        return Color.rgb(r, g, b)
    }

    fun clear() {
        generation.incrementAndGet()
        requestedKey = null
        localSky = null
    }

    companion object {
        private const val LOCAL_SKY_REFRESH_MS = 30_000L
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-HomeSky").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}
