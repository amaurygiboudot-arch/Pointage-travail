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
 * Cristal diamant procédural : aucune image ni éclat figé.
 * Les facettes gardent leur géométrie, mais leur luminosité dépend de lightAngle.
 */
class DiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val tint: Int = Color.parseColor("#B9E6FF")
) : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(angle: Float) {
        val normalized = ((angle % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.5f) return
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
        val cut = 16f * density
        val path = crystalPath(rect, cut)
        val save = canvas.save()
        canvas.clipPath(path)

        drawCrystalBody(canvas)
        drawFacets(canvas)
        drawMovingLight(canvas)
        canvas.restoreToCount(save)

        edgePaint.strokeWidth = 1.6f * density
        edgePaint.color = if (dark) Color.argb(235, 206, 239, 255) else Color.argb(245, 124, 194, 232)
        canvas.drawPath(path, edgePaint)
        edgePaint.strokeWidth = 0.7f * density
        edgePaint.color = Color.argb(if (pressed) 90 else 170, 255, 255, 255)
        val inset = 3f * density
        val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.drawPath(crystalPath(inner, (cut - inset).coerceAtLeast(4f * density)), edgePaint)
    }

    private fun drawCrystalBody(canvas: Canvas) {
        val deep = if (dark) Color.argb(210, 8, 24, 42) else Color.argb(145, 212, 237, 250)
        val mid = if (dark) Color.argb(175, 28, 63, 94) else Color.argb(120, 235, 248, 255)
        val clear = if (dark) Color.argb(105, 137, 204, 239) else Color.argb(95, 255, 255, 255)
        basePaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(deep, clear, mid, deep),
            floatArrayOf(0f, .28f, .63f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, basePaint)
        basePaint.shader = null
    }

    private fun drawFacets(canvas: Canvas) {
        val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom
        val w = rect.width(); val h = rect.height(); val cx = rect.centerX(); val cy = rect.centerY()
        val facets = listOf(
            facet(Path().apply { moveTo(l,t); lineTo(l+w*.22f,t); lineTo(l+w*.31f,cy); lineTo(l,b); close() }, 205f),
            facet(Path().apply { moveTo(l+w*.22f,t); lineTo(cx,t+h*.10f); lineTo(l+w*.31f,cy); close() }, 260f),
            facet(Path().apply { moveTo(cx,t+h*.10f); lineTo(r-w*.18f,t); lineTo(r-w*.28f,cy); lineTo(l+w*.31f,cy); close() }, 315f),
            facet(Path().apply { moveTo(r-w*.18f,t); lineTo(r,t); lineTo(r,b); lineTo(r-w*.28f,cy); close() }, 350f),
            facet(Path().apply { moveTo(l,b); lineTo(l+w*.31f,cy); lineTo(cx,b-h*.08f); lineTo(l+w*.20f,b); close() }, 145f),
            facet(Path().apply { moveTo(l+w*.31f,cy); lineTo(r-w*.28f,cy); lineTo(cx,b-h*.08f); close() }, 80f),
            facet(Path().apply { moveTo(r-w*.28f,cy); lineTo(r,b); lineTo(r-w*.20f,b); lineTo(cx,b-h*.08f); close() }, 25f)
        )
        facets.forEach { (path, normal) ->
            val illumination = ((cos(Math.toRadians(shortestDelta(normal, lightAngle).toDouble())) + 1.0) / 2.0).toFloat()
            val alpha = ((if (dark) 28 else 20) + illumination * (if (pressed) 70 else 125)).toInt().coerceIn(15, 165)
            val color = if (illumination > .48f) mix(Color.WHITE, tint, .22f) else mix(Color.parseColor("#183249"), tint, .18f)
            facetPaint.color = withAlpha(color, alpha)
            canvas.drawPath(path, facetPaint)
        }
    }

    private fun facet(path: Path, normal: Float) = path to normal

    private fun drawMovingLight(canvas: Canvas) {
        val diagonal = sqrt(rect.width()*rect.width() + rect.height()*rect.height())
        val rad = Math.toRadians(lightAngle.toDouble())
        val lx = rect.centerX() + (cos(rad) * rect.width() * .34f).toFloat()
        val ly = rect.centerY() + (sin(rad) * rect.height() * .34f).toFloat()

        sparklePaint.shader = RadialGradient(
            lx, ly, diagonal * .24f,
            intArrayOf(Color.argb(if (pressed) 100 else 210,255,255,255), Color.argb(80,150,218,255), Color.TRANSPARENT),
            floatArrayOf(0f,.20f,1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, sparklePaint)
        sparklePaint.shader = null

        val perpX = (-sin(rad) * rect.width() * .13f).toFloat()
        val perpY = (cos(rad) * rect.height() * .80f).toFloat()
        sparklePaint.shader = LinearGradient(
            lx-perpX, ly-perpY, lx+perpX, ly+perpY,
            intArrayOf(Color.TRANSPARENT, Color.argb(if (pressed) 85 else 175,255,255,255), Color.TRANSPARENT),
            floatArrayOf(0f,.5f,1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, sparklePaint)
        sparklePaint.shader = null

        sparklePaint.color = Color.argb(if (pressed) 120 else 235,255,255,255)
        sparklePaint.strokeWidth = 1.1f * density
        val ray = 5.5f * density
        canvas.drawLine(lx-ray, ly, lx+ray, ly, sparklePaint)
        canvas.drawLine(lx, ly-ray, lx, ly+ray, sparklePaint)
    }

    private fun crystalPath(r: RectF, cut: Float) = Path().apply {
        moveTo(r.left + cut, r.top)
        lineTo(r.right - cut, r.top)
        lineTo(r.right, r.top + cut)
        lineTo(r.right, r.bottom - cut)
        lineTo(r.right - cut, r.bottom)
        lineTo(r.left + cut, r.bottom)
        lineTo(r.left, r.bottom - cut)
        lineTo(r.left, r.top + cut)
        close()
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f,1f)
        return Color.rgb(
            (Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),
            (Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),
            (Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0,255), Color.red(color), Color.green(color), Color.blue(color))
    private fun shortestDelta(a: Float, b: Float): Float = ((b-a+540f)%360f)-180f

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha; facetPaint.alpha = alpha; edgePaint.alpha = alpha; sparklePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        basePaint.colorFilter = colorFilter; facetPaint.colorFilter = colorFilter; edgePaint.colorFilter = colorFilter; sparklePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
