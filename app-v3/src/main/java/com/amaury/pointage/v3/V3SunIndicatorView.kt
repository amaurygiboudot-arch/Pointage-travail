package com.amaury.pointage.v3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class V3SunIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var currentX = .5f
    private var currentY = .18f
    private var targetX = .5f
    private var targetY = .18f
    private var currentAlpha = 0f
    private var targetAlpha = 0f
    private var animating = false

    fun updateSun(sunAzimuth: Float, sunAltitude: Float, deviceHeading: Float) {
        if (sunAltitude <= -4f) {
            targetAlpha = 0f
            startAnimationLoop()
            return
        }

        val relative = shortestDelta(deviceHeading, sunAzimuth)
        val frontFactor = cos(Math.toRadians(relative.toDouble())).toFloat()

        // ±90° occupe l'écran ; derrière le téléphone on reste discrètement collé à un bord.
        targetX = when {
            relative <= -90f -> .06f
            relative >= 90f -> .94f
            else -> .5f + (relative / 180f) * .88f
        }

        // Soleil haut = plus haut sur l'écran. On garde des marges pour rester élégant.
        val altitude01 = ((sunAltitude + 4f) / 94f).coerceIn(0f, 1f)
        targetY = .82f - altitude01 * .68f

        val daylight = ((sunAltitude + 4f) / 18f).coerceIn(0f, 1f)
        val facing = ((frontFactor + 1f) * .5f).coerceIn(.18f, 1f)
        targetAlpha = (.18f + .34f * daylight * facing).coerceIn(.12f, .52f)
        startAnimationLoop()
    }

    fun hideSun() {
        targetAlpha = 0f
        startAnimationLoop()
    }

    private fun startAnimationLoop() {
        if (animating) return
        animating = true
        postOnAnimation(step)
    }

    private val step = object : Runnable {
        override fun run() {
            currentX += (targetX - currentX) * .055f
            currentY += (targetY - currentY) * .055f
            currentAlpha += (targetAlpha - currentAlpha) * .07f
            invalidate()

            val settled = abs(targetX-currentX) < .001f && abs(targetY-currentY) < .001f && abs(targetAlpha-currentAlpha) < .005f
            if (settled) {
                currentX = targetX; currentY = targetY; currentAlpha = targetAlpha
                animating = false
                invalidate()
            } else postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentAlpha <= .01f || width <= 0 || height <= 0) return

        val cx = width * currentX
        val cy = height * currentY
        val base = min(width, height).toFloat()
        val glowR = max(base * .075f, 34f)
        val coreR = glowR * .28f

        val a = (currentAlpha * 255).toInt().coerceIn(0,255)
        paint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(
                Color.argb((a * .95f).toInt(), 255, 239, 170),
                Color.argb((a * .50f).toInt(), 255, 193, 74),
                Color.argb((a * .14f).toInt(), 255, 156, 24),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .24f, .58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, paint)
        paint.shader = null

        paint.color = Color.argb((a * .72f).toInt(), 255, 226, 132)
        canvas.drawCircle(cx, cy, coreR, paint)
    }

    private fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
}
