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
 * Bouton rectangulaire HP Travail calé sur les deux assets approuvés :
 * - cadre céleste bleu/or fin, centre dégagé ;
 * - fond galaxie bleu nuit sous le cadre.
 *
 * Important : aucun réseau d'étoiles/croix n'est inventé. Les quelques reflets
 * présents correspondent uniquement aux points lumineux structurants du cadre de référence.
 */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val accent: Int = Color.parseColor("#D6A84B"),
    private val accentLight: Int = Color.parseColor("#F3D58A")
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
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
        val value = state.any { it == android.R.attr.state_pressed }
        if (value == pressed) return false
        pressed = value
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        val h = rect.height()
        val radius = min(h * 0.49f, 30f * density)

        // Fond seul mobile à l'appui : le cadre ne bouge jamais.
        val inner = RectF(rect).apply {
            inset(maxOf(5.4f * density, h * 0.10f), maxOf(4.3f * density, h * 0.085f))
        }
        val save = canvas.save()
        if (pressed) canvas.scale(0.973f, 0.91f, inner.centerX(), inner.centerY())
        drawReferenceFill(canvas, inner, radius * 0.79f)
        canvas.restoreToCount(save)

        drawReferenceFrame(canvas, rect, radius)
    }

    /** Fond galaxie propre : profondeur bleue, légère nébuleuse, sans motifs ajoutés. */
    private fun drawReferenceFill(canvas: Canvas, r: RectF, radius: Float) {
        val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clip)

        fillPaint.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(
                argb(245, 2, 8, 27),
                argb(242, 3, 24, 66),
                argb(246, 1, 12, 38),
                argb(240, 6, 29, 76)
            ),
            floatArrayOf(0f, .34f, .67f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        fillPaint.shader = null

        // Nébuleuse bleue très douce comme sur le fond approuvé.
        fillPaint.shader = LinearGradient(
            r.left + r.width() * .12f, r.bottom,
            r.right - r.width() * .10f, r.top,
            intArrayOf(
                Color.TRANSPARENT,
                argb(30, 30, 86, 160),
                argb(42, 53, 119, 205),
                argb(18, 130, 179, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .30f, .52f, .68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, fillPaint)
        fillPaint.shader = null

        // Éclairage dynamique : une zone de reflet seulement, pas une constellation.
        val rad = Math.toRadians(lightAngle.toDouble())
        val lx = r.centerX() + (cos(rad) * r.width() * .32f).toFloat()
        val ly = r.centerY() + (sin(rad) * r.height() * .32f).toFloat()
        fillPaint.shader = RadialGradient(
            lx, ly, r.width() * .24f,
            intArrayOf(
                argb(if (pressed) 45 else 78, 88, 162, 255),
                argb(if (pressed) 18 else 34, 22, 72, 170),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .34f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, fillPaint)
        fillPaint.shader = null

        // Reflet de verre supérieur continu, identique à l'esprit du visuel source.
        fillPaint.shader = LinearGradient(
            r.left, r.top, r.left, r.top + r.height() * .54f,
            intArrayOf(argb(if (pressed) 32 else 58, 235, 248, 255), argb(14, 92, 158, 240), Color.TRANSPARENT),
            floatArrayOf(0f, .28f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        fillPaint.shader = null

        canvas.restoreToCount(save)
    }

    /**
     * Cadre calé sur l'asset approuvé : large bande bleu nuit vitrée,
     * filet or intérieur très net et deux fins filets bleus. Pas de décor répétitif.
     */
    private fun drawReferenceFrame(canvas: Canvas, r: RectF, radius: Float) {
        // Halo externe très discret.
        val halo = RectF(r).apply { inset(1.1f * density, 1.1f * density) }
        strokePaint.strokeWidth = maxOf(1.15f * density, r.height() * .022f)
        strokePaint.color = argb((globalAlpha * .62f).toInt(), 35, 111, 220)
        canvas.drawRoundRect(halo, radius, radius, strokePaint)

        // Bande principale bleu nuit / bleu électrique.
        val blue = RectF(r).apply { inset(2.6f * density, 2.6f * density) }
        strokePaint.strokeWidth = maxOf(5.2f * density, r.height() * .103f)
        strokePaint.shader = LinearGradient(
            blue.left, blue.top, blue.right, blue.bottom,
            intArrayOf(
                argb(globalAlpha, 2, 21, 64),
                argb(globalAlpha, 7, 63, 145),
                argb(globalAlpha, 2, 17, 55),
                argb(globalAlpha, 8, 51, 127)
            ),
            floatArrayOf(0f, .28f, .64f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(blue, radius * .94f, radius * .94f, strokePaint)
        strokePaint.shader = null

        // Filet bleu clair externe fin.
        val cyan = RectF(r).apply { inset(2.0f * density, 2.0f * density) }
        strokePaint.strokeWidth = .78f * density
        strokePaint.color = argb((globalAlpha * .88f).toInt(), 95, 180, 255)
        canvas.drawRoundRect(cyan, radius * .97f, radius * .97f, strokePaint)

        // Filet or principal : plus fin et plus propre que la version précédente.
        val gold = RectF(r).apply {
            inset(maxOf(5.7f * density, r.height() * .108f), maxOf(4.7f * density, r.height() * .090f))
        }
        strokePaint.strokeWidth = maxOf(1.35f * density, r.height() * .027f)
        strokePaint.shader = LinearGradient(
            gold.left, gold.top, gold.right, gold.bottom,
            intArrayOf(
                argb(globalAlpha, 126, 70, 8),
                argb(globalAlpha, 255, 226, 127),
                argb(globalAlpha, 182, 108, 12),
                argb(globalAlpha, 255, 240, 166),
                argb(globalAlpha, 132, 72, 7)
            ),
            floatArrayOf(0f, .22f, .54f, .78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(gold, radius * .79f, radius * .79f, strokePaint)
        strokePaint.shader = null

        // Petit reflet blanc/bleu uniquement sur l'arc haut-gauche.
        val gloss = RectF(r).apply { inset(3.7f * density, 3.7f * density) }
        glowPaint.shader = RadialGradient(
            gloss.left + gloss.width() * .16f,
            gloss.top + gloss.height() * .10f,
            gloss.width() * .18f,
            intArrayOf(argb(86, 224, 246, 255), argb(24, 112, 190, 255), Color.TRANSPARENT),
            floatArrayOf(0f, .30f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeWidth = 1.1f * density
        canvas.drawRoundRect(gloss, radius * .91f, radius * .91f, glowPaint)
        glowPaint.shader = null
        glowPaint.style = Paint.Style.FILL

        // Deux éclats seulement, aux endroits caractéristiques du cadre source.
        drawFlare(canvas, r.left + r.width() * .73f, r.top + 3.4f * density, 2.7f * density, 255, 207, 92)
        drawFlare(canvas, r.right - 4.4f * density, r.top + r.height() * .30f, 3.1f * density, 255, 221, 127)
    }

    private fun drawFlare(canvas: Canvas, x: Float, y: Float, size: Float, red: Int, green: Int, blue: Int) {
        strokePaint.shader = null
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = .72f * density
        strokePaint.color = argb((globalAlpha * .78f).toInt(), red, green, blue)
        canvas.drawLine(x - size, y, x + size, y, strokePaint)
        canvas.drawLine(x, y - size, x, y + size, strokePaint)
        strokePaint.strokeCap = Paint.Cap.ROUND
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), red, green, blue)

    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
