package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Fond facetté dont le reflet peut tourner sans recréer la vue. */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(degrees: Float) {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (kotlin.math.abs(normalized - lightAngle) < 1.2f) return
        lightAngle = normalized
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
        if (nowPressed == pressed) return false
        pressed = nowPressed
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

        val cx = rect.centerX()
        val cy = rect.centerY()
        val diagonal = kotlin.math.sqrt(rect.width() * rect.width() + rect.height() * rect.height()) * 0.62f
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad) * diagonal).toFloat()
        val dy = (sin(rad) * diagonal).toFloat()

        val colors = if (dark) {
            if (pressed) intArrayOf(
                Color.parseColor("#191712"),
                Color.parseColor("#29251D"),
                Color.parseColor("#151515"),
                Color.parseColor("#070707")
            ) else intArrayOf(
                Color.parseColor("#10100E"),
                Color.parseColor("#3B3528"),
                Color.parseColor("#1B1B1B"),
                Color.parseColor("#080808")
            )
        } else {
            if (pressed) intArrayOf(
                Color.parseColor("#D7CDBC"),
                Color.parseColor("#F0EADD"),
                Color.parseColor("#FBF8F2"),
                Color.parseColor("#CEC2AE")
            ) else intArrayOf(
                Color.parseColor("#D4C8B4"),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#F8F3E9"),
                Color.parseColor("#CFC1AA")
            )
        }

        fillPaint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            colors,
            floatArrayOf(0f, 0.30f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )

        val radius = 15f * density
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        strokePaint.strokeWidth = 1.2f * density
        strokePaint.color = if (dark) {
            if (pressed) Color.parseColor("#9D7B38") else Color.parseColor("#D6A84B")
        } else {
            if (pressed) Color.parseColor("#9A711D") else Color.parseColor("#C4932E")
        }
        val half = strokePaint.strokeWidth / 2f
        rect.inset(half, half)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        rect.inset(-half, -half)
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; strokePaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter; strokePaint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
