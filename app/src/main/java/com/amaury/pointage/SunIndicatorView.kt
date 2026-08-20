package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SunIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonPath = Path()
    private val moonCutout = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var visibleCelestial = false
    private var nightMode = false
    private var sunPosition: CelestialEphemeris.Position? = null
    private var moonPosition: CelestialEphemeris.Position? = null
    private var deviceAzimuth = 0f
    private var devicePitch = 0f

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshAstronomy()
            if (isAttachedToWindow && visibleCelestial) handler.postDelayed(this, 30_000L)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateLightAngle(newAngle: Float) = Unit

    fun setDeviceOrientation(azimuth: Float, pitch: Float) {
        deviceAzimuth = normalize(azimuth)
        devicePitch = pitch.coerceIn(-90f, 90f)
        invalidate()
    }

    fun setSunVisible(visible: Boolean) {
        val dynamicEnabled = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
            .getBoolean("solar_lighting_enabled", false)
        visibleCelestial = visible || dynamicEnabled
        visibility = if (visibleCelestial) VISIBLE else GONE
        handler.removeCallbacks(refreshTask)
        if (visibleCelestial) {
            refreshAstronomy()
            handler.postDelayed(refreshTask, 30_000L)
        }
        invalidate()
    }

    fun setNightMode(night: Boolean) {
        if (nightMode == night) return
        nightMode = night
        AppThemeCatalog.setCelestialNight(context, night)
        contentDescription = "Soleil et Lune"
        (context as? Activity)?.let { activity ->
            AppearanceManager.apply(activity)
            PointageWidgetProvider.updateAll(activity)
            QuickActionsWidgetProvider.updateAll(activity)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibleCelestial) {
            handler.removeCallbacks(refreshTask)
            refreshAstronomy()
            handler.postDelayed(refreshTask, 30_000L)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refreshTask)
        super.onDetachedFromWindow()
    }

    private fun refreshAstronomy() {
        val location = lastKnownLocation()
        if (location != null) {
            val now = System.currentTimeMillis()
            sunPosition = CelestialEphemeris.sun(location.latitude, location.longitude, now)
            moonPosition = CelestialEphemeris.moon(location.latitude, location.longitude, now)
        } else {
            sunPosition = null
            moonPosition = null
        }
        invalidate()
    }

    private fun lastKnownLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleCelestial || width <= 0 || height <= 0) return

        val base = min(width, height).toFloat()
        val activeGlow = max(base * 0.10f, 26f)
        val inactiveGlow = activeGlow * 0.68f
        val activeCore = activeGlow * 0.30f
        val inactiveCore = activeCore * 0.78f

        val sun = sunPosition
        val moon = moonPosition

        if (sun != null) {
            val point = mapSkyPosition(sun)
            val active = !nightMode
            drawSun(canvas, point.first, point.second, if (active) activeGlow else inactiveGlow, if (active) activeCore else inactiveCore, active)
        }

        if (moon != null) {
            val point = mapSkyPosition(moon)
            val active = nightMode
            drawMoon(canvas, point.first, point.second, if (active) activeGlow * 0.95f else inactiveGlow * 0.92f, if (active) activeCore else inactiveCore, active)
        }

        if (sun == null && moon == null) {
            val sunFallback = fallbackPosition(false)
            val moonFallback = fallbackPosition(true)
            drawSun(canvas, sunFallback.first, sunFallback.second, if (!nightMode) activeGlow else inactiveGlow, if (!nightMode) activeCore else inactiveCore, !nightMode)
            drawMoon(canvas, moonFallback.first, moonFallback.second, if (nightMode) activeGlow * 0.95f else inactiveGlow * 0.92f, if (nightMode) activeCore else inactiveCore, nightMode)
        }
    }

    private fun fallbackPosition(night: Boolean): Pair<Float, Float> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val progress = if (night) {
            if (hour >= 20) ((hour - 20) / 11f).coerceIn(0f, 1f) else ((hour + 4) / 11f).coerceIn(0f, 1f)
        } else {
            ((hour - 7) / 13f).coerceIn(0f, 1f)
        }
        val baseX = width * (0.10f + progress * 0.80f)
        val rotatedX = ((baseX / width) - deviceAzimuth / 360f)
        val wrapped = ((rotatedX % 1f) + 1f) % 1f
        val x = width * (0.08f + wrapped * 0.84f)
        val arc = 1f - abs(progress * 2f - 1f)
        val pitchShift = (devicePitch / 90f) * height * 0.22f
        val y = (height * (0.78f - arc * 0.52f) + pitchShift).coerceIn(height * 0.08f, height * 0.92f)
        return x to y
    }

    private fun mapSkyPosition(position: CelestialEphemeris.Position): Pair<Float, Float> {
        val relative = shortestDelta(deviceAzimuth, position.azimuth.toFloat())
        val x = width * (0.50f + (relative / 220f)).coerceIn(0.06f, 0.94f)

        val altitude = position.altitude.toFloat().coerceIn(-35f, 90f)
        val apparentAltitude = altitude + devicePitch * 0.70f
        val normalizedAltitude = ((apparentAltitude + 35f) / 125f).coerceIn(0f, 1f)
        val y = height * (0.90f - normalizedAltitude * 0.78f)
        return x to y
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, glowRadius: Float, coreRadius: Float, active: Boolean) {
        val boost = if (active) 1f else 0.48f
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((235 * boost).toInt(), 255, 246, 190),
                Color.argb((175 * boost).toInt(), 255, 205, 85),
                Color.argb((75 * boost).toInt(), 255, 155, 30),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.28f, 0.64f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, paint)
        paint.shader = null
        paint.color = Color.argb(if (active) 250 else 150, 255, 226, 120)
        canvas.drawCircle(cx, cy, coreRadius, paint)
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, glowRadius: Float, coreRadius: Float, active: Boolean) {
        val boost = if (active) 1f else 0.46f
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((205 * boost).toInt(), 235, 244, 255),
                Color.argb((125 * boost).toInt(), 165, 195, 235),
                Color.argb((45 * boost).toInt(), 100, 130, 190),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 0.64f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, paint)
        paint.shader = null

        moonPath.reset()
        moonCutout.reset()
        moonPath.addCircle(cx, cy, coreRadius * 1.12f, Path.Direction.CW)
        moonCutout.addCircle(cx + coreRadius * 0.48f, cy - coreRadius * 0.12f, coreRadius * 0.98f, Path.Direction.CW)
        moonPath.op(moonCutout, Path.Op.DIFFERENCE)

        paint.color = Color.argb(if (active) 250 else 155, 225, 235, 255)
        canvas.drawPath(moonPath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.2f, coreRadius * 0.08f)
        paint.color = Color.argb(if (active) 190 else 110, 255, 255, 255)
        canvas.drawPath(moonPath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
}
