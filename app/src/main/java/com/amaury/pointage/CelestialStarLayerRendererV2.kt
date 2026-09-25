package com.amaury.pointage

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amaury.pointage.v2.CelestialAmbientLightV2
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import com.amaury.pointage.v2.engine.*
import com.amaury.pointage.v2.ui.StarSkyCatalogLoaderV2
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The clock owns tracking and cadence; this adapter opens no sensor subscription. */
class CelestialStarLayerRendererV2(context: Context, private val onInvalidated: () -> Unit) {
    private data class Star(val id: Int, val magnitude: Double, val position: LocalStarPositionV2)
    private data class Sky(val place: String, val atMs: Long, val stars: List<Star>)
    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val sprite = CelestialStarSpritePainterV2()
    private var sky: Sky? = null
    private var requested: String? = null
    private val domePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rimShader = RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(66,64,102,158), Color.TRANSPARENT),
        floatArrayOf(0f,0.72f,0.98f,1f), Shader.TileMode.CLAMP)
    private val shaderMatrix = Matrix()
    private var gridKey: String? = null
    private var grid: List<Pair<Path, Boolean>> = emptyList()

    fun update(state: CelestialTrackerV2.State) {
        val snapshot = state.snapshot
        if (snapshot == null || state.locationQuality != CelestialLocationQualityV2.VALID) { clear(); return }
        val place = String.format(Locale.ROOT, "%.4f:%.4f", snapshot.latitudeDeg, snapshot.longitudeDeg)
        val key = "$place:${snapshot.atMs / 30_000L}"
        if (key == requested) return
        requested = key
        val token = generation.incrementAndGet()
        executor.execute {
            val result = runCatching {
                val catalog = StarSkyCatalogLoaderV2.load(appContext)
                val stars = catalog.stars.mapNotNull { (id, star) ->
                    if (star.visualMagnitude > 4.5) return@mapNotNull null
                    val p = StarSkyProjectionV2.horizontal(star, snapshot.latitudeDeg, snapshot.longitudeDeg, snapshot.atMs)
                    if (!p.aboveApparentHorizon) return@mapNotNull null
                    Star(id, star.visualMagnitude, p)
                }
                Sky(place, snapshot.atMs, stars)
            }.getOrNull()
            handler.post {
                if (generation.get() == token) {
                    if (result != null) { sky = result; onInvalidated() } else requested = null
                }
            }
        }
    }

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, state: CelestialTrackerV2.State?) {
        val snapshot = state?.snapshot ?: return
        if (state.locationQuality != CelestialLocationQualityV2.VALID || radius <= 1f) return
        val heading = CelestialHeadingPolicyV2.renderingHeadingDeg(state.deviceAzimuthDeg.toDouble(), state.headingQuality)
        drawDome(canvas, cx, cy, radius, heading)
        val current = sky ?: return
        val place = String.format(Locale.ROOT, "%.4f:%.4f", snapshot.latitudeDeg, snapshot.longitudeDeg)
        if (current.place != place || snapshot.atMs - current.atMs !in 0L..60_000L) return
        val render = CelestialRenderStateFactoryV2.build(
            snapshot = snapshot, weather = CelestialWeatherContextV2.currentStateFor(snapshot),
            ambient = CelestialAmbientLightV2.currentState(), orientationQuality = state.headingQuality,
            locationQuality = state.locationQuality, locationAgeMs = state.locationAgeMs,
            locationProvider = state.locationProvider, headingAgeMs = state.headingAgeMs,
            nowElapsedMs = SystemClock.elapsedRealtime())
        val visibility = render.starsVisibility.coerceIn(0.0, 1.0)
        if (visibility <= 0.005) return
        val quality = CelestialRenderQualityProviderV2.current(appContext)
        val animated = quality != CelestialRenderQualityV2.REDUCED &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        val seconds = SystemClock.uptimeMillis() / 1000.0
        for (star in current.stars) {
            val p = CelestialDomeV2.project(star.position.azimuthDeg, star.position.apparentAltitudeDeg, heading) ?: continue
            val style = CelestialStarAppearanceV2.resolve(star.magnitude, star.position.apparentAltitudeDeg,
                star.id, seconds, visibility, animated) ?: continue
            sprite.draw(canvas, cx + (p.x * radius).toFloat(), cy + (p.y * radius).toFloat(), density, style)
        }
    }

    private fun drawDome(canvas: Canvas, cx: Float, cy: Float, radius: Float, heading: Double) {
        val r = (radius * CelestialDomeV2.RADIUS_FRACTION).toFloat()
        shaderMatrix.setScale(r, r); shaderMatrix.postTranslate(cx, cy)
        rimShader.setLocalMatrix(shaderMatrix); domePaint.shader = rimShader
        canvas.drawCircle(cx, cy, r, domePaint); domePaint.shader = null
        val key = "$cx:$cy:$radius:${(heading * 10).toInt()}"
        if (key != gridKey) {
            fun curve(coordinates: List<Pair<Double, Double>>): Path = Path().apply {
                coordinates.forEachIndexed { i, (az, alt) ->
                    val p = CelestialDomeV2.project(az, alt, heading) ?: return@forEachIndexed
                    val x = cx + (p.x * radius).toFloat(); val y = cy + (p.y * radius).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            grid = buildList {
                for (alt in listOf(0.0,30.0,60.0)) add(curve((0..72).map { it * 5.0 to alt }) to (alt == 0.0))
                for (az in 0 until 360 step 60) add(curve((0..30).map { az.toDouble() to it * 3.0 }) to false)
            }
            gridKey = key
        }
        // Coordinate graticule only: no catalogue constellation connections.
        for ((path, horizon) in grid) {
            gridPaint.color = Color.argb(if (horizon) 51 else 18,255,255,255)
            gridPaint.strokeWidth = (if (horizon) 0.8f else 0.5f) * density
            canvas.drawPath(path, gridPaint)
        }
    }

    fun clear() { generation.incrementAndGet(); requested = null; sky = null; gridKey = null; grid = emptyList() }
    companion object {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-StarSky").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}
