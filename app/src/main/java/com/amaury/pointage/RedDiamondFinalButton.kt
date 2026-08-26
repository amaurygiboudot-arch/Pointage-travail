package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.Button
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rendu Canvas du diamant 80 facettes.
 * Les trois vrais boutons utilisent ce moteur et lisent en direct les réglages
 * développeur de PrimaryDiamondLiveTuning.
 */
open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant céleste 80 facettes"

        @Deprecated("Use CelestialStateStore")
        fun updateGlobalNaturalLight(
            angle: Float,
            pitch: Float,
            roll: Float,
            intensity: Float,
            night: Boolean,
            elevation: Float
        ) = Unit
    }

    private val engine = DiamondFacetEngine()
    private val geometry = DiamondGeometry80.facets
    private val facetPaths = arrayOfNulls<Path>(80)
    private val facetBounds = Array(80) { RectF() }
    private val facetCentroids = Array(80) { FloatArray(2) }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

    private var centerX = 0f
    private var centerY = 0f
    private var diamondRadius = 0f
    private var lastRadiusScale = -1f

    @Deprecated("The new engine uses one material color per diamond")
    open fun diamondPalette() = intArrayOf(diamondTint())

    open fun diamondTint() = Color.rgb(255, 18, 48)
    open fun diamondDark() = Color.rgb(150, 0, 22)
    open fun diamondHighlight() = Color.rgb(255, 238, 243)

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        isAllCaps = false
    }

    fun setDiamondLightAngle(angle: Float) {
        postInvalidateOnAnimation()
    }

    @Deprecated("Curvature is part of the canonical geometry")
    fun setLensStrength(value: Float) {
        postInvalidateOnAnimation()
    }

    fun applyLiveDeveloperTuning() {
        lastRadiusScale = -1f
        if (width > 0 && height > 0) updateGeometryForTuning(PrimaryDiamondLiveTuning.current(context))
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        centerX = w * 0.5f
        centerY = h * 0.5f
        updateGeometryForTuning(PrimaryDiamondLiveTuning.current(context))
    }

    private fun updateGeometryForTuning(tuning: PrimaryDiamondLiveTuningConfig) {
        val wanted = tuning.radiusScale.coerceIn(.30f, .50f)
        if (wanted == lastRadiusScale && diamondRadius > 0f) return
        lastRadiusScale = wanted
        diamondRadius = min(width, height) * wanted
        rebuildFacetPaths()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val tuning = PrimaryDiamondLiveTuning.current(context)
        updateGeometryForTuning(tuning)
        if (diamondRadius <= 0f) return

        val snapshot = CelestialStateStore.current()
        val opticalFrame = engine.update(snapshot, System.nanoTime(), tuning)
        val pressScale = if (isPressed) tuning.pressScale.coerceIn(.70f, 1f) else 1f

        canvas.save()
        canvas.scale(pressScale, pressScale, centerX, centerY)
        drawFacets(canvas, opticalFrame, tuning)
        drawCelestialOverlay(canvas, opticalFrame, snapshot.isNight, tuning)
        drawEdges(canvas, opticalFrame, tuning)
        drawGirdle(canvas, opticalFrame, tuning)
        canvas.restore()

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun rebuildFacetPaths() {
        facetPaths.fill(null)
        val inner = diamondRadius * 0.28f
        val middle = diamondRadius * 0.63f
        val outer = diamondRadius * 0.96f
        val segments = 16

        repeat(segments) { i ->
            val a0 = segmentAngle(i)
            val a1 = segmentAngle(i + 1)
            setFacetPath(i, polygon(floatArrayOf(centerX, centerY), point(inner, a0), point(inner, a1)))
        }

        repeat(segments) { i ->
            val a0 = segmentAngle(i)
            val a1 = segmentAngle(i + 1)
            val am = (a0 + a1) * 0.5f
            val i0 = point(inner, a0)
            val i1 = point(inner, a1)
            val m0 = point(middle, a0)
            val m1 = point(middle, a1)
            val mm = point(middle, am)
            setFacetPath(16 + i * 2, polygon(i0, m0, mm, i1))
            setFacetPath(17 + i * 2, polygon(i1, mm, m1))
        }

        repeat(segments) { i ->
            val a0 = segmentAngle(i)
            val a1 = segmentAngle(i + 1)
            val am = (a0 + a1) * 0.5f
            val m0 = point(middle, a0)
            val m1 = point(middle, a1)
            val o0 = point(outer, a0)
            val o1 = point(outer, a1)
            val om = point(outer, am)
            setFacetPath(48 + i * 2, polygon(m0, o0, om, m1))
            setFacetPath(49 + i * 2, polygon(m1, om, o1))
        }
    }

    private fun setFacetPath(id: Int, path: Path) {
        facetPaths[id] = path
        path.computeBounds(facetBounds[id], true)
        val bounds = facetBounds[id]
        facetCentroids[id][0] = bounds.centerX()
        facetCentroids[id][1] = bounds.centerY()
    }

    private fun drawFacets(canvas: Canvas, frame: DiamondOpticalFrame, tuning: PrimaryDiamondLiveTuningConfig) {
        val material = PrimaryDiamondLiveTuning.adjustedMaterialColor(context, id, diamondTint())
        val dark = PrimaryDiamondLiveTuning.adjustedMaterialColor(context, id, diamondDark())
        val highlight = diamondHighlight()

        geometry.forEach { facet ->
            val path = facetPaths[facet.id] ?: return@forEach
            val state = frame.facets[facet.id]
            val radial = radialUnit(facet.azimuthDeg)
            val span = when (facet.ring) {
                DiamondRing.INNER -> diamondRadius * 0.17f
                DiamondRing.MIDDLE -> diamondRadius * 0.24f
                DiamondRing.OUTER -> diamondRadius * 0.31f
            }

            val baseLit = scaleColor(material, state.luminance, tuning.baseLuminance)
            val slopeStrength = when (facet.ring) {
                DiamondRing.INNER -> tuning.innerSlope
                DiamondRing.MIDDLE -> tuning.middleSlope
                DiamondRing.OUTER -> tuning.outerSlope
            }.coerceIn(0f, .80f)
            val maxMix = tuning.highlightMix.coerceIn(0f, .60f)
            val upper = mix(baseLit, highlight, (state.directLight * maxMix).coerceIn(0f, maxMix))
            val lower = mix(baseLit, dark, slopeStrength)
            val alpha = (state.referenceTranslucency * tuning.translucencyScale * 255f)
                .roundToInt().coerceIn(0, 255)

            val gx = radial[0] * span
            val gy = radial[1] * span
            fillPaint.shader = LinearGradient(
                centerX - gx,
                centerY - gy,
                centerX + gx,
                centerY + gy,
                withAlpha(upper, alpha),
                withAlpha(lower, alpha),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, fillPaint)
            fillPaint.shader = null
            drawLocalizedSpecular(canvas, path, facet, state, frame, alpha, tuning)
        }
    }

    private fun drawLocalizedSpecular(
        canvas: Canvas,
        path: Path,
        facet: DiamondFacetGeometry,
        state: DiamondFacetOpticalState,
        frame: DiamondOpticalFrame,
        materialAlpha: Int,
        tuning: PrimaryDiamondLiveTuningConfig
    ) {
        if (state.specular < 0.015f) return
        val bounds = facetBounds[facet.id]
        if (bounds.isEmpty) return
        val light = frame.sunDirectionDevice ?: frame.moonDirectionDevice ?: return
        val offset = tuning.specularOffset.coerceIn(0f, 1f)
        val cx = facetCentroids[facet.id][0] + light.x * bounds.width() * offset
        val cy = facetCentroids[facet.id][1] - light.y * bounds.height() * offset
        val radius = max(bounds.width(), bounds.height()).coerceAtLeast(4f) * tuning.specularRadius.coerceIn(.10f, 2f)
        val maxAlpha = tuning.specularAlpha.roundToInt().coerceIn(0, 255)
        val peakAlpha = (state.specular * maxAlpha * materialAlpha / 255f).roundToInt().coerceIn(0, maxAlpha)
        if (peakAlpha <= 0) return

        fillPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb(peakAlpha, 255, 255, 255),
                Color.argb((peakAlpha * 0.30f).roundToInt(), 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null
    }

    private fun drawCelestialOverlay(
        canvas: Canvas,
        frame: DiamondOpticalFrame,
        night: Boolean,
        tuning: PrimaryDiamondLiveTuningConfig
    ) {
        val light = if (night) frame.moonDirectionDevice ?: frame.sunDirectionDevice else frame.sunDirectionDevice ?: frame.moonDirectionDevice
        light ?: return
        val len = kotlin.math.sqrt(light.x * light.x + light.y * light.y).coerceAtLeast(.001f)
        val ux = light.x / len
        val uy = -light.y / len
        val haloAlpha = (if (night) tuning.moonHaloAlpha else tuning.sunHaloAlpha).roundToInt().coerceIn(0, 255)
        val shadowAlpha = (if (night) tuning.moonShadowAlpha else tuning.sunShadowAlpha).roundToInt().coerceIn(0, 255)
        val arcAlpha = (if (night) tuning.moonArcAlpha else tuning.sunArcAlpha).roundToInt().coerceIn(0, 255)
        val lightColor = if (night) Color.rgb(190, 220, 255) else Color.rgb(255, 239, 184)

        if (haloAlpha > 0) {
            val hx = centerX + ux * diamondRadius * tuning.haloOffset.coerceIn(0f, 1.5f)
            val hy = centerY + uy * diamondRadius * tuning.haloOffset.coerceIn(0f, 1.5f)
            overlayPaint.style = Paint.Style.FILL
            overlayPaint.shader = RadialGradient(
                hx, hy,
                diamondRadius * tuning.haloRadius.coerceIn(.1f, 2.5f),
                intArrayOf(
                    withAlpha(lightColor, haloAlpha),
                    withAlpha(lightColor, (haloAlpha * .52f).roundToInt()),
                    withAlpha(lightColor, (haloAlpha * .05f).roundToInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, .30f, .68f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(centerX, centerY, diamondRadius * .96f, Path.Direction.CW) })
            canvas.drawCircle(centerX, centerY, diamondRadius, overlayPaint)
            canvas.restore()
            overlayPaint.shader = null
        }

        if (shadowAlpha > 0) {
            val sx = centerX - ux * diamondRadius * tuning.shadowOffset.coerceIn(0f, 1.5f)
            val sy = centerY - uy * diamondRadius * tuning.shadowOffset.coerceIn(0f, 1.5f)
            overlayPaint.style = Paint.Style.FILL
            overlayPaint.shader = RadialGradient(
                sx, sy,
                diamondRadius * tuning.shadowRadius.coerceIn(.1f, 2f),
                intArrayOf(Color.argb(shadowAlpha, 0, 0, 0), Color.argb((shadowAlpha * .30f).roundToInt(), 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, .55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(centerX, centerY, diamondRadius * .96f, Path.Direction.CW) })
            canvas.drawCircle(centerX, centerY, diamondRadius, overlayPaint)
            canvas.restore()
            overlayPaint.shader = null
        }

        if (arcAlpha > 0 && tuning.arcSpanDeg > 0f) {
            val lightAngle = Math.toDegrees(atan2(uy.toDouble(), ux.toDouble())).toFloat()
            val angle = lightAngle + tuning.arcAngleOffsetDeg.coerceIn(-180f, 180f)
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeCap = Paint.Cap.ROUND
            overlayPaint.strokeWidth = max(1f, diamondRadius * tuning.arcWidth.coerceIn(.001f, .2f))
            overlayPaint.color = withAlpha(lightColor, arcAlpha)
            val rr = diamondRadius * tuning.arcRadius.coerceIn(.55f, 1.10f)
            canvas.drawArc(RectF(centerX - rr, centerY - rr, centerX + rr, centerY + rr), angle - tuning.arcSpanDeg / 2f, tuning.arcSpanDeg, false, overlayPaint)
        }
    }

    private fun drawEdges(canvas: Canvas, frame: DiamondOpticalFrame, tuning: PrimaryDiamondLiveTuningConfig) {
        val minimum = tuning.baseLuminance.coerceIn(.02f, .95f)
        val averageLuminance = frame.facets.map { it.luminance }.average().toFloat().coerceIn(minimum, 1.25f)
        edgePaint.strokeWidth = max(1f, diamondRadius * tuning.edgeWidth.coerceIn(.0005f, .08f))
        val alpha = (tuning.edgeBaseAlpha + averageLuminance * tuning.edgeLightAlpha).roundToInt().coerceIn(0, 255)
        edgePaint.color = withAlpha(diamondHighlight(), alpha)

        val inner = diamondRadius * 0.28f
        val middle = diamondRadius * 0.63f
        val outer = diamondRadius * 0.96f
        repeat(16) { i ->
            val angle = segmentAngle(i)
            val p1 = point(inner, angle)
            val p2 = point(middle, angle)
            val p3 = point(outer, angle)
            canvas.drawLine(centerX, centerY, p1[0], p1[1], edgePaint)
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], edgePaint)
            canvas.drawLine(p2[0], p2[1], p3[0], p3[1], edgePaint)
        }
    }

    private fun drawGirdle(canvas: Canvas, frame: DiamondOpticalFrame, tuning: PrimaryDiamondLiveTuningConfig) {
        val averageLuminance = frame.facets
            .filter { geometry[it.facetId].ring == DiamondRing.OUTER }
            .map { it.luminance }
            .average().toFloat().coerceIn(.05f, 1.25f)

        edgePaint.strokeWidth = max(1f, diamondRadius * tuning.girdleWidth.coerceIn(.001f, .12f))
        edgePaint.color = withAlpha(
            scaleColor(PrimaryDiamondLiveTuning.adjustedMaterialColor(context, id, diamondTint()), 0.55f + averageLuminance * 0.35f, tuning.baseLuminance),
            tuning.girdleAlpha.roundToInt().coerceIn(0, 255)
        )
        canvas.drawCircle(centerX, centerY, diamondRadius * tuning.girdleRadius.coerceIn(.70f, 1.15f), edgePaint)

        edgePaint.strokeWidth = max(1f, diamondRadius * tuning.girdleInnerWidth.coerceIn(.001f, .08f))
        edgePaint.color = withAlpha(diamondHighlight(), tuning.girdleInnerAlpha.roundToInt().coerceIn(0, 255))
        canvas.drawCircle(centerX, centerY, diamondRadius * tuning.girdleInnerRadius.coerceIn(.65f, 1.10f), edgePaint)
    }

    private fun segmentAngle(index: Int): Float = -90f + index * (360f / 16f)

    private fun point(radius: Float, degrees: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        return floatArrayOf(centerX + cos(rad).toFloat() * radius, centerY + sin(rad).toFloat() * radius)
    }

    private fun polygon(vararg points: FloatArray): Path = Path().apply {
        moveTo(points[0][0], points[0][1])
        for (i in 1 until points.size) lineTo(points[i][0], points[i][1])
        close()
    }

    private fun radialUnit(azimuthDeg: Float): FloatArray {
        val rad = Math.toRadians(azimuthDeg.toDouble())
        return floatArrayOf(cos(rad).toFloat(), sin(rad).toFloat())
    }

    private fun scaleColor(color: Int, luminance: Float, minimum: Float): Int {
        val l = luminance.coerceIn(minimum.coerceIn(.02f, .95f), 1.25f)
        return Color.rgb(
            (Color.red(color) * l).roundToInt().coerceIn(0, 255),
            (Color.green(color) * l).roundToInt().coerceIn(0, 255),
            (Color.blue(color) * l).roundToInt().coerceIn(0, 255)
        )
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt().coerceIn(0, 255)
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )
}
