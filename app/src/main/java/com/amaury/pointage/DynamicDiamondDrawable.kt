package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fond facetté dont chaque facette réagit différemment à la direction de lumière.
 * Une facette tournée vers la source devient franchement lumineuse ; la facette
 * opposée s'assombrit nettement afin que le mouvement solaire soit perceptible.
 */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(degrees: Float) {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.8f) return
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

        val radius = 15f * density
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)

        val cx = rect.centerX()
        val cy = rect.centerY()
        val diagonal = sqrt(rect.width() * rect.width() + rect.height() * rect.height()) * 0.78f
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad) * diagonal).toFloat()
        val dy = (sin(rad) * diagonal).toFloat()

        // Base volontairement plus contrastée pour donner de la profondeur.
        val colors = if (dark) {
            if (pressed) intArrayOf(
                Color.parseColor("#030303"), Color.parseColor("#463815"), Color.parseColor("#17130C"), Color.parseColor("#000000")
            ) else intArrayOf(
                Color.parseColor("#020202"), Color.parseColor("#725B22"), Color.parseColor("#20190D"), Color.parseColor("#000000")
            )
        } else {
            if (pressed) intArrayOf(
                Color.parseColor("#9D8969"), Color.parseColor("#FFFFFF"), Color.parseColor("#EEE2CF"), Color.parseColor("#897454")
            ) else intArrayOf(
                Color.parseColor("#8D7652"), Color.parseColor("#FFFFFF"), Color.parseColor("#FFF9EE"), Color.parseColor("#7C6645")
            )
        }

        fillPaint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            colors,
            floatArrayOf(0f, 0.28f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val topLeft = Path().apply {
            moveTo(rect.left, rect.top)
            lineTo(cx, rect.top)
            lineTo(rect.left + rect.width() * 0.30f, cy)
            close()
        }
        val topRight = Path().apply {
            moveTo(cx, rect.top)
            lineTo(rect.right, rect.top)
            lineTo(rect.right - rect.width() * 0.22f, cy)
            close()
        }
        val bottomLeft = Path().apply {
            moveTo(rect.left, rect.bottom)
            lineTo(rect.left + rect.width() * 0.30f, cy)
            lineTo(cx, rect.bottom)
            close()
        }
        val bottomRight = Path().apply {
            moveTo(rect.right, rect.bottom)
            lineTo(rect.right - rect.width() * 0.22f, cy)
            lineTo(cx, rect.bottom)
            close()
        }
        val centre = Path().apply {
            moveTo(rect.left + rect.width() * 0.30f, cy)
            lineTo(cx, rect.top)
            lineTo(rect.right - rect.width() * 0.22f, cy)
            lineTo(cx, rect.bottom)
            close()
        }

        // Chaque facette possède une normale différente : c'est ce qui crée
        // l'alternance claire/sombre quand la lumière tourne.
        drawFacet(canvas, topLeft, 225f)
        drawFacet(canvas, topRight, 315f)
        drawFacet(canvas, bottomRight, 45f)
        drawFacet(canvas, bottomLeft, 135f)
        drawFacet(canvas, centre, 270f, centreFacet = true)

        // Faisceau spéculaire étroit : il doit être immédiatement visible.
        val beamWidth = rect.width() * 0.15f
        val perpX = (-sin(rad) * beamWidth).toFloat()
        val perpY = (cos(rad) * beamWidth).toFloat()
        val beamAlpha = if (pressed) 130 else if (dark) 205 else 225
        val beamColor = if (dark) Color.argb(beamAlpha, 255, 224, 125)
        else Color.argb(beamAlpha, 255, 255, 255)
        shinePaint.shader = LinearGradient(
            cx - perpX, cy - perpY, cx + perpX, cy + perpY,
            intArrayOf(Color.TRANSPARENT, beamColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, shinePaint)
        shinePaint.shader = null

        canvas.restoreToCount(save)

        strokePaint.strokeWidth = 1.7f * density
        strokePaint.color = if (dark) {
            if (pressed) Color.parseColor("#9C7424") else Color.parseColor("#F0C35A")
        } else {
            if (pressed) Color.parseColor("#8A6117") else Color.parseColor("#D5A63A")
        }
        val half = strokePaint.strokeWidth / 2f
        rect.inset(half, half)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        rect.inset(-half, -half)
    }

    private fun drawFacet(canvas: Canvas, path: Path, normalAngle: Float, centreFacet: Boolean = false) {
        val light = illumination(normalAngle)
        val magnitude = kotlin.math.abs(light)

        facetPaint.style = Paint.Style.FILL
        if (light >= 0f) {
            // Face au soleil : montée très franche vers blanc/or.
            val alphaBase = if (centreFacet) 55 else 75
            val alphaRange = if (pressed) 105 else 165
            val alpha = (alphaBase + alphaRange * magnitude).toInt().coerceIn(0, 235)
            facetPaint.color = if (dark) {
                Color.argb(alpha, 255, 218, 115)
            } else {
                Color.argb(alpha, 255, 255, 255)
            }
        } else {
            // Dos au soleil : ombre nettement renforcée.
            val alphaBase = if (centreFacet) 55 else 70
            val alphaRange = if (pressed) 85 else 145
            val alpha = (alphaBase + alphaRange * magnitude).toInt().coerceIn(0, 225)
            facetPaint.color = if (dark) {
                Color.argb(alpha, 0, 0, 0)
            } else {
                Color.argb(alpha, 55, 36, 15)
            }
        }
        canvas.drawPath(path, facetPaint)
    }

    /** -1 = complètement dos à la lumière, +1 = complètement face. */
    private fun illumination(normalAngle: Float): Float {
        val delta = Math.toRadians(shortestDelta(normalAngle, lightAngle).toDouble())
        return cos(delta).toFloat().coerceIn(-1f, 1f)
    }

    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        facetPaint.alpha = alpha
        shinePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        facetPaint.colorFilter = colorFilter
        shinePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
