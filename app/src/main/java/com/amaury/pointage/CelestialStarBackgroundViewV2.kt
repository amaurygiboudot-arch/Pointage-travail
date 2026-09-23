package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.engine.LocalStarPositionV2
import com.amaury.pointage.v2.engine.StarSkyProjectionV2
import com.amaury.pointage.v2.ui.ConstellationPathV2
import com.amaury.pointage.v2.ui.StarSkyCatalogLoaderV2
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Fond astronomique réel de l'Accueil.
 *
 * Les étoiles sont issues du BSC5P embarqué et les traits relient des HR
 * réellement référencées. Le GPS et le cap restent locaux au téléphone.
 */
class CelestialStarBackgroundViewV2 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class LocalStar(
        val hr: Int,
        val magnitude: Double,
        val position: LocalStarPositionV2
    )

    private data class LocalSky(
        val latitude: Double,
        val longitude: Double,
        val timeBucket: Long,
        val stars: List<LocalStar>,
        val paths: List<ConstellationPathV2>
    )

    private val density = resources.displayMetrics.density
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
        textSize = 9f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
    }

    private val generation = AtomicLong(0)
    private var state: CelestialTrackerV2.State? = null
    @Volatile private var localSky: LocalSky? = null
    private var requestedKey: String? = null
    private var subscribed = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSubscription()
    }

    override fun onDetachedFromWindow() {
        if (subscribed) {
            CelestialTrackerV2.unsubscribe(this)
            subscribed = false
        }
        generation.incrementAndGet()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) post { updateSubscription() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) post { updateSubscription() }
    }

    private fun updateSubscription() {
        val shouldSubscribe = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (shouldSubscribe && !subscribed) {
            subscribed = true
            CelestialTrackerV2.subscribe(context, this) { next ->
                state = next
                requestLocalSky(next)
                postInvalidateOnAnimation()
            }
        } else if (!shouldSubscribe && subscribed) {
            CelestialTrackerV2.unsubscribe(this)
            subscribed = false
        }
    }

    private fun requestLocalSky(next: CelestialTrackerV2.State) {
        val snapshot = next.snapshot ?: return
        if (!next.hasRealSky) return
        val bucket = snapshot.atMs / LOCAL_SKY_REFRESH_MS
        val key = "%.4f:%.4f:%d".format(snapshot.latitudeDeg, snapshot.longitudeDeg, bucket)
        if (key == requestedKey) return
        requestedKey = key
        val requestGeneration = generation.incrementAndGet()
        val appContext = context.applicationContext

        executor.execute {
            val catalog = runCatching { StarSkyCatalogLoaderV2.load(appContext) }.getOrNull() ?: return@execute
            val prepared = catalog.stars.mapNotNull { (hr, star) ->
                runCatching {
                    LocalStar(
                        hr = hr,
                        magnitude = star.visualMagnitude,
                        position = StarSkyProjectionV2.horizontal(
                            star = star,
                            latitudeDeg = snapshot.latitudeDeg,
                            longitudeDeg = snapshot.longitudeDeg,
                            timeMs = snapshot.atMs
                        )
                    )
                }.getOrNull()
            }
            val result = LocalSky(
                latitude = snapshot.latitudeDeg,
                longitude = snapshot.longitudeDeg,
                timeBucket = bucket,
                stars = prepared,
                paths = catalog.constellationPaths
            )
            post {
                if (requestGeneration == generation.get() && isAttachedToWindow) {
                    localSky = result
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = state ?: return
        val snapshot = current.snapshot ?: return
        val frame = current.deviceFrame ?: return
        if (!current.hasRealSky) return

        val opacity = StarSkyProjectionV2.nightSkyOpacity(snapshot.sun.altitudeDeg)
        if (opacity <= 0.01) return
        val sky = localSky ?: return

        val cx = width * 0.5f
        val cy = height * 0.55f
        val radius = min(width, height) * 0.50f
        if (radius <= 1f) return

        val projected = HashMap<Int, PointF>(sky.stars.size / 2)
        val visibleStars = ArrayList<Pair<LocalStar, PointF>>(sky.stars.size / 2)
        for (star in sky.stars) {
            if (!star.position.aboveApparentHorizon) continue
            val point = StarSkyProjectionV2.projectToDevice(star.position, frame) ?: continue
            val screen = PointF(
                cx + (point.x * radius).toFloat(),
                cy + (point.y * radius).toFloat()
            )
            projected[star.hr] = screen
            visibleStars += star to screen
        }

        linePaint.alpha = (opacity * 72.0).toInt().coerceIn(0, 255)
        labelPaint.alpha = (opacity * 112.0).toInt().coerceIn(0, 255)
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
                if (previous != null) {
                    canvas.drawLine(previous.x, previous.y, point.x, point.y, linePaint)
                }
                previous = point
                visibleCount++
                sumX += point.x
                sumY += point.y
            }
            if (visibleCount >= 3) {
                canvas.drawText(
                    path.abbreviation,
                    sumX / visibleCount,
                    sumY / visibleCount,
                    labelPaint
                )
            }
        }

        for ((star, point) in visibleStars) {
            val brightness = ((6.6 - star.magnitude) / 7.5).coerceIn(0.08, 1.0)
            starPaint.alpha = (opacity * (85.0 + 170.0 * brightness)).toInt().coerceIn(0, 255)
            val radiusPx = (0.45 + brightness * 1.9).toFloat() * density
            canvas.drawCircle(point.x, point.y, radiusPx, starPaint)
        }
    }

    companion object {
        private const val LOCAL_SKY_REFRESH_MS = 30_000L
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-StarSky").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}
