package com.amaury.pointage

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import com.amaury.pointage.v2.engine.*
import com.amaury.pointage.v2.ui.StarSkyCatalogLoaderV2
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Real BSC5 stars only. No constellation connectors or random star positions. */
class CelestialHomeSkyBackgroundRendererV2(context: Context, private val onInvalidated: () -> Unit) {
    private data class Star(val id: Int, val magnitude: Double, val altitude: Double, val x: Double, val y: Double)
    private data class Sky(val key: String, val place: String, val atMs: Long, val stars: List<Star>)
    private data class Cache(val key: String, val place: String, val atMs: Long, val bitmap: Bitmap)
    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val cloudTextures = CelestialCloudTextureRendererV2(onInvalidated)
    private val sprite = CelestialStarSpritePainterV2()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sky: Sky? = null
    private var cache: Cache? = null
    private var requestedSky: String? = null
    private var requestedCache: String? = null
    private var tickScheduled = false
    private val tick = Runnable { tickScheduled = false; onInvalidated() }

    fun update(state: CelestialTrackerV2.State) {
        val snapshot = state.snapshot
        if (snapshot == null || state.locationQuality != CelestialLocationQualityV2.VALID) { clear(); return }
        CelestialWeatherContextV2.refreshIfNeeded(snapshot) { onInvalidated() }
        val place = String.format(Locale.ROOT, "%.4f:%.4f", snapshot.latitudeDeg, snapshot.longitudeDeg)
        val key = "$place:${snapshot.atMs / 30_000L}"
        if (requestedSky == key) return
        requestedSky = key
        val token = generation.incrementAndGet()
        executor.execute {
            val result = runCatching {
                val catalog = StarSkyCatalogLoaderV2.load(appContext)
                val stars = catalog.stars.mapNotNull { (id, star) ->
                    if (star.visualMagnitude > 4.2) return@mapNotNull null
                    val position = StarSkyProjectionV2.horizontal(star, snapshot.latitudeDeg, snapshot.longitudeDeg, snapshot.atMs)
                    val p = CelestialPanoramaGeometryV2.normalized(position) ?: return@mapNotNull null
                    Star(id, star.visualMagnitude, position.apparentAltitudeDeg, p.x01, p.y01)
                }
                Sky(key, place, snapshot.atMs, stars)
            }.getOrNull()
            mainHandler.post {
                if (generation.get() == token) {
                    if (result != null) { sky = result; requestedCache = null; onInvalidated() }
                    else requestedSky = null
                }
            }
        }
    }

    fun draw(canvas: Canvas, width: Float, height: Float, state: CelestialTrackerV2.State?, renderState: CelestialRenderStateV2) {
        if (width <= 1f || height <= 1f) return
        drawAtmosphericBase(canvas, width, height, renderState)
        val snapshot = state?.snapshot
        if (state == null || snapshot == null || state.locationQuality != CelestialLocationQualityV2.VALID) { clear(); return }
        val quality = CelestialRenderQualityProviderV2.current(appContext)
        cloudTextures.draw(canvas, width, height, renderState, quality)
        val current = sky ?: return
        val place = String.format(Locale.ROOT, "%.4f:%.4f", snapshot.latitudeDeg, snapshot.longitudeDeg)
        if (current.place != place || snapshot.atMs - current.atMs !in 0L..60_000L) return
        val visibility = renderState.starsVisibility.coerceIn(0.0, 1.0)
        if (visibility <= 0.005) return
        val heading = CelestialHeadingPolicyV2.renderingHeadingDeg(state.deviceAzimuthDeg.toDouble(), state.headingQuality)
        val key = "${current.key}:${width.toInt()}x${height.toInt()}:${quality.name}"
        ensureCache(current, key, width, height, quality)
        val ready = cache
        // Keep the previous same-place texture while refreshing time. Never flash
        // to an empty sky between two successful asynchronous cache publications.
        if (ready != null && ready.place == place && snapshot.atMs - ready.atMs in 0L..60_000L) {
            paint.alpha = (255 * visibility).toInt().coerceIn(0, 255)
            val left = (CelestialPanoramaGeometryV2.baseLeftFraction(heading) * width).toFloat()
            canvas.drawBitmap(ready.bitmap, null, RectF(left, 0f, left + width, height), paint)
            if (left != 0f) {
                val other = if (left > 0f) left - width else left + width
                canvas.drawBitmap(ready.bitmap, null, RectF(other, 0f, other + width, height), paint)
            }
            paint.alpha = 255
        }
        val animate = quality != CelestialRenderQualityV2.REDUCED &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        val seconds = SystemClock.uptimeMillis() / 1000.0
        val offset = CelestialPanoramaGeometryV2.baseLeftFraction(heading)
        for (star in current.stars) {
            if (star.magnitude > CelestialStarAppearanceV2.TWINKLE_MAX_MAGNITUDE) continue
            val style = CelestialStarAppearanceV2.resolve(star.magnitude, star.altitude, star.id, seconds, visibility, animate) ?: continue
            val x = (((star.x + offset) % 1.0 + 1.0) % 1.0 * width).toFloat()
            val y = (star.y * height).toFloat()
            sprite.draw(canvas, x, y, density, style)
            val halo = (style.haloRadius * density).toFloat()
            if (x < halo) sprite.draw(canvas, x + width, y, density, style)
            if (x > width - halo) sprite.draw(canvas, x - width, y, density, style)
        }
        // One pending tick at most. A hidden view that no longer draws cannot
        // restart it; clear() also cancels the pending callback immediately.
        if (animate && !tickScheduled) {
            tickScheduled = true
            mainHandler.postDelayed(tick, if (quality == CelestialRenderQualityV2.HIGH) 50L else 100L)
        } else if (!animate && tickScheduled) {
            mainHandler.removeCallbacks(tick); tickScheduled = false
        }
    }

