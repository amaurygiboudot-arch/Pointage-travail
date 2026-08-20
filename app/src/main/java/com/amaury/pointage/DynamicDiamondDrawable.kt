package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Bouton rectangulaire HP Travail : fond céleste + cadre bleu/or.
 * Le cadre reste fixe ; à l'appui, seul le fond intérieur s'enfonce légèrement.
 */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val accent: Int = Color.parseColor("#D6A84B"),
    private val accentLight: Int = Color.parseColor("#F3D58A")
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var pressed = false
    private var lightAngle = -55f
    private var globalAlpha = 255

    fun setLightAngle(degrees: Float) {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.8f) return
        lightAngle = normalized
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = state.any { it == android.R.attr.state_pressed }
        if (nowPressed == pressed) return false
        pressed = nowPressed
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        val h = rect.height()
        val radius = min(h * 0.48f, 28f * density)

        // 1) FOND : la partie qui réagit au toucher.
        val inner = RectF(rect).apply {
            inset(maxOf(5.2f * density, h * 0.09f), maxOf(4.0f * density, h * 0.075f))
        }
        val innerSave = canvas.save()
        if (pressed) canvas.scale(0.965f, 0.88f, inner.centerX(), inner.centerY())
        drawCelestialFill(canvas, inner, (radius * 0.78f).coerceAtLeast(8f * density))
        canvas.restoreToCount(innerSave)

        // 2) CADRE : toujours fixe, au-dessus du fond.
        drawCelestialFrame(canvas, rect, radius)
    }

    private fun drawCelestialFill(canvas: Canvas, r: RectF, radius: Float) {
        val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clip)

        // Fond nuit profond, proche du fond généré ensemble.
        fillPaint.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(
                withAlpha(Color.rgb(2, 10, 31), 245),
                withAlpha(Color.rgb(3, 28, 72), 238),
                withAlpha(Color.rgb(4, 18, 52), 242),
                withAlpha(Color.rgb(8, 32, 83), 235)
            ),
            floatArrayOf(0f, .34f, .70f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        fillPaint.shader = null

        // Lueur bleue mobile, très douce, issue de l'éclairage dynamique.
        val rad = Math.toRadians(lightAngle.toDouble())
        val lx = r.centerX() + (cos(rad) * r.width() * .30).toFloat()
        val ly = r.centerY() + (sin(rad) * r.height() * .34).toFloat()
        fillPaint.shader = RadialGradient(
            lx, ly, r.width() * .30f,
            intArrayOf(
                withAlpha(Color.rgb(72, 154, 255), if (pressed) 65 else 105),
                withAlpha(Color.rgb(22, 74, 164), if (pressed) 35 else 58),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, fillPaint)
        fillPaint.shader = null

        // Voie lactée discrète en diagonale.
        fillPaint.shader = LinearGradient(
            r.left + r.width() * .18f, r.bottom,
            r.right - r.width() * .12f, r.top,
            intArrayOf(Color.TRANSPARENT, withAlpha(Color.rgb(65, 130, 220), 34), withAlpha(Color.WHITE, 28), Color.TRANSPARENT),
            floatArrayOf(0f, .34f, .58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, fillPaint)
        fillPaint.shader = null

        drawStars(canvas, r)

        // Reflet verre supérieur, très fin.
        fillPaint.shader = LinearGradient(
            r.left, r.top, r.left, r.top + r.height() * .55f,
            intArrayOf(withAlpha(Color.WHITE, pressedAlpha(50)), withAlpha(Color.rgb(120, 190, 255), pressedAlpha(18)), Color.TRANSPARENT),
            floatArrayOf(0f, .25f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        fillPaint.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawStars(canvas: Canvas, r: RectF) {
        val points = arrayOf(
            .09f to .34f, .17f to .67f, .25f to .29f, .34f to .55f,
            .43f to .24f, .52f to .66f, .61f to .38f, .70f to .20f,
            .79f to .61f, .87f to .31f, .93f to .70f, .57f to .18f,
            .74f to .47f, .29f to .77f, .47f to .43f, .83f to .80f
        )
        points.forEachIndexed { index, p ->
            val x = r.left + r.width() * p.first
            val y = r.top + r.height() * p.second
            val bright = index % 5 == 0
            starPaint.color = withAlpha(if (index % 3 == 0) Color.rgb(255, 211, 105) else Color.rgb(190, 225, 255), if (bright) pressedAlpha(210) else pressedAlpha(120))
            starPaint.strokeWidth = if (bright) 1.15f * density else .65f * density
            if (bright) {
                val s = min(4.1f * density, r.height() * .085f)
                canvas.drawLine(x - s, y, x + s, y, starPaint)
                canvas.drawLine(x, y - s, x, y + s, starPaint)
            } else {
                canvas.drawCircle(x, y, .75f * density, starPaint)
            }
        }
    }

    private fun drawCelestialFrame(canvas: Canvas, r: RectF, radius: Float) {
        // Trait bleu extérieur.
        val outer = RectF(r).apply { inset(1.0f * density, 1.0f * density) }
        framePaint.strokeWidth = maxOf(2.2f * density, r.height() * .055f)
        framePaint.shader = LinearGradient(
            outer.left, outer.top, outer.right, outer.bottom,
            intArrayOf(
                withAlpha(Color.rgb(3, 31, 89), globalAlpha),
                withAlpha(Color.rgb(22, 121, 255), globalAlpha),
                withAlpha(Color.rgb(1, 22, 67), globalAlpha),
                withAlpha(Color.rgb(15, 103, 224), globalAlpha)
            ), null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(outer, radius, radius, framePaint)
        framePaint.shader = null

        // Couronne bleue vitrée plus épaisse.
        val blue = RectF(r).apply { inset(3.2f * density, 3.2f * density) }
        framePaint.strokeWidth = maxOf(4.0f * density, r.height() * .085f)
        framePaint.shader = LinearGradient(
            blue.left, blue.top, blue.right, blue.bottom,
            intArrayOf(
                withAlpha(Color.rgb(5, 55, 145), globalAlpha),
                withAlpha(Color.rgb(25, 139, 255), globalAlpha),
                withAlpha(Color.rgb(2, 29, 85), globalAlpha),
                withAlpha(Color.rgb(5, 86, 201), globalAlpha)
            ), floatArrayOf(0f, .24f, .66f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(blue, radius * .92f, radius * .92f, framePaint)
        framePaint.shader = null

        // Liseré or intérieur, comme le cadre PNG retrouvé.
        val gold = RectF(r).apply { inset(maxOf(5.1f * density, r.height() * .10f), maxOf(4.1f * density, r.height() * .082f)) }
        framePaint.strokeWidth = maxOf(1.45f * density, r.height() * .032f)
        framePaint.shader = LinearGradient(
            gold.left, gold.top, gold.right, gold.bottom,
            intArrayOf(
                withAlpha(Color.rgb(126, 69, 4), globalAlpha),
                withAlpha(Color.rgb(255, 222, 109), globalAlpha),
                withAlpha(Color.rgb(181, 106, 9), globalAlpha),
                withAlpha(Color.rgb(255, 239, 157), globalAlpha),
                withAlpha(Color.rgb(134, 72, 4), globalAlpha)
            ), null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(gold, radius * .80f, radius * .80f, framePaint)
        framePaint.shader = null

        // Fins reflets bleu-blanc sur les bords.
        val highlight = RectF(r).apply { inset(2.0f * density, 2.0f * density) }
        framePaint.strokeWidth = .75f * density
        framePaint.color = withAlpha(Color.rgb(167, 219, 255), (globalAlpha * .78f).toInt())
        canvas.drawRoundRect(highlight, radius * .96f, radius * .96f, framePaint)

        // Quatre éclats localisés, fixes avec le cadre.
        starPaint.strokeWidth = 1.0f * density
        starPaint.color = withAlpha(Color.rgb(255, 226, 139), globalAlpha)
        sparkle(canvas, r.left + r.width() * .24f, r.top + 4.3f * density, 3.7f * density)
        sparkle(canvas, r.right - 5.0f * density, r.top + r.height() * .31f, 4.2f * density)
        sparkle(canvas, r.left + r.width() * .70f, r.bottom - 4.0f * density, 3.5f * density)
        starPaint.color = withAlpha(Color.rgb(166, 222, 255), globalAlpha)
        sparkle(canvas, r.left + 5.0f * density, r.bottom - r.height() * .28f, 3.4f * density)
    }

    private fun sparkle(canvas: Canvas, x: Float, y: Float, s: Float) {
        canvas.drawLine(x - s, y, x + s, y, starPaint)
        canvas.drawLine(x, y - s, x, y + s, starPaint)
    }

    private fun pressedAlpha(value: Int): Int = ((if (pressed) value * .72f else value.toFloat()) * globalAlpha / 255f).toInt().coerceIn(0, 255)
    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    override fun setAlpha(alpha: Int) { globalAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter; framePaint.colorFilter = colorFilter; starPaint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
