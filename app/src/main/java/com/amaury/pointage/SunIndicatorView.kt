package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class SunIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonPath = Path()
    private val moonCutout = Path()
    private var angle = -55f
    private var visibleCelestial = false
    private var nightMode = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateLightAngle(newAngle: Float) {
        angle = newAngle
        invalidate()
    }

    fun setSunVisible(visible: Boolean) {
        visibleCelestial = visible
        visibility = if (visible) VISIBLE else GONE
        invalidate()
    }

    fun setNightMode(night: Boolean) {
        if (nightMode == night) return
        nightMode = night
        AppThemeCatalog.setCelestialNight(context, night)
        contentDescription = if (night) "Lune" else "Soleil"
        (context as? Activity)?.let { activity ->
            AppearanceManager.apply(activity)
            PointageWidgetProvider.updateAll(activity)
            QuickActionsWidgetProvider.updateAll(activity)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleCelestial || width <= 0 || height <= 0) return

        val rads = Math.toRadians(angle.toDouble())
        val cx = width * 0.5f + cos(rads).toFloat() * width * 0.34f
        val cy = height * 0.5f + sin(rads).toFloat() * height * 0.32f
        val base = min(width, height).toFloat()
        val glowRadius = max(base * 0.10f, 30f)
        val coreRadius = glowRadius * 0.28f

        if (nightMode) drawMoon(canvas, cx, cy, glowRadius, coreRadius)
        else drawSun(canvas, cx, cy, glowRadius, coreRadius)
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
