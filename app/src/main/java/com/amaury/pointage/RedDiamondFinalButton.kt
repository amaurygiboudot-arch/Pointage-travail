package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.Button
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.*

open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "Diamant 80 facettes"
        private const val FACETS = 16
        private const val MESH = 40
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
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = Path()
    private val facetEngine = DiamondFacetEngine { diamondPalette() }

    private var lightAngle = -55f
    private var pitch = 0f
    private var roll = 0f
    private var intensity = .78f
    private var night = false
    private var elevation = 45f
    private var lensStrength = .50f

    private var renderBitmap: Bitmap? = null
    private val meshVerts = FloatArray((MESH + 1) * (MESH + 1) * 2)

    open fun diamondPalette() = intArrayOf(
        Color.rgb(255, 50, 76), Color.rgb(214, 5, 35), Color.rgb(132, 0, 24),
        Color.rgb(255, 92, 118), Color.rgb(92, 0, 20), Color.rgb(238, 12, 48),
        Color.rgb(178, 0, 31), Color.rgb(255, 148, 164), Color.rgb(110, 0, 25),
        Color.rgb(245, 22, 56), Color.rgb(156, 0, 29), Color.rgb(255, 72, 102),
        Color.rgb(74, 0, 18), Color.rgb(226, 8, 42), Color.rgb(194, 0, 34),
        Color.rgb(255, 118, 140)
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
        releaseRenderBitmap()
        super.onDetachedFromWindow()
    }

    fun setDiamondLightAngle(angle: Float) {
        setNaturalLight(angle, pitch, roll, intensity, night, elevation)
    }

    fun setLensStrength(value: Float) {
        lensStrength = value.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    private fun setNaturalLight(a: Float, p: Float, r: Float, i: Float, n: Boolean, e: Float) {
        lightAngle = norm(a)
        pitch = p.coerceIn(-90f, 90f)
        roll = r.coerceIn(-90f, 90f)
        intensity = i.coerceIn(.12f, 1f)
        night = n
        elevation = e.coerceIn(-10f, 90f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val cx = w * .5f
        val cy = h * .5f
        val radius = min(w, h) * .455f
        val press = if (isPressed) .93f else 1f

        // 1. Ombre extérieure : hors du matériau, donc avant le diamant.
        drawShadow(canvas, cx, cy, radius * press)

        // 2. Rendu matériau : fond translucide léger puis exactement 80 facettes.
        val bitmap = ensureRenderBitmap(w, h)
        bitmap.eraseColor(Color.TRANSPARENT)
        val materialCanvas = Canvas(bitmap)
        outer.reset()
        outer.addCircle(cx, cy, radius, Path.Direction.CW)
        materialCanvas.save()
        materialCanvas.clipPath(outer)
        drawTranslucentBody(materialCanvas, cx, cy, radius)
        drawFacets(materialCanvas, cx, cy, radius)
        drawFacetStructure(materialCanvas, cx, cy, radius)
        materialCanvas.restore()

        // 3. Déformation bombée uniquement géométrique. Elle ne crée aucune lumière.
        buildConvexMesh(w.toFloat(), h.toFloat(), cx, cy, radius)

        // 4. Projection du matériau déjà calculé à travers le bombé.
        canvas.save()
        canvas.scale(press, press, cx, cy)
        canvas.clipPath(outer)
        canvas.drawBitmapMesh(bitmap, MESH, MESH, meshVerts, 0, null, 0, null)
        canvas.restore()

        // 5. Cerclage final, sans modifier l'état des facettes.
        drawGirdle(canvas, cx, cy, radius * press)
    }

    private fun drawTranslucentBody(c: Canvas, cx: Float, cy: Float, r: Float) {
        val tint = diamondTint()
        val dark = diamondDark()
        fill.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(alpha(tint, 46), alpha(tint, 34), alpha(dark, 52), alpha(dark, 68)),
            floatArrayOf(0f, .38f, .76f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun drawFacets(c: Canvas, cx: Float, cy: Float, r: Float) {
        val tableRadius = r * .28f
        val crownRadius = r * .63f
        val girdleRadius = r * .96f

        // 16 facettes centrales : ids 0..15
        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            drawFacet(
                c,
                center(cx, cy, pt(cx, cy, tableRadius, a0), pt(cx, cy, tableRadius, a1)),
                facetId = i,
                paletteIndex = i,
                azimuth = (a0 + a1) * .5f,
                ring = 0,
                energy = 1.16f
            )
        }

        // 32 facettes intermédiaires : ids 16..47
        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            val am = (a0 + a1) * .5f
            val i0 = pt(cx, cy, tableRadius, a0)
            val i1 = pt(cx, cy, tableRadius, a1)
            val m0 = pt(cx, cy, crownRadius, a0)
            val m1 = pt(cx, cy, crownRadius, a1)
            val mm = pt(cx, cy, crownRadius, am)
            drawFacet(c, poly(i0, m0, mm, i1), 16 + i * 2, i + 3, (a0 + am) * .5f, 1, .98f)
            drawFacet(c, poly(i1, mm, m1), 17 + i * 2, i + 9, (am + a1) * .5f, 1, .87f)
        }

        // 32 facettes périphériques : ids 48..79
        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            val am = (a0 + a1) * .5f
            val m0 = pt(cx, cy, crownRadius, a0)
            val m1 = pt(cx, cy, crownRadius, a1)
            val o0 = pt(cx, cy, girdleRadius, a0)
            val o1 = pt(cx, cy, girdleRadius, a1)
            val om = pt(cx, cy, girdleRadius, am)
            drawFacet(c, poly(m0, o0, om, m1), 48 + i * 2, i + 5, (a0 + am) * .5f, 2, .73f)
            drawFacet(c, poly(m1, om, o1), 49 + i * 2, i + 12, (am + a1) * .5f, 2, .61f)
        }
    }

    private fun drawFacet(
        c: Canvas,
        path: Path,
        facetId: Int,
        paletteIndex: Int,
        azimuth: Float,
        ring: Int,
        energy: Float
    ) {
        val baseTilt = when (ring) { 0 -> 11f; 1 -> 30f; else -> 47f }
        val tiltSignature = (((paletteIndex * 37 + ring * 53) % 17) - 8) * .46f
        val azimuthSignature = (((paletteIndex * 29 + ring * 11) % 13) - 6) * .42f
        val cutTilt = baseTilt + tiltSignature
        val facetAzimuth = norm(azimuth + azimuthSignature + roll * .10f)
        val facetTilt = (cutTilt + pitch * .010f).coerceIn(6f, 74f)

        // État permanent d'abord : couleur + transparence + historique de cette facette.
        val state = facetEngine.stateFor(
            facetId,
            paletteIndex,
            ring,
            azimuth,
            cutTilt,
            facetAzimuth,
            facetTilt
        )

        // Ensuite seulement : orientation actuelle -> lumière et reflet.
        val normal = normal3(azimuth + azimuthSignature, cutTilt, pitch, roll)
        val light = light3(lightAngle, elevation)
        val view = floatArrayOf(0f, 0f, 1f)
        val ndl = max(0f, dot(normal, light))
        val ndv = max(.001f, dot(normal, view))
        val halfVector = normalize3(floatArrayOf(light[0] + view[0], light[1] + view[1], light[2] + view[2]))
        val ndh = max(0f, dot(normal, halfVector))

        val r0 = ((N_AIR - N_DIAMOND) / (N_AIR + N_DIAMOND)).pow(2)
        val fresnel = r0 + (1 - r0) * (1 - ndv).pow(5)
        val specular = ndh.pow(if (night) 70f else 155f) * intensity
        val incidence = Math.toDegrees(acos(ndl.coerceIn(0f, 1f)).toDouble()).toFloat()
        val critical = Math.toDegrees(asin((N_AIR / N_DIAMOND).toDouble())).toFloat()
        val internalReflection = if (incidence > critical) {
            ((incidence - critical) / (90f - critical)).coerceIn(0f, 1f)
        } else 0f
        val transmission = (1 - fresnel) * (1 - internalReflection) * ndl
        val ringDepth = when (ring) { 0 -> 1.05f; 1 -> .83f; else -> .58f }
        val dynamic = energy * ringDepth * (ndl * .34f + transmission * .14f) + fresnel * .08f + internalReflection * .04f

        val targetLuminosity = (.72f + dynamic).coerceIn(.70f, 1.24f)
        val targetReflection = (specular * 1.10f + fresnel * .06f + internalReflection * .04f).coerceIn(0f, .30f)
        val lighting = facetEngine.update(
            state,
            targetLuminosity,
            targetReflection,
            facetAzimuth,
            facetTilt
        )

        // Enfin : rendu unique de la facette. Sa couleur de base et son alpha ne bougent jamais.
        val base = state.baseColor
        val rr = (Color.red(base) * lighting.luminosity).toInt().coerceIn(0, 255)
        val gg = (Color.green(base) * lighting.luminosity).toInt().coerceIn(0, 255)
        val bb = (Color.blue(base) * lighting.luminosity).toInt().coerceIn(0, 255)
        val litColor = Color.rgb(rr, gg, bb)
        val reflectedColor = mix(litColor, diamondHighlight(), lighting.reflection)
        val depth = when (ring) { 0 -> .72f; 1 -> .62f; else -> .52f }
        val deepColor = Color.rgb(
            (rr * depth).toInt().coerceIn(0, 255),
            (gg * depth).toInt().coerceIn(0, 255),
            (bb * depth).toInt().coerceIn(0, 255)
        )

        val gradientAngle = Math.toRadians((azimuth + 90f).toDouble())
        val gx = cos(gradientAngle).toFloat() * width * .42f
        val gy = sin(gradientAngle).toFloat() * height * .42f
        val alpha = state.referenceAlpha
        fill.shader = LinearGradient(
            width * .5f - gx,
            height * .5f - gy,
            width * .5f + gx,
            height * .5f + gy,
            alpha(reflectedColor, alpha),
            alpha(deepColor, (alpha * .88f).toInt()),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, fill)
        fill.shader = null
    }

    private fun drawFacetStructure(c: Canvas, cx: Float, cy: Float, r: Float) {
        val hi = diamondHighlight()
        edge.shader = null
        edge.strokeWidth = maxOf(1f, r * .008f)
        edge.color = alpha(hi, if (night) 30 else 48)

        for (i in 0 until FACETS) {
            val a = angle(i)
            val p1 = pt(cx, cy, r * .28f, a)
            val p2 = pt(cx, cy, r * .63f, a)
            val p3 = pt(cx, cy, r * .96f, a)
            c.drawLine(cx, cy, p1[0], p1[1], edge)
            c.drawLine(p1[0], p1[1], p2[0], p2[1], edge)
            c.drawLine(p2[0], p2[1], p3[0], p3[1], edge)
        }

        edge.color = alpha(hi, if (night) 26 else 42)
        c.drawCircle(cx, cy, r * .28f, edge)
        edge.color = alpha(hi, if (night) 20 else 34)
        c.drawCircle(cx, cy, r * .63f, edge)
    }

    /**
     * Déformation radiale non linéaire conservée à l'identique dans son principe.
     * Elle déforme la texture déjà rendue et n'ajoute aucun éclairage/reflet.
     */
    private fun buildConvexMesh(w: Float, h: Float, cx: Float, cy: Float, r: Float) {
        val lensRadius = r * .955f
        val strength = lensStrength.coerceIn(0f, 1f)
        val k = strength * .72f
        var index = 0

        for (iy in 0..MESH) {
            val sy = h * iy / MESH.toFloat()
            for (ix in 0..MESH) {
                val sx = w * ix / MESH.toFloat()
                val dx = sx - cx
                val dy = sy - cy
                val dist = sqrt(dx * dx + dy * dy)

                if (dist > .0001f && dist < lensRadius) {
                    val lensN = (dist / lensRadius).coerceIn(0f, 1f)
                    val diamondN = (dist / r).coerceIn(0f, 1f)
                    val dome = 4f * lensN * (1f - lensN)
                    val smoothDome = dome * dome * (3f - 2f * dome)

                    val angularCount: Int
                    val ringStart: Float
                    val ringEnd: Float
                    val ringGain: Float
                    when {
                        diamondN < .28f -> {
                            angularCount = 16
                            ringStart = 0f
                            ringEnd = .28f
                            ringGain = .92f
                        }
                        diamondN < .63f -> {
                            angularCount = 32
                            ringStart = .28f
                            ringEnd = .63f
                            ringGain = 1.05f
                        }
                        else -> {
                            angularCount = 32
                            ringStart = .63f
                            ringEnd = .96f
                            ringGain = .88f
                        }
                    }

                    val angleDeg = norm(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f)
                    val step = 360f / angularCount
                    val localA = ((angleDeg % step) / step).coerceIn(0f, 1f)
                    val angularArch = sin(Math.PI.toFloat() * localA).pow(2)
                    val angularFacet = .72f + .28f * angularArch
                    val localR = ((diamondN - ringStart) / (ringEnd - ringStart)).coerceIn(0f, 1f)
                    val radialArch = sin(Math.PI.toFloat() * localR).pow(2)
                    val radialFacet = .78f + .22f * radialArch
                    val facetShape = angularFacet * radialFacet * ringGain
                    val radialScale = 1f + k * .34f * smoothDome * facetShape

                    meshVerts[index++] = cx + dx * radialScale
                    meshVerts[index++] = cy + dy * radialScale
                } else {
                    meshVerts[index++] = sx
                    meshVerts[index++] = sy
                }
            }
        }
    }

    private fun drawShadow(c: Canvas, cx: Float, cy: Float, r: Float) {
        shadow.shader = RadialGradient(
            cx,
            cy + r * .08f,
            r * 1.16f,
            intArrayOf(Color.TRANSPARENT, Color.argb(72, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(.67f, .88f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy + r * .08f, r * 1.16f, shadow)
        shadow.shader = null
    }

    private fun drawGirdle(c: Canvas, cx: Float, cy: Float, r: Float) {
        val dark = diamondDark()
        val tint = diamondTint()
        val hi = diamondHighlight()
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = maxOf(2.2f, r * .052f)
        edge.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(alpha(dark, 235), alpha(tint, 214), alpha(hi, 178), alpha(dark, 235), alpha(tint, 210), alpha(dark, 235)),
            null
        )
        c.drawCircle(cx, cy, r * .982f, edge)
        edge.shader = null
        edge.strokeWidth = maxOf(1f, r * .011f)
        edge.color = alpha(Color.BLACK, 128)
        c.drawCircle(cx, cy, r * 1.012f, edge)
    }

    private fun ensureRenderBitmap(w: Int, h: Int): Bitmap {
        val current = renderBitmap
        if (current == null || current.width != w || current.height != h || current.isRecycled) {
            releaseRenderBitmap()
            renderBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        return renderBitmap!!
    }

    private fun releaseRenderBitmap() {
        renderBitmap?.let { if (!it.isRecycled) it.recycle() }
        renderBitmap = null
    }

    private fun normal3(azimuth: Float, tilt: Float, p: Float, r: Float): FloatArray {
        val az = Math.toRadians((azimuth + r * .10f).toDouble())
        val tr = Math.toRadians((tilt + p * .010f).coerceIn(6f, 74f).toDouble())
        val s = sin(tr).toFloat()
        return normalize3(floatArrayOf(cos(az).toFloat() * s, sin(az).toFloat() * s, cos(tr).toFloat()))
    }

    private fun light3(azimuth: Float, elev: Float): FloatArray {
        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elev.coerceIn(-5f, 90f).toDouble())
        val ce = cos(el).toFloat()
        return normalize3(floatArrayOf(cos(az).toFloat() * ce, sin(az).toFloat() * ce, sin(el).toFloat()))
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize3(v: FloatArray): FloatArray {
        val length = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-8f))
        return floatArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    private fun angle(i: Int) = -90f + i * (360f / FACETS)
    private fun norm(v: Float) = ((v % 360f) + 360f) % 360f

    private fun pt(cx: Float, cy: Float, r: Float, degrees: Float): FloatArray {
        val q = Math.toRadians(degrees.toDouble())
        return floatArrayOf(cx + cos(q).toFloat() * r, cy + sin(q).toFloat() * r)
    }

    private fun poly(vararg points: FloatArray) = Path().apply {
        moveTo(points[0][0], points[0][1])
        for (i in 1 until points.size) lineTo(points[i][0], points[i][1])
        close()
    }

    private fun center(cx: Float, cy: Float, vararg points: FloatArray) = Path().apply {
        moveTo(cx, cy)
        points.forEach { lineTo(it[0], it[1]) }
        close()
    }

    private fun alpha(color: Int, alpha: Int) = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun mix(a: Int, b: Int, t: Float): Int {
        val q = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * q).toInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * q).toInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * q).toInt().coerceIn(0, 255)
        )
    }
}
