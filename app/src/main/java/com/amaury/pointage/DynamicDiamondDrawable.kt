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

/** Fond facetté dont les reflets suivent l'orientation du téléphone. */
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
        if (kotlin.math.abs(normalized - lightAngle) < 1.0f) return
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
        val diagonal = sqrt(rect.width() * rect.width() + rect.height() * rect.height()) * 0.72f
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad) * diagonal).toFloat()
        val dy = (sin(rad) * diagonal).toFloat()

        val colors = if (dark) {
            if (pressed) intArrayOf(
                Color.parseColor("#0A0A09"), Color.parseColor("#4A402B"), Color.parseColor("#201D17"), Color.parseColor("#050505")
            ) else intArrayOf(
                Color.parseColor("#080808"), Color.parseColor("#665738"), Color.parseColor("#29251C"), Color.parseColor("#050505")
            )
        } else {
            if (pressed) intArrayOf(
                Color.parseColor("#CBBDA6"), Color.parseColor("#FFFFFF"), Color.parseColor("#E7DDCC"), Color.parseColor("#BCAA8E")
            ) else intArrayOf(
                Color.parseColor("#C7B79B"), Color.parseColor("#FFFFFF"), Color.parseColor("#F7F0E4"), Color.parseColor("#B8A483")
            )
        }

        fillPaint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            colors,
            floatArrayOf(0f, 0.24f, 0.64f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Facettes triangulaires bien visibles, inspirées d'une pierre taillée.
        val hi = if (dark) Color.argb(if (pressed) 34 else 62, 255, 235, 180)
        else Color.argb(if (pressed) 38 else 70, 255, 255, 255)
        val shadow = if (dark) Color.argb(85, 0, 0, 0) else Color.argb(55, 98, 73, 38)

        facetPaint.style = Paint.Style.FILL
        facetPaint.color = hi
        val p1 = Path().apply {
            moveTo(rect.left, rect.top)
            lineTo(cx, rect.top)
            lineTo(rect.left + rect.width() * 0.30f, cy)
            close()
        }
        canvas.drawPath(p1, facetPaint)

        val p2 = Path().apply {
            moveTo(cx, rect.top)
            lineTo(rect.right, rect.top)
            lineTo(rect.right - rect.width() * 0.22f, cy)
            close()
        }
        facetPaint.color = Color.argb(if (dark) 28 else 42, 255, 250, 225)
        canvas.drawPath(p2, facetPaint)

        facetPaint.color = shadow
        val p3 = Path().apply {
            moveTo(rect.left, rect.bottom)
            lineTo(rect.left + rect.width() * 0.30f, cy)
            lineTo(cx, rect.bottom)
            close()
        }
        canvas.drawPath(p3, facetPaint)

        val p4 = Path().apply {
            moveTo(rect.right, rect.bottom)
            lineTo(rect.right - rect.width() * 0.22f, cy)
            lineTo(cx, rect.bottom)
            close()
        }
        canvas.drawPath(p4, facetPaint)

        // Faisceau lumineux mobile : beaucoup plus net que l'ancien dégradé seul.
        val beamWidth = rect.width() * 0.23f
        val perpX = (-sin(rad) * beamWidth).toFloat()
        val perpY = (cos(rad) * beamWidth).toFloat()
        val beamColors = intArrayOf(Color.TRANSPARENT, Color.argb(if (dark) 92 else 125, 255, 241, 193), Color.TRANSPARENT)
        shinePaint.shader = LinearGradient(
            cx - perpX, cy - perpY, cx + perpX, cy + perpY,
            beamColors, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, shinePaint)
        shinePaint.shader = null

        canvas.restoreToCount(save)

        strokePaint.strokeWidth = 1.5f * density
        strokePaint.color = if (dark) {
            if (pressed) Color.parseColor("#A47F34") else Color.parseColor("#E1B54F")
        } else {
            if (pressed) Color.parseColor("#9A711D") else Color.parseColor("#C99831")
        }
        val half = strokePaint.strokeWidth / 2f
        rect.inset(half, half)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        rect.inset(-half, -half)
    }

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
