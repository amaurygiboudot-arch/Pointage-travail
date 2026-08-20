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
        contentDescription = if (night) "Soleil et Lune — éclairage par la Lune" else "Soleil et Lune — éclairage par le Soleil"
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
        val glowRadius = max(base * 0.10f, 26f)
        val coreRadius = glowRadius * 0.30f

        val sunPoint = sunPosition?.let { mapSkyPosition(it) } ?: fallbackPosition(night = false)
        val moonPoint = moonPosition?.let { mapSkyPosition(it) } ?: fallbackPosition(night = true)

        if (nightMode) {
            // La nuit : les deux restent visibles, mais seule la Lune est la source lumineuse active.
            drawSun(canvas, sunPoint.first, sunPoint.second, glowRadius * 0.72f, coreRadius * 0.72f, active = false)
            drawMoon(canvas, moonPoint.first, moonPoint.second, glowRadius, coreRadius, active = true)
        } else {
            // Le jour : les deux restent visibles, mais seul le Soleil pilote l'éclairage.
            drawMoon(canvas, moonPoint.first, moonPoint.second, glowRadius * 0.72f, coreRadius * 0.72f, active = false)
            drawSun(canvas, sunPoint.first, sunPoint.second, glowRadius, coreRadius, active = true)
        }
    }

    private fun fallbackPosition(night: Boolean): Pair<Float, Float> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val progress = if (night) {
            if (hour >= 18) ((hour - 18) / 12f).coerceIn(0f, 1f) else ((hour + 6) / 12f).coerceIn(0f, 1f)
        } else {
            ((hour - 6) / 12f).coerceIn(0f, 1f)
        }
        val x = width * (0.10f + progress * 0.80f)
        val arc = 1f - kotlin.math.abs(progress * 2f - 1f)
        val y = height * (0.80f - arc * 0.55f)
        return x to y
    }

    private fun mapSkyPosition(position: CelestialEphemeris.Position): Pair<Float, Float> {
        val xMargin = width * 0.08f
        val usableWidth = width * 0.84f
        val x = xMargin + (position.azimuth / 360.0).toFloat() * usableWidth

        // On conserve les deux astres visibles même lorsqu'ils sont sous l'horizon.
        // +90° = haut de la zone, 0° = milieu, -90° = bas de la zone.
        val topY = height * 0.10f
        val bottomY = height * 0.90f
        val normalizedAltitude = ((position.altitude.coerceIn(-90.0, 90.0) + 90.0) / 180.0).toFloat()
        val y = bottomY - normalizedAltitude * (bottomY - topY)
        return x to y
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, glowRadius: Float, coreRadius: Float, active: Boolean) {
        val glowAlpha = if (active) 235 else 90
        val middleAlpha = if (active) 175 else 55
        val outerAlpha = if (active) 75 else 20
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(glowAlpha, 255, 246, 190),
                Color.argb(middleAlpha, 255, 205, 85),
                Color.argb(outerAlpha, 255, 155, 30),
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
        val glowAlpha = if (active) 205 else 85
        val middleAlpha = if (active) 125 else 50
        val outerAlpha = if (active) 45 else 18
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(glowAlpha, 235, 244, 255),
                Color.argb(middleAlpha, 165, 195, 235),
                Color.argb(outerAlpha, 100, 130, 190),
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
        paint.color = Color.argb(if (active) 190 else 90, 255, 255, 255)
        canvas.drawPath(moonPath, paint)
        paint.style = Paint.Style.FILL
    }
}
