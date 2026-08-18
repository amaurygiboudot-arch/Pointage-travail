package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private var angle = -55f
    private var visibleSun = false

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
        visibleSun = visible
        visibility = if (visible) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleSun || width <= 0 || height <= 0) return

        val rads = Math.toRadians(angle.toDouble())
        val cx = width * 0.5f + cos(rads).toFloat() * width * 0.34f
        val cy = height * 0.5f + sin(rads).toFloat() * height * 0.32f
        val base = min(width, height).toFloat()
        val glowRadius = max(base * 0.10f, 30f)
        val coreRadius = glowRadius * 0.28f

        paint.shader = RadialGradient(
            cx,
            cy,
            glowRadius,
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
}