    private fun ensureCache(sky: Sky, key: String, width: Float, height: Float, quality: CelestialRenderQualityV2) {
        if (cache?.key == key || requestedCache == key) return
        requestedCache = key
        val token = generation.get()
        val scale = minOf(1f, quality.maxPanoramaWidthPx / width, quality.maxPanoramaHeightPx / height)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        executor.execute {
            val result = runCatching {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val target = Canvas(bitmap)
                val painter = CelestialStarSpritePainterV2()
                for (star in sky.stars) {
                    if (generation.get() != token) { bitmap.recycle(); return@runCatching null }
                    if (star.magnitude <= CelestialStarAppearanceV2.TWINKLE_MAX_MAGNITUDE) continue
                    val style = CelestialStarAppearanceV2.resolve(star.magnitude, star.altitude, star.id, 0.0, 1.0, false) ?: continue
                    val x = (star.x * w).toFloat(); val y = (star.y * h).toFloat()
                    for (dx in floatArrayOf(0f, -w.toFloat(), w.toFloat())) painter.draw(target, x + dx, y, density * scale, style)
                }
                Cache(key, sky.place, sky.atMs, bitmap)
            }.getOrNull()
            mainHandler.post {
                if (generation.get() == token && requestedCache == key) {
                    requestedCache = null
                    if (result != null) { cache = result; onInvalidated() }
                } else result?.bitmap?.recycle() // Never published, therefore safe.
            }
        }
    }

    private fun drawAtmosphericBase(canvas: Canvas, width: Float, height: Float, state: CelestialRenderStateV2) {
        val day = state.solarLightLevel.coerceIn(0.0, 1.0)
        val twilight = state.twilightLevel.coerceIn(0.0, 1.0)
        val night = state.nightLevel.coerceIn(0.0, 1.0)
        fun mix(a: Int, b: Int, c: Int): Int {
            val total = (day + twilight + night).coerceAtLeast(0.0001)
            fun channel(shift: Int) = ((((a shr shift) and 255) * day + ((b shr shift) and 255) * twilight + ((c shr shift) and 255) * night) / total).toInt().coerceIn(0, 255)
            return Color.rgb(channel(16), channel(8), channel(0))
        }
        val top = mix(Color.rgb(54,139,224), Color.rgb(52,65,116), Color.rgb(1,5,14))
        val bottom = mix(Color.rgb(176,222,248), Color.rgb(235,137,92), Color.rgb(0,1,7))
        backgroundPaint.shader = LinearGradient(0f,0f,0f,height,top,bottom,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width,height,backgroundPaint)
        backgroundPaint.shader = null
    }

    fun clear() {
        generation.incrementAndGet()
        cloudTextures.clear()
        mainHandler.removeCallbacks(tick)
        tickScheduled = false
        requestedSky = null; requestedCache = null; sky = null; cache = null
        // Published bitmaps may still be referenced by a hardware display list.
        // Release references instead of recycling a texture still in use by GPU.
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-HomeSky").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}
