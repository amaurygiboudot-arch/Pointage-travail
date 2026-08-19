package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diamant / cristal transparent sans image fixe.
 * Le fond reste visible à travers le bouton et les reflets dépendent de lightAngle.
 */
class DiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val tint: Int = Color.parseColor("#CDEFFF")
) : Drawable() {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(angle: Float) {
        val normalized = ((angle % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.45f) return
        lightAngle = normalized
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val newPressed = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
        if (newPressed == pressed) return false
        pressed = newPressed
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return

        rect.set(
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat()
        )

        val radius = 20f * density
        val outline = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        val save = canvas.save()
        canvas.clipPath(outline)

        drawTransparentBody(canvas)
        drawMicroFacets(canvas)
        drawRefractionBands(canvas)
        drawIndependentHighlights(canvas)

        canvas.restoreToCount(save)
        drawEdges(canvas, outline, radius)
    }

    private fun drawTransparentBody(canvas: Canvas) {
        val cold = if (dark) Color.argb(42, 105, 175, 215) else Color.argb(30, 190, 228, 248)
        val clear = if (dark) Color.argb(16, 255, 255, 255) else Color.argb(12, 255, 255, 255)
        val depth = if (dark) Color.argb(48, 10, 28, 42) else Color.argb(22, 120, 170, 198)

        bodyPaint.shader = LinearGradient(
            rect.left, rect.top,
            rect.right, rect.bottom,
            intArrayOf(cold, clear, depth, clear, cold),
            floatArrayOf(0f, .22f, .50f, .76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, bodyPaint)
        bodyPaint.shader = null
    }

    private fun drawMicroFacets(canvas: Canvas) {
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val w = rect.width()
        val h = rect.height()
        val cx = rect.centerX()
        val cy = rect.centerY()

        val xs = floatArrayOf(l, l + w * .12f, l + w * .28f, cx, l + w * .72f, l + w * .88f, r)
        val ys = floatArrayOf(t, t + h * .18f, cy, t + h * .82f, b)

        val facets = ArrayList<Pair<Path, Float>>()
        for (row in 0 until ys.lastIndex) {
            for (col in 0 until xs.lastIndex) {
                val x0 = xs[col]
                val x1 = xs[col + 1]
                val y0 = ys[row]
                val y1 = ys[row + 1]
                val midX = (x0 + x1) * .5f
                val midY = (y0 + y1) * .5f

                val p1 = Path().apply {
                    moveTo(x0, y0)
                    lineTo(x1, y0)
                    lineTo(midX, midY)
                    close()
                }
                val p2 = Path().apply {
                    moveTo(x1, y0)
                    lineTo(x1, y1)
                    lineTo(midX, midY)
                    close()
                }
                val p3 = Path().apply {
                    moveTo(x1, y1)
                    lineTo(x0, y1)
                    lineTo(midX, midY)
                    close()
                }
                val p4 = Path().apply {
                    moveTo(x0, y1)
                    lineTo(x0, y0)
                    lineTo(midX, midY)
                    close()
                }

                val baseNormal = ((col * 47 + row * 31) % 360).toFloat()
                facets += p1 to baseNormal
                facets += p2 to ((baseNormal + 73f) % 360f)
                facets += p3 to ((baseNormal + 157f) % 360f)
                facets += p4 to ((baseNormal + 241f) % 360f)
            }
        }

        facets.forEachIndexed { index, (path, normal) ->
            val facing = ((cos(Math.toRadians(shortestDelta(normal, lightAngle).toDouble())) + 1.0) / 2.0).toFloat()
            val shimmer = ((sin(Math.toRadians((lightAngle * 1.7f + index * 19f).toDouble())) + 1.0) / 2.0).toFloat()
            val alpha = ((if (dark) 7 else 5) + facing * 28f + shimmer * 12f).toInt().coerceIn(4, 46)

            val facetColor = if (facing > .62f) {
                mix(Color.WHITE, tint, .14f)
            } else {
                mix(if (dark) Color.parseColor("#48697C") else Color.parseColor("#9FC9DE"), tint, .28f)
            }

            facetPaint.color = withAlpha(facetColor, if (pressed) (alpha * .72f).toInt() else alpha)
            canvas.drawPath(path, facetPaint)
        }
    }

    private fun drawRefractionBands(canvas: Canvas) {
        val diagonal = sqrt(rect.width() * rect.width() + rect.height() * rect.height())
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad) * diagonal).toFloat()
        val dy = (sin(rad) * diagonal).toFloat()
        val cx = rect.centerX()
        val cy = rect.centerY()

        lightPaint.shader = LinearGradient(
            cx - dx, cy - dy,
            cx + dx, cy + dy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(if (pressed) 18 else 34, 205, 240, 255),
                Color.argb(if (pressed) 38 else 72, 255, 255, 255),
                Color.argb(if (pressed) 12 else 26, 170, 225, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .34f, .50f, .66f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, lightPaint)
        lightPaint.shader = null
    }

    private fun drawIndependentHighlights(canvas: Canvas) {
        val rad = Math.toRadians(lightAngle.toDouble())
        val w = rect.width()
        val h = rect.height()

        val points = arrayOf(
            floatArrayOf(.18f, .27f, .090f, 0f),
            floatArrayOf(.42f, .16f, .060f, 67f),
            floatArrayOf(.68f, .31f, .075f, 131f),
            floatArrayOf(.82f, .64f, .055f, 203f),
            floatArrayOf(.53f, .76f, .080f, 279f),
            floatArrayOf(.27f, .68f, .050f, 337f)
        )

        points.forEachIndexed { i, p ->
            val phase = Math.toRadians((lightAngle * (1.05 + i * .08) + p[3]).toDouble())
            val px = rect.left + w * p[0] + (cos(phase) * w * .035).toFloat()
            val py = rect.top + h * p[1] + (sin(phase) * h * .055).toFloat()
            val radius = (w.coerceAtMost(h * 4f)) * p[2]

            val strength = ((cos(Math.toRadians((lightAngle - p[3]).toDouble())) + 1.0) / 2.0).toFloat()
            val centerAlpha = ((if (pressed) 70 else 130) + strength * (if (pressed) 70 else 115)).toInt().coerceIn(55, 245)

            lightPaint.shader = RadialGradient(
                px, py, radius,
                intArrayOf(
                    Color.argb(centerAlpha, 255, 255, 255),
                    Color.argb((centerAlpha * .34f).toInt(), 190, 232, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, .18f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, radius, lightPaint)
            lightPaint.shader = null

            if (strength > .72f) {
                lightPaint.color = Color.argb((centerAlpha * .9f).toInt(), 255, 255, 255)
                lightPaint.strokeWidth = .75f * density
                val ray = (3.0f + strength * 4.0f) * density
                canvas.drawLine(px - ray, py, px + ray, py, lightPaint)
                canvas.drawLine(px, py - ray, px, py + ray, lightPaint)
            }
        }

        val mainX = rect.centerX() + (cos(rad) * w * .28f).toFloat()
        val mainY = rect.centerY() + (sin(rad) * h * .28f).toFloat()
        lightPaint.shader = RadialGradient(
            mainX, mainY, h * .82f,
            intArrayOf(
                Color.argb(if (pressed) 34 else 64, 255, 255, 255),
                Color.argb(if (pressed) 13 else 28, 190, 232, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .30f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, lightPaint)
        lightPaint.shader = null
    }

    private fun drawEdges(canvas: Canvas, outline: Path, radius: Float) {
        edgePaint.strokeWidth = 1.15f * density
        edgePaint.color = Color.argb(if (dark) 115 else 100, 213, 243, 255)
        canvas.drawPath(outline, edgePaint)

        val inset = 2.2f * density
        val inner = RectF(
            rect.left + inset, rect.top + inset,
            rect.right - inset, rect.bottom - inset
        )
        edgePaint.strokeWidth = .55f * density
        edgePaint.color = Color.argb(if (pressed) 70 else 120, 255, 255, 255)
        canvas.drawRoundRect(inner, (radius - inset).coerceAtLeast(1f), (radius - inset).coerceAtLeast(1f), edgePaint)
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    override fun setAlpha(alpha: Int) {
        bodyPaint.alpha = alpha
        facetPaint.alpha = alpha
        edgePaint.alpha = alpha
        lightPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
        facetPaint.colorFilter = colorFilter
        edgePaint.colorFilter = colorFilter
        lightPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
