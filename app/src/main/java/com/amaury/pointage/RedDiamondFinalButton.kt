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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rendu Canvas du diamant 80 facettes.
 *
 * Architecture : géométrie -> snapshot céleste -> DiamondFacetEngine -> rendu.
 * La vue ne recalcule ni l'astronomie, ni l'orientation du téléphone, ni la
 * physique lumineuse. Rouge, vert et orange utilisent exactement ce même moteur.
 */
open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant céleste 80 facettes"

        /**
         * Pont binaire temporaire pour les anciens appelants. Le nouveau moteur
         * consomme CelestialStateStore directement : aucune donnée physique n'est
         * plus stockée globalement dans les boutons.
         */
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

    private var centerX = 0f
    private var centerY = 0f
    private var diamondRadius = 0f

    /** Anciennes palettes conservées seulement pour compatibilité des sous-classes. */
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

    /** Compatibilité avec l'ancien laboratoire/installer. */
    fun setDiamondLightAngle(angle: Float) {
        postInvalidateOnAnimation()
    }

    /** La courbure est désormais intégrée à DiamondGeometry80, pas réglée ici. */
    @Deprecated("Curvature is part of the canonical geometry")
    fun setLensStrength(value: Float) {
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        centerX = w * 0.5f
        centerY = h * 0.5f
        diamondRadius = min(w, h) * 0.455f
        rebuildFacetPaths()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || diamondRadius <= 0f) return

        val snapshot = CelestialStateStore.current()
        val opticalFrame = engine.update(snapshot, System.nanoTime())
        val pressScale = if (isPressed) 0.93f else 1f

        canvas.save()
        canvas.scale(pressScale, pressScale, centerX, centerY)
        drawFacets(canvas, opticalFrame)
        drawEdges(canvas, opticalFrame)
        drawGirdle(canvas, opticalFrame)
        canvas.restore()

        // Tant que le téléphone bouge, l'état optique possède une micro-inertie.
        // Le prochain frame permet à cette réponse de converger indépendamment du Hz.
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
            setFacetPath(
                i,
                polygon(
                    floatArrayOf(centerX, centerY),
                    point(inner, a0),
                    point(inner, a1)
                )
            )
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

    private fun drawFacets(canvas: Canvas, frame: DiamondOpticalFrame) {
        val material = diamondTint()
        val dark = diamondDark()
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

            // Une seule couleur de matière. Les nuances viennent de l'optique.
            val baseLit = scaleColor(material, state.luminance)
            val slopeStrength = when (facet.ring) {
                DiamondRing.INNER -> 0.08f
                DiamondRing.MIDDLE -> 0.14f
                DiamondRing.OUTER -> 0.20f
            }
            val upper = mix(baseLit, highlight, (state.directLight * 0.09f).coerceIn(0f, 0.09f))
            val lower = mix(baseLit, dark, slopeStrength)
            val alpha = (state.referenceTranslucency * 255f).roundToInt().coerceIn(0, 255)

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

            drawLocalizedSpecular(canvas, path, facet, state, frame, alpha)
        }
    }

    /**
     * Le spéculaire est localisé : jamais de couche blanche couvrant toute une facette.
     */
    private fun drawLocalizedSpecular(
        canvas: Canvas,
        path: Path,
        facet: DiamondFacetGeometry,
        state: DiamondFacetOpticalState,
        frame: DiamondOpticalFrame,
        materialAlpha: Int
    ) {
        if (state.specular < 0.025f) return
        val bounds = facetBounds[facet.id]
        if (bounds.isEmpty) return

        val light = frame.sunDirectionDevice ?: frame.moonDirectionDevice ?: return
        val cx = facetCentroids[facet.id][0] + light.x * bounds.width() * 0.18f
        val cy = facetCentroids[facet.id][1] - light.y * bounds.height() * 0.18f
        val radius = max(bounds.width(), bounds.height()).coerceAtLeast(4f) * 0.55f
        val peakAlpha = (state.specular * 92f * materialAlpha / 255f).roundToInt().coerceIn(0, 92)

        fillPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
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

    private fun drawEdges(canvas: Canvas, frame: DiamondOpticalFrame) {
        val averageLuminance = frame.facets.map { it.luminance }.average().toFloat().coerceIn(0.2f, 1f)
        edgePaint.strokeWidth = max(1f, diamondRadius * 0.0065f)
        edgePaint.color = withAlpha(
            diamondHighlight(),
            (18f + averageLuminance * 20f).roundToInt().coerceIn(18, 38)
        )

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

    /** Ceinture réactivée comme matière, sans lumière propre. */
    private fun drawGirdle(canvas: Canvas, frame: DiamondOpticalFrame) {
        val averageLuminance = frame.facets
            .filter { geometry[it.facetId].ring == DiamondRing.OUTER }
            .map { it.luminance }
            .average()
            .toFloat()
            .coerceIn(0.2f, 1f)

        edgePaint.strokeWidth = max(1.3f, diamondRadius * 0.025f)
        edgePaint.color = withAlpha(
            scaleColor(diamondTint(), 0.55f + averageLuminance * 0.35f),
            92
        )
        canvas.drawCircle(centerX, centerY, diamondRadius * 0.982f, edgePaint)

        edgePaint.strokeWidth = max(1f, diamondRadius * 0.007f)
        edgePaint.color = withAlpha(diamondHighlight(), 44)
        canvas.drawCircle(centerX, centerY, diamondRadius * 0.958f, edgePaint)
    }

    private fun segmentAngle(index: Int): Float = -90f + index * (360f / 16f)

    private fun point(radius: Float, degrees: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        return floatArrayOf(
            centerX + cos(rad).toFloat() * radius,
            centerY + sin(rad).toFloat() * radius
        )
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

    private fun scaleColor(color: Int, luminance: Float): Int {
        val l = luminance.coerceIn(0.20f, 1f)
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
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
