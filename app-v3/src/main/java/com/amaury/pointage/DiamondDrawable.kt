package com.amaury.pointage

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

/**
 * Bouton diamant rendu en couches indépendantes.
 * Chaque couche peut être modifiée sans toucher aux autres :
 * 1. verre de fond
 * 2. facettes
 * 3. table centrale
 * 4. lumière/réfraction
 * 5. biseau
 * 6. éclats
 */
class DiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private var tuning: DiamondTuning = DiamondTuning()
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(angle: Float) {
        lightAngle = ((angle % 360f) + 360f) % 360f
        invalidateSelf()
    }

    fun setTuning(value: DiamondTuning) {
        tuning = value
        invalidateSelf()
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val newPressed = state.any {
            it == android.R.attr.state_pressed || it == android.R.attr.state_focused
        }
        if (newPressed == pressed) return false
        pressed = newPressed
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return

        r.set(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat()
        )

        val geometry = buildGeometry(r)

        canvas.save()
        canvas.clipPath(geometry.outer)

        drawBaseGlassLayer(canvas, geometry)
        drawFacetLayer(canvas, geometry)
        drawTableLayer(canvas, geometry)
        drawLightLayer(canvas, geometry)

        canvas.restore()

        drawBevelLayer(canvas, geometry)
        drawSparkleLayer(canvas, geometry)
    }

    // ---------------------------------------------------------------------
    // COUCHE 1 — VERRE / COULEUR DE FOND
    // Réglages concernés : Transparence + Bleu glace
    // ---------------------------------------------------------------------
    private fun drawBaseGlassLayer(canvas: Canvas, g: DiamondGeometry) {
        val alphaScale = (1f - tuning.transparency * 0.72f).coerceIn(.18f, 1f)
        val blue = tuning.iceBlue.coerceIn(0f, 1f)

        val baseR = lerp(55, 5, blue)
        val baseG = lerp(62, 82, blue)
        val baseB = lerp(72, 145, blue)

        fill.shader = LinearGradient(
            r.left,
            r.top,
            r.left,
            r.bottom,
            intArrayOf(
                Color.argb(
                    (205 * alphaScale).toInt(),
                    lerp(baseR, 105, blue),
                    lerp(baseG, 190, blue),
                    lerp(baseB, 245, blue)
                ),
                Color.argb((155 * alphaScale).toInt(), baseR, baseG, baseB),
                Color.argb(
                    (195 * alphaScale).toInt(),
                    lerp(15, 0, blue),
                    lerp(22, 48, blue),
                    lerp(35, 105, blue)
                )
            ),
            floatArrayOf(0f, .48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, fill)
        fill.shader = null
    }

    // ---------------------------------------------------------------------
    // COUCHE 2 — FACETTES
    // Réglage concerné : Profondeur des facettes
    // ---------------------------------------------------------------------
    private fun drawFacetLayer(canvas: Canvas, g: DiamondGeometry) {
        val alphaScale = (1f - tuning.transparency * 0.72f).coerceIn(.18f, 1f)
        val energy = .35f + tuning.facetDepth * .95f

        g.facets.forEachIndexed { index, facetInfo ->
            val facing = facing(facetInfo.normal)
            val base = when (index % 4) {
                0 -> intArrayOf(205, 235, 255)
                1 -> intArrayOf(255, 255, 255)
                2 -> intArrayOf(154, 203, 242)
                else -> intArrayOf(220, 230, 255)
            }

            val alpha = ((22 + 170 * facing * energy) * alphaScale.coerceAtLeast(.48f))
                .toInt()
                .coerceIn(18, 220)

            fill.shader = LinearGradient(
                r.left,
                r.top,
                r.right,
                r.bottom,
                Color.argb(alpha, base[0], base[1], base[2]),
                Color.argb((alpha * .12f).toInt(), 65, 112, 166),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(facetInfo.path, fill)
            fill.shader = null
        }
    }

    // ---------------------------------------------------------------------
    // COUCHE 3 — TABLE CENTRALE DU DIAMANT
    // Dépend légèrement de la profondeur et de la transparence.
    // ---------------------------------------------------------------------
    private fun drawTableLayer(canvas: Canvas, g: DiamondGeometry) {
        val alphaScale = (1f - tuning.transparency * 0.72f).coerceIn(.18f, 1f)
        val tableAlpha = (50 * alphaScale).toInt().coerceAtLeast(8)

        fill.shader = LinearGradient(
            g.table.left,
            g.table.top,
            g.table.right,
            g.table.bottom,
            intArrayOf(
                Color.argb(tableAlpha, 225, 244, 255),
                Color.argb((18 * alphaScale).toInt(), 96, 143, 190),
                Color.argb((42 * alphaScale).toInt(), 214, 239, 255)
            ),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(g.table, g.height * .10f, g.height * .10f, fill)
        fill.shader = null
    }

    // ---------------------------------------------------------------------
    // COUCHE 4 — LUMIÈRE / RÉFRACTION
    // Réglages concernés : Réfraction + Direction de la lumière
    // ---------------------------------------------------------------------
    private fun drawLightLayer(canvas: Canvas, g: DiamondGeometry) {
        val radians = Math.toRadians(lightAngle.toDouble())
        val ux = cos(radians).toFloat()
        val uy = sin(radians).toFloat()
        val span = max(g.width, g.height) * .75f
        val dx = ux * span
        val dy = uy * span

        val refraction = tuning.refraction.coerceIn(0f, 1f)
        val beamAlpha = (70 + 100 * refraction).toInt()

        fill.shader = LinearGradient(
            r.centerX() - dx,
            r.centerY() - dy,
            r.centerX() + dx,
            r.centerY() + dy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(20, 120, 200, 255),
                Color.argb(if (pressed) beamAlpha / 2 else beamAlpha, 255, 255, 255),
                Color.argb(35, 100, 190, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .38f, .50f, .62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, fill)
        fill.shader = null
    }

    // ---------------------------------------------------------------------
    // COUCHE 5 — BISEAU EXTERNE + BISEAU INTERNE
    // Réglage concerné : Épaisseur du biseau
    // ---------------------------------------------------------------------
    private fun drawBevelLayer(canvas: Canvas, g: DiamondGeometry) {
        val bevel = tuning.bevel.coerceIn(0f, 1f)

        line.strokeWidth = (.35f + bevel * 3.0f) * density
        line.shader = LinearGradient(
            r.left,
            r.top,
            r.right,
            r.bottom,
            intArrayOf(
                Color.rgb(151, 211, 255),
                Color.WHITE,
                Color.rgb(113, 170, 224),
                Color.WHITE,
                Color.rgb(166, 220, 255)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        line.alpha = ((if (pressed) 180 else 245) * (.45f + bevel * .55f))
            .toInt()
            .coerceIn(60, 255)
        canvas.drawPath(g.outer, line)
        line.shader = null

        val inset = (.8f + bevel * 5.0f) * density
        val inner = RectF(
            r.left + inset,
            r.top + inset,
            r.right - inset,
            r.bottom - inset
        )

        line.strokeWidth = (.25f + bevel * 1.8f) * density
        line.color = Color.argb(
            (75 + 180 * bevel).toInt().coerceIn(0, 255),
            225,
            246,
            255
        )
        line.alpha = 255
        canvas.drawPath(
            cutPath(inner, (g.cut - inset).coerceAtLeast(3f * density)),
            line
        )
    }

    // ---------------------------------------------------------------------
    // COUCHE 6 — ÉCLATS / ÉTOILES
    // Réglage concerné : Éclats lumineux
    // ---------------------------------------------------------------------
    private fun drawSparkleLayer(canvas: Canvas, g: DiamondGeometry) {
        val sparkle = tuning.sparkle.coerceIn(0f, 1f)
        val glints = arrayOf(
            floatArrayOf(.08f, .20f, 210f),
            floatArrayOf(.25f, .07f, 255f),
            floatArrayOf(.55f, .06f, 292f),
            floatArrayOf(.82f, .08f, 325f),
            floatArrayOf(.96f, .34f, 0f),
            floatArrayOf(.88f, .88f, 42f),
            floatArrayOf(.54f, .94f, 90f),
            floatArrayOf(.15f, .88f, 145f)
        )

        glints.forEach { glint ->
            val facing = facing(glint[2])
            val threshold = .88f - sparkle * .28f
            if (facing < threshold) return@forEach

            val x = r.left + g.width * glint[0]
            val y = r.top + g.height * glint[1]
            val radius = (1.2f + (2.2f + 4.2f * sparkle) * facing) * density
            val alpha = (245 * facing * sparkle).toInt().coerceIn(0, 245)

            fill.shader = RadialGradient(
                x,
                y,
                radius,
                Color.argb(alpha, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, radius, fill)
            fill.shader = null
        }
    }

    private fun buildGeometry(rect: RectF): DiamondGeometry {
        val height = rect.height()
        val width = rect.width()
        val facetScale = .18f + tuning.facetDepth * .18f
        val cut = min(height * facetScale, (12f + tuning.facetDepth * 10f) * density)
        val outer = cutPath(rect, cut)

        val ix = rect.left + cut * 1.35f
        val ir = rect.right - cut * 1.35f
        val tableInset = height * (.18f + tuning.facetDepth * .10f)
        val it = rect.top + tableInset
        val ib = rect.bottom - tableInset

        val facets = arrayOf(
            Facet(facet(rect.left + cut, rect.top, rect.left + width * .19f, rect.top, ix, it, rect.left, rect.top + cut), 215f),
            Facet(facet(rect.left + width * .19f, rect.top, rect.left + width * .39f, rect.top, rect.left + width * .34f, it, ix, it), 250f),
            Facet(facet(rect.left + width * .39f, rect.top, rect.left + width * .61f, rect.top, rect.left + width * .66f, it, rect.left + width * .34f, it), 285f),
            Facet(facet(rect.left + width * .61f, rect.top, rect.right - cut, rect.top, ir, it, rect.left + width * .66f, it), 320f),
            Facet(facet(rect.right - cut, rect.top, rect.right, rect.top + cut, rect.right, rect.bottom - cut, ir, ib, ir, it), 355f),
            Facet(facet(rect.right, rect.bottom - cut, rect.right - cut, rect.bottom, rect.left + width * .81f, rect.bottom, ir, ib), 28f),
            Facet(facet(rect.left + width * .81f, rect.bottom, rect.left + width * .61f, rect.bottom, rect.left + width * .66f, ib, ir, ib), 63f),
            Facet(facet(rect.left + width * .61f, rect.bottom, rect.left + width * .39f, rect.bottom, rect.left + width * .34f, ib, rect.left + width * .66f, ib), 95f),
            Facet(facet(rect.left + width * .39f, rect.bottom, rect.left + width * .19f, rect.bottom, ix, ib, rect.left + width * .34f, ib), 128f),
            Facet(facet(rect.left + width * .19f, rect.bottom, rect.left + cut, rect.bottom, rect.left, rect.bottom - cut, rect.left, rect.top + cut, ix, it, ix, ib), 165f)
        )

        return DiamondGeometry(
            width = width,
            height = height,
            cut = cut,
            outer = outer,
            table = RectF(ix, it, ir, ib),
            facets = facets
        )
    }

    private fun facing(normal: Float): Float {
        val delta = ((lightAngle - normal + 540f) % 360f) - 180f
        return ((cos(Math.toRadians(delta.toDouble())) + 1.0) * .5).toFloat()
    }

    private fun cutPath(rect: RectF, cut: Float) = Path().apply {
        moveTo(rect.left + cut, rect.top)
        lineTo(rect.right - cut, rect.top)
        lineTo(rect.right, rect.top + cut)
        lineTo(rect.right, rect.bottom - cut)
        lineTo(rect.right - cut, rect.bottom)
        lineTo(rect.left + cut, rect.bottom)
        lineTo(rect.left, rect.bottom - cut)
        lineTo(rect.left, rect.top + cut)
        close()
    }

    private fun facet(vararg values: Float) = Path().apply {
        moveTo(values[0], values[1])
        var i = 2
        while (i < values.size) {
            lineTo(values[i], values[i + 1])
            i += 2
        }
        close()
    }

    private fun lerp(a: Int, b: Int, t: Float) =
        (a + (b - a) * t.coerceIn(0f, 1f)).toInt()

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        line.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        line.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private data class Facet(val path: Path, val normal: Float)

    private data class DiamondGeometry(
        val width: Float,
        val height: Float,
        val cut: Float,
        val outer: Path,
        val table: RectF,
        val facets: Array<Facet>
    )
}
