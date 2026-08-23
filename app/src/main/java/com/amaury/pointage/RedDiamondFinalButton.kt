package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * DIAMANT ROUGE FINAL
 *
 * Rendu permanent du bouton SORTIE. Il ne dépend d'aucun thème.
 * Chaque facette est dessinée indépendamment avec sa propre teinte,
 * son propre niveau d'ombre et sa propre réaction à la lumière.
 */
class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : LightReactiveJewelButton(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant rouge final"
        private const val FACETS = 16
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = Path()

    private val reds = intArrayOf(
        Color.rgb(255, 50, 76),
        Color.rgb(214, 5, 35),
        Color.rgb(132, 0, 24),
        Color.rgb(255, 92, 118),
        Color.rgb(92, 0, 20),
        Color.rgb(238, 12, 48),
        Color.rgb(178, 0, 31),
        Color.rgb(255, 148, 164),
        Color.rgb(110, 0, 25),
        Color.rgb(245, 22, 56),
        Color.rgb(156, 0, 29),
        Color.rgb(255, 72, 102),
        Color.rgb(74, 0, 18),
        Color.rgb(226, 8, 42),
        Color.rgb(194, 0, 34),
        Color.rgb(255, 118, 140)
    )

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * .5f
        val cy = h * .5f
        val radius = min(w, h) * .465f
        val press = if (isPressed) .93f else 1f

        canvas.save()
        canvas.scale(press, press, cx, cy)

        outer.reset()
        outer.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(outer)

        drawGlassBase(canvas, cx, cy, radius)
        drawFacetField(canvas, cx, cy, radius)
        drawDirectionalRefraction(canvas, cx, cy, radius)
        drawFacetEdges(canvas, cx, cy, radius)
        drawSpecularGlints(canvas, cx, cy, radius)

        canvas.restore()
        drawOuterRim(canvas, cx, cy, radius * press)
    }

    private fun drawGlassBase(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.shader = RadialGradient(
            cx - r * .24f,
            cy - r * .30f,
            r * 1.25f,
            intArrayOf(
                Color.argb(205, 255, 72, 98),
                Color.argb(215, 185, 0, 36),
                Color.argb(225, 68, 0, 18),
                Color.argb(232, 20, 0, 8)
            ),
            floatArrayOf(0f, .36f, .74f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun drawFacetField(c: Canvas, cx: Float, cy: Float, r: Float) {
        val innerR = r * .30f
        val midR = r * .64f
        val outerR = r * .96f

        // Couronne centrale : 16 facettes triangulaires indépendantes.
        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            val p0 = point(cx, cy, innerR, a0)
            val p1 = point(cx, cy, innerR, a1)
            drawFacet(c, path(cx, cy, p0, p1), i, normal(a0, a1), 1.12f)
        }

        // Couronne moyenne : chaque secteur est scindé en 2 facettes.
        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            val am = (a0 + a1) * .5f
            val i0 = point(cx, cy, innerR, a0)
            val i1 = point(cx, cy, innerR, a1)
            val m0 = point(cx, cy, midR, a0)
            val m1 = point(cx, cy, midR, a1)
            val mm = point(cx, cy, midR, am)
            drawFacet(c, path(i0, m0, mm, i1), i + 3, normal(a0, am), .92f)
            drawFacet(c, path(i1, mm, m1), i + 9, normal(am, a1), .82f)
        }

        // Couronne extérieure : 32 facettes, plus sombres et plus contrastées.
        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            val am = (a0 + a1) * .5f
            val m0 = point(cx, cy, midR, a0)
            val m1 = point(cx, cy, midR, a1)
            val o0 = point(cx, cy, outerR, a0)
            val o1 = point(cx, cy, outerR, a1)
            val om = point(cx, cy, outerR, am)
            drawFacet(c, path(m0, o0, om, m1), i + 5, normal(a0, am), .72f)
            drawFacet(c, path(m1, om, o1), i + 12, normal(am, a1), .64f)
        }
    }

    private fun drawFacet(c: Canvas, p: Path, index: Int, normal: Float, energy: Float) {
        val facing = facing(normal)
        val base = reds[index % reds.size]
        val shadow = .36f + facing * .92f * energy
        val rr = (Color.red(base) * shadow).toInt().coerceIn(0, 255)
        val gg = (Color.green(base) * shadow).toInt().coerceIn(0, 255)
        val bb = (Color.blue(base) * shadow).toInt().coerceIn(0, 255)
        val alpha = (165 + facing * 78f).toInt().coerceIn(145, 245)

        fill.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Color.argb(alpha, rr, gg, bb),
            Color.argb((alpha * .72f).toInt(), (rr * .48f).toInt(), 0, (bb * .46f).toInt()),
            Shader.TileMode.CLAMP
        )
        c.drawPath(p, fill)
        fill.shader = null
    }

    private fun drawDirectionalRefraction(c: Canvas, cx: Float, cy: Float, r: Float) {
        val rad = Math.toRadians(jewelLightAngle.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val sx = cx - ux * r * .90f
        val sy = cy - uy * r * .90f
        val ex = cx + ux * r * .90f
        val ey = cy + uy * r * .90f
        fill.shader = LinearGradient(
            sx, sy, ex, ey,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(34, 255, 0, 45),
                Color.argb(138, 255, 214, 224),
                Color.argb(68, 255, 70, 105),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .31f, .50f, .66f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun drawFacetEdges(c: Canvas, cx: Float, cy: Float, r: Float) {
        edge.strokeWidth = maxOf(1f, r * .0105f)
        edge.color = Color.argb(92, 255, 188, 202)
        for (i in 0 until FACETS) {
            val a = angle(i)
            val p1 = point(cx, cy, r * .30f, a)
            val p2 = point(cx, cy, r * .64f, a)
            val p3 = point(cx, cy, r * .96f, a)
            c.drawLine(cx, cy, p1[0], p1[1], edge)
            c.drawLine(p1[0], p1[1], p2[0], p2[1], edge)
            c.drawLine(p2[0], p2[1], p3[0], p3[1], edge)
        }
        edge.alpha = 75
        c.drawCircle(cx, cy, r * .30f, edge)
        c.drawCircle(cx, cy, r * .64f, edge)
        edge.alpha = 255
    }

    private fun drawSpecularGlints(c: Canvas, cx: Float, cy: Float, r: Float) {
        val rad = Math.toRadians(jewelLightAngle.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val x = cx + ux * r * .48f
        val y = cy + uy * r * .48f
        glow.shader = RadialGradient(
            x, y, r * .34f,
            intArrayOf(
                Color.argb(220, 255, 246, 249),
                Color.argb(130, 255, 165, 184),
                Color.argb(28, 255, 45, 82),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .18f, .54f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r * .34f, glow)
        glow.shader = null

        edge.strokeWidth = maxOf(1f, r * .016f)
        edge.color = Color.argb(180, 255, 238, 243)
        c.drawLine(x - r * .12f, y, x + r * .12f, y, edge)
        c.drawLine(x, y - r * .12f, x, y + r * .12f, edge)
    }

    private fun drawOuterRim(c: Canvas, cx: Float, cy: Float, r: Float) {
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = maxOf(1.2f, r * .028f)
        edge.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.rgb(96, 0, 22),
                Color.rgb(255, 146, 166),
                Color.rgb(255, 28, 62),
                Color.rgb(80, 0, 18),
                Color.rgb(255, 215, 224),
                Color.rgb(165, 0, 32),
                Color.rgb(96, 0, 22)
            ),
            null
        )
        c.drawCircle(cx, cy, r, edge)
        edge.shader = null
    }

    private fun facing(normal: Float): Float {
        val delta = ((jewelLightAngle - normal + 540f) % 360f) - 180f
        return ((cos(Math.toRadians(delta.toDouble())) + 1.0) * .5).toFloat()
    }

    private fun angle(i: Int): Float = -90f + i * (360f / FACETS)
    private fun normal(a0: Float, a1: Float) = (a0 + a1) * .5f

    private fun point(cx: Float, cy: Float, r: Float, deg: Float): FloatArray {
        val rad = Math.toRadians(deg.toDouble())
        return floatArrayOf(cx + cos(rad).toFloat() * r, cy + sin(rad).toFloat() * r)
    }

    private fun path(vararg p: FloatArray): Path = Path().apply {
        moveTo(p[0][0], p[0][1])
        for (i in 1 until p.size) lineTo(p[i][0], p[i][1])
        close()
    }

    private fun path(cx: Float, cy: Float, vararg p: FloatArray): Path = Path().apply {
        moveTo(cx, cy)
        p.forEach { lineTo(it[0], it[1]) }
        close()
    }
}
