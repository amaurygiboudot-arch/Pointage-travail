package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Fond diamant pseudo-aléatoire, éclairage naturel et palette dépendante du thème. */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val accent: Int = Color.parseColor("#D6A84B"),
    private val accentLight: Int = Color.parseColor("#F3D58A")
) : Drawable() {
    companion object { private val nextSeed = AtomicInteger(1) }

    private val seed = nextSeed.getAndIncrement()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    private fun variant(index: Int, min: Float, max: Float): Float {
        var x = seed * 1103515245 + 12345 + index * 1013904223
        x = x xor (x ushr 16)
        val unit = (x and 0x7fffffff) / 2147483647f
        return min + (max - min) * unit
    }

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
        val radius = variant(1, 11f, 20f) * density
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)

        val cx = rect.left + rect.width() * variant(2, 0.42f, 0.58f)
        val cy = rect.top + rect.height() * variant(3, 0.40f, 0.60f)
        val diagonal = sqrt(rect.width() * rect.width() + rect.height() * rect.height()) * 0.78f
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad) * diagonal).toFloat()
        val dy = (sin(rad) * diagonal).toFloat()

        val baseDark = if (dark) Color.parseColor("#070707") else Color.parseColor("#B8AA94")
        val baseMid = if (dark) mix(Color.parseColor("#151515"), accent, 0.32f) else mix(Color.WHITE, accent, 0.12f)
        val baseLight = if (dark) mix(Color.parseColor("#292929"), accentLight, 0.35f) else mix(Color.WHITE, accentLight, 0.25f)
        val deep = if (dark) Color.BLACK else mix(Color.parseColor("#8E7B60"), accent, 0.16f)

        val colors = if (pressed) {
            intArrayOf(mix(baseDark, Color.BLACK, 0.18f), mix(baseLight, baseMid, 0.35f), baseMid, deep)
        } else {
            intArrayOf(baseDark, baseLight, baseMid, deep)
        }

        fillPaint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            colors, floatArrayOf(0f, .28f, .62f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val leftMidX = rect.left + rect.width() * variant(4, .18f, .38f)
        val rightMidX = rect.right - rect.width() * variant(5, .16f, .36f)
        val topX = rect.left + rect.width() * variant(6, .35f, .65f)
        val bottomX = rect.left + rect.width() * variant(7, .35f, .65f)
        val splitY = rect.top + rect.height() * variant(8, .38f, .62f)

        val facets = listOf(
            Path().apply { moveTo(rect.left, rect.top); lineTo(topX, rect.top); lineTo(leftMidX, splitY); close() } to variant(9, 195f, 250f),
            Path().apply { moveTo(topX, rect.top); lineTo(rect.right, rect.top); lineTo(rightMidX, splitY); close() } to variant(10, 285f, 345f),
            Path().apply { moveTo(rect.right, rect.bottom); lineTo(rightMidX, splitY); lineTo(bottomX, rect.bottom); close() } to variant(11, 20f, 75f),
            Path().apply { moveTo(rect.left, rect.bottom); lineTo(leftMidX, splitY); lineTo(bottomX, rect.bottom); close() } to variant(12, 105f, 165f),
            Path().apply { moveTo(leftMidX, splitY); lineTo(topX, rect.top); lineTo(rightMidX, splitY); lineTo(bottomX, rect.bottom); close() } to variant(13, 245f, 295f)
        )
        facets.forEachIndexed { i, (path, normal) -> drawFacet(canvas, path, normal, i == 4) }

        if (seed % 2 == 0) {
            drawFacet(
                canvas,
                Path().apply { moveTo(rect.left, rect.top); lineTo(leftMidX, splitY); lineTo(rect.left, rect.bottom); close() },
                variant(14, 150f, 220f)
            )
        }
        if (seed % 3 == 0) {
            drawFacet(
                canvas,
                Path().apply { moveTo(rect.right, rect.top); lineTo(rightMidX, splitY); lineTo(rect.right, rect.bottom); close() },
                variant(15, 320f, 380f)
            )
        }

        val beamWidth = rect.width() * variant(16, .10f, .18f)
        val perpX = (-sin(rad) * beamWidth).toFloat()
        val perpY = (cos(rad) * beamWidth).toFloat()
        val beamAlpha = if (pressed) 120 else if (dark) 185 else 210
        val beamColor = withAlpha(if (dark) accentLight else Color.WHITE, beamAlpha)
        shinePaint.shader = LinearGradient(
            cx - perpX, cy - perpY, cx + perpX, cy + perpY,
            intArrayOf(Color.TRANSPARENT, beamColor, Color.TRANSPARENT),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, shinePaint)
        shinePaint.shader = null
        canvas.restoreToCount(save)

        strokePaint.strokeWidth = 1.7f * density
        strokePaint.color = if (pressed) mix(accent, Color.BLACK, 0.25f) else accent
        val half = strokePaint.strokeWidth / 2f
        rect.inset(half, half)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        rect.inset(-half, -half)
    }

    private fun drawFacet(canvas: Canvas, path: Path, normalAngle: Float, centreFacet: Boolean = false) {
        val direct = ((illumination(normalAngle) + 1f) / 2f).coerceIn(0f, 1f)
        val ambient = if (dark) 0.18f else 0.24f
        val bounce = ((illumination(normalAngle + 180f) + 1f) / 2f) * 0.16f
        val total = (ambient + direct * 0.72f + bounce).coerceIn(0f, 1f)
        val centred = if (centreFacet) 0.90f else 1f
        facetPaint.style = Paint.Style.FILL

        if (direct >= 0.45f) {
            val alpha = ((45 + 165 * total * centred) * if (pressed) 0.72f else 1f).toInt().coerceIn(35, 225)
            facetPaint.color = withAlpha(if (dark) accentLight else Color.WHITE, alpha)
        } else {
            val shadowStrength = (1f - total).coerceIn(0f, 1f)
            val alpha = ((28 + 115 * shadowStrength) * centred).toInt().coerceIn(20, 165)
            facetPaint.color = if (dark) withAlpha(mix(Color.BLACK, accent, 0.12f), alpha)
            else withAlpha(mix(Color.parseColor("#5A4630"), accent, 0.12f), alpha)
        }
        canvas.drawPath(path, facetPaint)
    }

    private fun illumination(normalAngle: Float): Float =
        cos(Math.toRadians(shortestDelta(normalAngle, lightAngle).toDouble())).toFloat().coerceIn(-1f, 1f)

    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

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
