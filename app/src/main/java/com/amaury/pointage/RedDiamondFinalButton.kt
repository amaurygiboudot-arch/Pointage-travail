package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.Button
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.*

/**
 * Moteur optique temps réel des trois diamants permanents.
 * Le rendu est organisé en couches de profondeur : table, couronne,
 * pavillon visuel et ceinture, afin d'éviter l'effet de disque plat.
 */
open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant rouge final"
        private const val FACETS = 16
        private const val N_AIR = 1.000293f
        private const val N_DIAMOND = 2.417f
        private val live = Collections.newSetFromMap(WeakHashMap<RedDiamondFinalButton, Boolean>())

        fun updateGlobalNaturalLight(
            angle: Float,
            pitch: Float,
            roll: Float,
            intensity: Float,
            night: Boolean,
            elevation: Float
        ) {
            live.toList().forEach {
                it.setNaturalLight(angle, pitch, roll, intensity, night, elevation)
            }
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = Path()

    private var lightAngle = -55f
    private var pitch = 0f
    private var roll = 0f
    private var intensity = .78f
    private var night = false
    private var elevation = 45f

    open fun diamondPalette() = intArrayOf(
        Color.rgb(255, 50, 76), Color.rgb(214, 5, 35), Color.rgb(132, 0, 24), Color.rgb(255, 92, 118),
        Color.rgb(92, 0, 20), Color.rgb(238, 12, 48), Color.rgb(178, 0, 31), Color.rgb(255, 148, 164),
        Color.rgb(110, 0, 25), Color.rgb(245, 22, 56), Color.rgb(156, 0, 29), Color.rgb(255, 72, 102),
        Color.rgb(74, 0, 18), Color.rgb(226, 8, 42), Color.rgb(194, 0, 34), Color.rgb(255, 118, 140)
    )

    open fun diamondTint() = Color.rgb(255, 28, 62)
    open fun diamondDark() = Color.rgb(96, 0, 22)
    open fun diamondHighlight() = Color.rgb(255, 238, 243)

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        isAllCaps = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        live.add(this)
    }

    override fun onDetachedFromWindow() {
        live.remove(this)
        super.onDetachedFromWindow()
    }

    fun setDiamondLightAngle(angle: Float) {
        setNaturalLight(angle, pitch, roll, intensity, night, elevation)
    }

    private fun setNaturalLight(
        angle: Float,
        newPitch: Float,
        newRoll: Float,
        newIntensity: Float,
        newNight: Boolean,
        newElevation: Float
    ) {
        lightAngle = normalizeAngle(angle)
        pitch = newPitch.coerceIn(-90f, 90f)
        roll = newRoll.coerceIn(-90f, 90f)
        intensity = newIntensity.coerceIn(.12f, 1f)
        night = newNight
        elevation = newElevation.coerceIn(-10f, 90f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * .5f
        val cy = h * .5f
        val radius = min(w, h) * .455f
        val pressedScale = if (isPressed) .93f else 1f

        drawDropDepth(canvas, cx, cy, radius * pressedScale)

        canvas.save()
        canvas.scale(pressedScale, pressedScale, cx, cy)
        outer.reset()
        outer.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(outer)

        drawGlassBody(canvas, cx, cy, radius)
        drawPavilionShadow(canvas, cx, cy, radius)
        drawFacets(canvas, cx, cy, radius)
        drawTableLift(canvas, cx, cy, radius)
        drawInnerRefraction(canvas, cx, cy, radius)
        drawFacetEdges(canvas, cx, cy, radius)
        drawCausticGlints(canvas, cx, cy, radius)

        canvas.restore()
        drawGirdle(canvas, cx, cy, radius * pressedScale)
    }

    /** Ombre externe très courte : donne de l'épaisseur sans faire un gros halo. */
    private fun drawDropDepth(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        shadow.shader = RadialGradient(
            cx,
            cy + r * .08f,
            r * 1.16f,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(88, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(.67f, .88f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + r * .08f, r * 1.16f, shadow)
        shadow.shader = null
    }

    private fun drawGlassBody(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val rad = Math.toRadians(lightAngle.toDouble())
        val lx = cx + cos(rad).toFloat() * r * .24f
        val ly = cy + sin(rad).toFloat() * r * .24f
        val tint = diamondTint()
        val dark = diamondDark()

        fill.shader = RadialGradient(
            lx,
            ly,
            r * 1.28f,
            intArrayOf(
                alpha(lighten(tint, .34f), (160 + 65 * intensity).toInt()),
                alpha(tint, 214),
                alpha(dark, 232),
                Color.argb(244, Color.red(dark) / 7, Color.green(dark) / 7, Color.blue(dark) / 7)
            ),
            floatArrayOf(0f, .34f, .72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    /** Assombrit la périphérie comme si les facettes descendaient vers le pavillon. */
    private fun drawPavilionShadow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val dark = diamondDark()
        fill.shader = RadialGradient(
            cx - roll * .02f,
            cy + pitch * .02f,
            r,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                alpha(dark, 42),
                alpha(Color.BLACK, 138)
            ),
            floatArrayOf(0f, .43f, .73f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun drawFacets(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val tableRadius = r * .28f
        val crownRadius = r * .63f
        val girdleRadius = r * .96f

        for (i in 0 until FACETS) {
            val a0 = facetAngle(i)
            val a1 = facetAngle(i + 1)
            drawFacet(
                canvas,
                centerFacet(cx, cy, point(cx, cy, tableRadius, a0), point(cx, cy, tableRadius, a1)),
                i,
                (a0 + a1) * .5f,
                0,
                1.16f
            )
        }

        for (i in 0 until FACETS) {
            val a0 = facetAngle(i)
            val a1 = facetAngle(i + 1)
            val am = (a0 + a1) * .5f
            val i0 = point(cx, cy, tableRadius, a0)
            val i1 = point(cx, cy, tableRadius, a1)
            val m0 = point(cx, cy, crownRadius, a0)
            val m1 = point(cx, cy, crownRadius, a1)
            val mm = point(cx, cy, crownRadius, am)

            drawFacet(canvas, polygon(i0, m0, mm, i1), i + 3, (a0 + am) * .5f, 1, .98f)
            drawFacet(canvas, polygon(i1, mm, m1), i + 9, (am + a1) * .5f, 1, .87f)
        }

        for (i in 0 until FACETS) {
            val a0 = facetAngle(i)
            val a1 = facetAngle(i + 1)
            val am = (a0 + a1) * .5f
            val m0 = point(cx, cy, crownRadius, a0)
            val m1 = point(cx, cy, crownRadius, a1)
            val o0 = point(cx, cy, girdleRadius, a0)
            val o1 = point(cx, cy, girdleRadius, a1)
            val om = point(cx, cy, girdleRadius, am)

            drawFacet(canvas, polygon(m0, o0, om, m1), i + 5, (a0 + am) * .5f, 2, .73f)
            drawFacet(canvas, polygon(m1, om, o1), i + 12, (am + a1) * .5f, 2, .61f)
        }
    }

    private fun drawFacet(
        canvas: Canvas,
        path: Path,
        index: Int,
        azimuth: Float,
        ring: Int,
        energy: Float
    ) {
        val baseTilt = when (ring) {
            0 -> 11f
            1 -> 30f
            else -> 47f
        }
        val microTilt = (((index * 37 + ring * 53) % 17) - 8) * .46f
        val microAz = (((index * 29 + ring * 11) % 13) - 6) * .42f
        val normal = normal3(azimuth + microAz, baseTilt + microTilt, pitch, roll)
        val light = light3(lightAngle, elevation)
        val view = floatArrayOf(0f, 0f, 1f)
        val ndl = max(0f, dot(normal, light))
        val ndv = max(.001f, dot(normal, view))
        val halfVector = normalize3(floatArrayOf(light[0] + view[0], light[1] + view[1], light[2] + view[2]))
        val ndh = max(0f, dot(normal, halfVector))

        val r0 = ((N_AIR - N_DIAMOND) / (N_AIR + N_DIAMOND)).pow(2)
        val fresnel = r0 + (1f - r0) * (1f - ndv).pow(5)
        val specular = ndh.pow(if (night) 70f else 155f) * intensity

        val incidence = Math.toDegrees(acos(ndl.coerceIn(0f, 1f)).toDouble()).toFloat()
        val critical = Math.toDegrees(asin((N_AIR / N_DIAMOND).toDouble())).toFloat()
        val tir = if (incidence > critical) {
            ((incidence - critical) / (90f - critical)).coerceIn(0f, 1f)
        } else 0f

        val transmission = (1f - fresnel) * (1f - tir) * ndl
        val ringDepth = when (ring) {
            0 -> 1.05f
            1 -> .83f
            else -> .58f
        }
        val brightness = (
            .075f +
                energy * ringDepth * (ndl * .66f + transmission * .31f) +
                fresnel * .20f +
                tir * .08f
            ).coerceIn(.055f, 1.42f) * (.60f + .50f * intensity)

        val palette = diamondPalette()
        val base = palette[index % palette.size]
        val hi = diamondHighlight()
        val rr = (Color.red(base) * brightness).toInt().coerceIn(0, 255)
        val gg = (Color.green(base) * brightness).toInt().coerceIn(0, 255)
        val bb = (Color.blue(base) * brightness).toInt().coerceIn(0, 255)

        val flash = (specular * 2.05f + fresnel * .16f + tir * .15f).coerceIn(0f, 1f)
        val top = mix(Color.rgb(rr, gg, bb), hi, flash)
        val deepFactor = when (ring) { 0 -> .52f; 1 -> .34f; else -> .20f }
        val deep = Color.rgb(
            (rr * deepFactor).toInt().coerceIn(0, 255),
            (gg * deepFactor).toInt().coerceIn(0, 255),
            (bb * deepFactor).toInt().coerceIn(0, 255)
        )
        val alpha = (150 + ndl * 58f + fresnel * 31f).toInt().coerceIn(145, 245)

        val gradientAngle = Math.toRadians((azimuth + 90f).toDouble())
        val gx = cos(gradientAngle).toFloat() * width * .42f
        val gy = sin(gradientAngle).toFloat() * height * .42f
        fill.shader = LinearGradient(
            width * .5f - gx,
            height * .5f - gy,
            width * .5f + gx,
            height * .5f + gy,
            alpha(top, alpha),
            alpha(deep, (alpha * .76f).toInt()),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null

        if (specular > .42f) {
            fill.color = alpha(Color.WHITE, ((specular - .42f) / .58f * 170f).toInt().coerceIn(0, 170))
            canvas.drawPath(path, fill)
        }

        drawSubtleFire(canvas, path, index, ring, ndh, transmission, tir)
    }

    /** Le feu chromatique reste rare et local à une facette. */
    private fun drawSubtleFire(
        canvas: Canvas,
        path: Path,
        index: Int,
        ring: Int,
        alignment: Float,
        transmission: Float,
        tir: Float
    ) {
        if (night) return
        val threshold = .90f + ring * .016f
        val gate = ((alignment - threshold) / (1f - threshold)).coerceIn(0f, 1f)
        val selected = ((index * 17 + ring * 7) % 13) <= 3
        if (!selected || gate <= 0f) return

        val power = (gate.pow(2.1f) * transmission * intensity * (1f - .30f * tir)).coerceIn(0f, .28f)
        if (power < .02f) return

        val q = Math.toRadians((lightAngle + index * 3.2f).toDouble())
        val dx = cos(q).toFloat() * width * .012f
        val dy = sin(q).toFloat() * height * .012f
        fill.shader = LinearGradient(
            width * .5f - dx,
            height * .5f - dy,
            width * .5f + dx,
            height * .5f + dy,
            intArrayOf(
                alpha(Color.rgb(255, 134, 58), (112f * power).toInt()),
                Color.TRANSPARENT,
                alpha(Color.rgb(80, 150, 255), (126f * power).toInt())
            ),
            floatArrayOf(0f, .5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fill)
        fill.shader = null
    }

    /** Table centrale légèrement bombée visuellement : elle doit sembler au-dessus de la couronne. */
    private fun drawTableLift(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val tableR = r * .285f
        val rad = Math.toRadians(lightAngle.toDouble())
        val hx = cx + cos(rad).toFloat() * tableR * .34f
        val hy = cy + sin(rad).toFloat() * tableR * .34f
        val hi = diamondHighlight()

        fill.shader = RadialGradient(
            hx,
            hy,
            tableR * 1.12f,
            intArrayOf(
                alpha(hi, if (night) 38 else 84),
                Color.TRANSPARENT,
                alpha(Color.BLACK, 48)
            ),
            floatArrayOf(0f, .58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, tableR, fill)
        fill.shader = null

        edge.strokeWidth = maxOf(1f, r * .014f)
        edge.color = alpha(hi, if (night) 44 else 92)
        canvas.drawCircle(cx, cy, tableR, edge)
    }

    private fun drawInnerRefraction(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val rad = Math.toRadians(lightAngle.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val hi = diamondHighlight()
        val tint = diamondTint()

        fill.shader = LinearGradient(
            cx - ux * r * .72f,
            cy - uy * r * .72f,
            cx + ux * r * .72f,
            cy + uy * r * .72f,
            intArrayOf(
                Color.TRANSPARENT,
                alpha(tint, (18f * intensity).toInt()),
                alpha(hi, ((if (night) 50f else 105f) * intensity).toInt()),
                alpha(tint, (25f * intensity).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .32f, .50f, .68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * .78f, fill)
        fill.shader = null
    }

    private fun drawFacetEdges(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val hi = diamondHighlight()
        edge.strokeWidth = maxOf(1f, r * .009f)
        edge.color = alpha(hi, if (night) 44 else 74)

        for (i in 0 until FACETS) {
            val a = facetAngle(i)
            val p1 = point(cx, cy, r * .28f, a)
            val p2 = point(cx, cy, r * .63f, a)
            val p3 = point(cx, cy, r * .96f, a)
            canvas.drawLine(cx, cy, p1[0], p1[1], edge)
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], edge)
            canvas.drawLine(p2[0], p2[1], p3[0], p3[1], edge)
        }

        edge.alpha = 64
        canvas.drawCircle(cx, cy, r * .28f, edge)
        edge.alpha = 48
        canvas.drawCircle(cx, cy, r * .63f, edge)
        edge.alpha = 255
    }

    private fun drawCausticGlints(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val rad = Math.toRadians(lightAngle.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val hi = diamondHighlight()
        val elevationFactor = ((elevation + 5f) / 70f).coerceIn(.15f, 1f)
        val power = intensity * elevationFactor * if (night) .36f else 1f
        val x = cx + ux * r * (.43f + roll * .0015f).coerceIn(.24f, .65f)
        val y = cy + uy * r * (.43f - pitch * .0015f).coerceIn(.24f, .65f)

        glow.shader = RadialGradient(
            x,
            y,
            r * .21f,
            intArrayOf(
                alpha(Color.WHITE, (235f * power).toInt().coerceIn(16, 235)),
                alpha(hi, (118f * power).toInt().coerceIn(8, 118)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .10f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, r * .21f, glow)
        glow.shader = null

        if (!night && power > .48f) {
            edge.strokeWidth = maxOf(1f, r * .011f)
            edge.color = alpha(Color.WHITE, (215f * power).toInt().coerceIn(40, 215))
            val len = r * (.045f + .12f * power)
            canvas.drawLine(x - len, y, x + len, y, edge)
            canvas.drawLine(x, y - len, x, y + len, edge)
        }
    }

    /** Ceinture plus épaisse avec côté sombre + filet clair : donne l'épaisseur du bijou. */
    private fun drawGirdle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val dark = diamondDark()
        val tint = diamondTint()
        val hi = diamondHighlight()

        edge.style = Paint.Style.STROKE
        edge.strokeWidth = maxOf(2.2f, r * .060f)
        edge.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(
                alpha(dark, 255),
                alpha(tint, 230),
                alpha(hi, 238),
                alpha(dark, 255),
                alpha(tint, 220),
                alpha(hi, 228),
                alpha(dark, 255)
            ),
            null
        )
        canvas.drawCircle(cx, cy, r * .982f, edge)
        edge.shader = null

        edge.strokeWidth = maxOf(1f, r * .015f)
        edge.color = alpha(hi, if (night) 72 else 145)
        canvas.drawCircle(cx, cy, r * .952f, edge)

        edge.strokeWidth = maxOf(1f, r * .012f)
        edge.color = alpha(Color.BLACK, 140)
        canvas.drawCircle(cx, cy, r * 1.012f, edge)
    }

    private fun normal3(azimuth: Float, tilt: Float, p: Float, r: Float): FloatArray {
        val az = Math.toRadians((azimuth + r * .18f).toDouble())
        val tr = Math.toRadians((tilt + p * .08f).coerceIn(2f, 82f).toDouble())
        val s = sin(tr).toFloat()
        return normalize3(
            floatArrayOf(
                cos(az).toFloat() * s,
                sin(az).toFloat() * s,
                cos(tr).toFloat()
            )
        )
    }

    private fun light3(azimuth: Float, elev: Float): FloatArray {
        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elev.coerceIn(-5f, 90f).toDouble())
        val ce = cos(el).toFloat()
        return normalize3(
            floatArrayOf(
                cos(az).toFloat() * ce,
                sin(az).toFloat() * ce,
                sin(el).toFloat()
            )
        )
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize3(v: FloatArray): FloatArray {
        val length = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-8f))
        return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    private fun facetAngle(i: Int): Float = -90f + i * (360f / FACETS)
    private fun normalizeAngle(v: Float): Float = ((v % 360f) + 360f) % 360f

    private fun point(cx: Float, cy: Float, r: Float, degrees: Float): FloatArray {
        val q = Math.toRadians(degrees.toDouble())
        return floatArrayOf(cx + cos(q).toFloat() * r, cy + sin(q).toFloat() * r)
    }

    private fun polygon(vararg points: FloatArray): Path = Path().apply {
        moveTo(points[0][0], points[0][1])
        for (i in 1 until points.size) lineTo(points[i][0], points[i][1])
        close()
    }

    private fun centerFacet(cx: Float, cy: Float, vararg points: FloatArray): Path = Path().apply {
        moveTo(cx, cy)
        points.forEach { lineTo(it[0], it[1]) }
        close()
    }

    private fun alpha(color: Int, value: Int): Int = Color.argb(
        value.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
    )

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
        )
    }
}
