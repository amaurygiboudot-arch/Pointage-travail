package com.amaury.pointage

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amaury.pointage.v2.engine.*
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.round

/** Platform adapter only: no weather request, classification, GPS or astronomy calculation. */
internal class CelestialCloudTextureRendererV2(private val invalidate: () -> Unit) {
    private data class Origin(val latitude: Double, val longitude: Double, val source: String?)
    private data class Key(val recipe: CloudTextureRecipeV2, val origin: Origin)
    private data class Frame(val key: Key, val bitmap: Bitmap)
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    @Volatile private var desired: Key? = null
    private var working = false
    private var failed: Key? = null
    private var current: Frame? = null
    private var previous: Frame? = null
    private var switchedAt = 0L
    private var tickScheduled = false
    private val tick = Runnable { tickScheduled = false; invalidate() }

    /** Called on the UI thread, before stars (their attenuation is already canonical). */
    fun draw(canvas: Canvas, width: Float, height: Float,
             state: CelestialRenderStateV2, quality: CelestialRenderQualityV2) {
        val clouds = state.clouds
        val expires = state.cloudsExpiresAtMs
        val fetched = state.cloudsFetchedAtMs
        val wallNow = System.currentTimeMillis()
        if (clouds == null || expires == null || fetched == null || wallNow < fetched || wallNow > expires || state.dataFreshness.weatherStatus != CelestialDataStatusV2.FRESH ||
            state.dataFreshness.locationStatus != CelestialDataStatusV2.FRESH ||
            !width.isFinite() || !height.isFinite() || width <= 1f || height <= 1f ||
            listOf(state.solarLightLevel, state.twilightLevel, state.nightLevel).any { !it.isFinite() }) {
            clear(); return
        }
        val motion = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        val detail = when (quality) {
            CelestialRenderQualityV2.REDUCED -> 2
            CelestialRenderQualityV2.BALANCED -> 3
            CelestialRenderQualityV2.HIGH -> 4
        }
        val w = when (quality) {
            CelestialRenderQualityV2.REDUCED -> 64
            CelestialRenderQualityV2.BALANCED -> 96
            CelestialRenderQualityV2.HIGH -> 128
        }
        val h = (w * (height / width).toDouble()).toInt().coerceIn(32, 256)
        fun light(value: Double) = floor(value.coerceIn(0.0, 1.0) * 64.0) / 64.0
        val key = Key(CloudTextureRecipeV2(clouds, state.weatherType, w, h, detail,
            if (motion) floor(System.currentTimeMillis() / 20_000.0) * 20.0 else 0.0,
            light(state.solarLightLevel), light(state.twilightLevel), light(state.nightLevel)),
            Origin(round(state.latitudeDeg * 100.0) / 100.0,
                round(state.longitudeDeg * 100.0) / 100.0, state.dataFreshness.weatherSource))
        desired = key
        // Never carry a texture from another weather cell/source while a new one is computed.
        if (current?.key?.origin != key.origin) { current = null; previous = null }
        if (current?.key != key && !working && failed != key) request(key)
        val frame = current
        val now = SystemClock.uptimeMillis()
        val fraction = if (motion) ((now - switchedAt) / 6_000.0).coerceIn(0.0, 1.0) else 1.0
        if (frame != null) {
            val rect = RectF(0f, 0f, width, height)
            if (fraction >= 1.0) {
                previous = null
                paint.alpha = 255
                canvas.drawBitmap(frame.bitmap, null, rect, paint)
            } else {
                // Premultiplied linear crossfade in an isolated layer, not double source-over.
                val saved = canvas.saveLayer(rect, null)
                paint.xfermode = add
                previous?.let {
                    paint.alpha = (255.0 * (1.0 - fraction)).toInt()
                    canvas.drawBitmap(it.bitmap, null, rect, paint)
                }
                paint.alpha = (255.0 * fraction).toInt()
                canvas.drawBitmap(frame.bitmap, null, rect, paint)
                paint.xfermode = null
                paint.alpha = 255
                canvas.restoreToCount(saved)
            }
        }
        // Even with animations disabled, expiry must not depend on a new GPS event.
        if (!tickScheduled) {
            tickScheduled = true
            handler.postDelayed(tick, if (motion && frame != null && fraction < 1.0 && quality != CelestialRenderQualityV2.REDUCED) 250L else 1_000L)
        }
    }

    private fun request(key: Key) {
        working = true
        executor.execute {
            val bitmap = runCatching {
                val pixels = CelestialCloudTextureV2.rasterize(key.recipe) { desired != key }
                    ?: return@runCatching null
                Bitmap.createBitmap(pixels, key.recipe.width, key.recipe.height, Bitmap.Config.ARGB_8888)
            }.getOrNull()
            handler.post {
                working = false
                if (desired == key) {
                    if (bitmap == null) {
                        failed = key
                    } else {
                        previous = current
                        current = Frame(key, bitmap)
                        switchedAt = SystemClock.uptimeMillis()
                        failed = null
                    }
                } else {
                    // This bitmap was never submitted to Canvas, so recycling it is safe.
                    bitmap?.recycle()
                }
                if (desired != null) invalidate()
            }
        }
    }

    fun clear() {
        desired = null
        current = null
        previous = null
        failed = null
        handler.removeCallbacks(tick)
        tickScheduled = false
        // Keep the in-flight slot occupied until its cancelled worker returns.
        // Displayed bitmaps are released, not recycled while the GPU may still use them.
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-CloudTexture").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
}
