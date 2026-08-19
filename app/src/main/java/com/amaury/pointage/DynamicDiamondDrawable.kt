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
 * Fond matériel pour les boutons HP Travail.
 * - Alu brossé : métal satiné avec micro-stries horizontales.
 * - Carbone : tressage 2x2 discret, sans grandes formes géométriques.
 * - Autres thèmes : dégradé sobre guidé par la lumière.
 */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val accent: Int = Color.parseColor("#D6A84B"),
    private val accentLight: Int = Color.parseColor("#F3D58A")
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    private val aluminumAccent = Color.parseColor("#7C858C")
    private val carbonAccent = Color.parseColor("#596166")

    fun setLightAngle(degrees: Float) {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.8f) return
        lightAngle = normalized
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val value = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
        if (value == pressed) return false
        pressed = value
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        val radius = 18f * density
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clip)

        when (accent) {
            aluminumAccent -> drawBrushedAluminum(canvas)
            carbonAccent -> drawCarbon(canvas)
            else -> drawElegantSurface(canvas)
        }

        canvas.restoreToCount(save)
        strokePaint.strokeWidth = 1.6f * density
        strokePaint.color = if (pressed) mix(accent, Color.BLACK, 0.22f) else accent
        val inset = strokePaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        rect.inset(-inset, -inset)
    }

    private fun drawBrushedAluminum(canvas: Canvas) {
        val top = if (dark) Color.parseColor("#25292C") else Color.parseColor("#EEF0F1")
        val middle = if (dark) Color.parseColor("#3A3F43") else Color.parseColor("#C9CDD0")
        val bottom = if (dark) Color.parseColor("#1E2124") else Color.parseColor("#E0E3E5")
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            intArrayOf(top, middle, bottom), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        val step = maxOf(1f, 1.35f * density)
        var y = rect.top
        var i = 0
        while (y <= rect.bottom) {
            val alpha = when (i % 5) { 0 -> 34; 1 -> 15; 2 -> 25; 3 -> 9; else -> 20 }
            detailPaint.color = if (dark) Color.argb(alpha, 230, 235, 238) else Color.argb(alpha, 45, 50, 54)
            detailPaint.strokeWidth = if (i % 7 == 0) 0.8f * density else 0.45f * density
            canvas.drawLine(rect.left, y, rect.right, y, detailPaint)
            y += step
            i++
        }

        val rad = Math.toRadians(lightAngle.toDouble())
        val band = rect.width() * 0.28f
        val cx = rect.centerX()
        val cy = rect.centerY()
        val px = (-sin(rad) * band).toFloat()
        val py = (cos(rad) * band).toFloat()
        paint.shader = LinearGradient(
            cx - px, cy - py, cx + px, cy + py,
            intArrayOf(Color.TRANSPARENT, Color.argb(if (dark) 45 else 75, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawCarbon(canvas: Canvas) {
        val base = if (dark) Color.parseColor("#090A0A") else Color.parseColor("#B9BCBD")
        val base2 = if (dark) Color.parseColor("#151717") else Color.parseColor("#D3D5D5")
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, base, base2, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = null

        val cell = maxOf(5f * density, 8f)
        val strip = cell * 0.48f
        var y = rect.top - cell
        var row = 0
        while (y < rect.bottom + cell) {
            var x = rect.left - cell
            var col = 0
            while (x < rect.right + cell) {
                val forward = (row + col) % 2 == 0
                val path = Path()
                if (forward) {
                    path.moveTo(x, y + strip)
                    path.lineTo(x + strip, y)
                    path.lineTo(x + cell, y + cell - strip)
                    path.lineTo(x + cell - strip, y + cell)
                } else {
                    path.moveTo(x, y)
                    path.lineTo(x + strip, y + strip)
                    path.lineTo(x + cell - strip, y + cell)
                    path.lineTo(x + cell, y + cell - strip)
                }
                path.close()
                val hi = if (dark) 38 else 28
                val lo = if (dark) 105 else 70
                detailPaint.color = if (forward) Color.argb(hi, 210, 215, 215) else Color.argb(lo, 25, 27, 27)
                canvas.drawPath(path, detailPaint)
                x += cell
                col++
            }
            y += cell
            row++
        }

        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Color.argb(if (dark) 24 else 40, 255, 255, 255), Color.TRANSPARENT, Color.argb(if (dark) 45 else 30, 0, 0, 0)),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawElegantSurface(canvas: Canvas) {
        val base = if (dark) Color.parseColor("#111111") else Color.parseColor("#F2F0EA")
        val mid = mix(base, accent, if (dark) 0.24f else 0.10f)
        val light = mix(base, accentLight, if (dark) 0.32f else 0.16f)
        val diagonal = sqrt(rect.width() * rect.width() + rect.height() * rect.height())
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad) * diagonal).toFloat()
        val dy = (sin(rad) * diagonal).toFloat()
        val cx = rect.centerX()
        val cy = rect.centerY()
        paint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            intArrayOf(if (pressed) mix(base, Color.BLACK, .12f) else base, light, mid),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        detailPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        detailPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
