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

    // Conservée pour compatibilité avec l'ancien contrôleur de lumière.
    // La position céleste ne dépend plus de l'orientation du téléphone.
    fun updateLightAngle(newAngle: Float) = Unit

    fun setSunVisible(visible: Boolean) {
        visibleCelestial = visible
        visibility = if (visible) VISIBLE else GONE
        handler.removeCallbacks(refreshTask)
        if (visible) {
            refreshAstronomy()
            handler.postDelayed(refreshTask, 30_000L)
        }
        invalidate()
    }

    fun setNightMode(night: Boolean) {
        if (nightMode == night) return
        nightMode = night
        AppThemeCatalog.setCelestialNight(context, night)
        contentDescription = "Soleil et lune"
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
        val location = lastKnownLocation() ?: return
        val now = System.currentTimeMillis()
        sunPosition = CelestialEphemeris.sun(location.latitude, location.longitude, now)
        moonPosition = CelestialEphemeris.moon(location.latitude, location.longitude, now)
        invalidate()
    }

    private fun lastKnownLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            lm.getProviders(true)
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleCelestial || width <= 0 || height <= 0) return

        val base = min(width, height).toFloat()
        val glowRadius = max(base * 0.085f, 24f)
        val coreRadius = glowRadius * 0.29f

        sunPosition?.takeIf { it.altitude > -0.833 }?.let { pos ->
            val (x, y) = mapSkyPosition(pos)
            drawSun(canvas, x, y, glowRadius, coreRadius)
        }

        moonPosition?.takeIf { it.altitude > -0.5 }?.let { pos ->
            val (x, y) = mapSkyPosition(pos)
            drawMoon(canvas, x, y, glowRadius * 0.90f, coreRadius * 0.92f)
        }
    }

    private fun mapSkyPosition(position: CelestialEphemeris.Position): Pair<Float, Float> {
        // Carte céleste stable : N=0/360°, E=90°, S=180°, O=270°.
        // La hauteur réelle au-dessus de l'horizon contrôle la verticale.
        val xMargin = width * 0.08f
        val usableWidth = width * 0.84f
        val x = xMargin + (position.azimuth / 360.0).toFloat() * usableWidth

        val horizonY = height * 0.82f
        val zenithY = height * 0.13f
        val altitude = position.altitude.coerceIn(0.0, 90.0)
        val y = horizonY - (altitude / 90.0).toFloat() * (horizonY - zenithY)
        return x to y
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, glowRadius: Float, coreRadius: Float) {
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(215, 255, 244, 188),
                Color.argb(145, 255, 196, 75),
                Color.argb(55, 255, 155, 30),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.28f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, paint)
        paint.shader = null
        paint.color = Color.argb(235, 255, 225, 125)
        canvas.drawCircle(cx, cy, coreRadius, paint)
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, glowRadius: Float, coreRadius: Float) {
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(175, 235, 244, 255),
                Color.argb(105, 165, 195, 235),
                Color.argb(35, 100, 130, 190),
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

        paint.color = Color.argb(245, 225, 235, 255)
        canvas.drawPath(moonPath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.2f, coreRadius * 0.08f)
        paint.color = Color.argb(180, 255, 255, 255)
        canvas.drawPath(moonPath, paint)
        paint.style = Paint.Style.FILL
    }
}
