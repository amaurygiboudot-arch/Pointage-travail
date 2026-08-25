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
        const val RENDER_NAME = "Diamant rouge final"
        private const val FACETS = 16
        private const val FACET_COUNT = 80
        private const val MESH = 40
        private const val N_AIR = 1.000293f
        private const val N_DIAMOND = 2.417f
        private const val MIN_LIGHT_ANGLE_DELTA = 1.0f
        private const val MIN_TILT_DELTA = 1.0f
        private const val MIN_INTENSITY_DELTA = .015f
        private const val MIN_ELEVATION_DELTA = .75f
        private const val BASE_TRANSLUCENCY_ALPHA = 77 // ~70 % transparent / 30 % opaque
        private val live = Collections.newSetFromMap(WeakHashMap<RedDiamondFinalButton, Boolean>())

        fun updateGlobalNaturalLight(
            angle: Float,
            pitch: Float,
            roll: Float,
            intensity: Float,
            night: Boolean,
            elevation: Float
        ) {
            live.forEach {
                it.setNaturalLight(angle, pitch, roll, intensity, night, elevation)
            }
        }
    }

    private data class FacetState(
        val baseColor: Int,
        val referenceAlpha: Int,
        var luminosity: Float,
        var reflection: Float,
        var lastAzimuth: Float,
        var lastTilt: Float
    )

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

    private var lensStrength = .50f

    private var renderBitmap: Bitmap? = null
    private var renderDirty = true
    private var meshDirty = true
    private var meshVerts = FloatArray((MESH + 1) * (MESH + 1) * 2)
    private val facetStates = arrayOfNulls<FacetState>(FACET_COUNT)

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

    private fun releaseRenderBitmap() {
        renderBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        renderBitmap = null
        renderDirty = true
        meshDirty = true
    }

    fun setDiamondLightAngle(angle: Float) {
        setNaturalLight(angle, pitch, roll, intensity, night, elevation)
    }

    fun setLensStrength(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (abs(next - lensStrength) < .005f) return
        lensStrength = next
        meshDirty = true
        postInvalidateOnAnimation()
    }

    private fun setNaturalLight(a: Float, p: Float, r: Float, i: Float, n: Boolean, e: Float) {
        val nextAngle = norm(a)
        val nextPitch = p.coerceIn(-90f, 90f)
        val nextRoll = r.coerceIn(-90f, 90f)
        val nextIntensity = i.coerceIn(.12f, 1f)
        val nextElevation = e.coerceIn(-10f, 90f)

        val changed =
            abs(shortestDelta(lightAngle, nextAngle)) >= MIN_LIGHT_ANGLE_DELTA ||
                abs(nextPitch - pitch) >= MIN_TILT_DELTA ||
                abs(nextRoll - roll) >= MIN_TILT_DELTA ||
                abs(nextIntensity - intensity) >= MIN_INTENSITY_DELTA ||
                abs(nextElevation - elevation) >= MIN_ELEVATION_DELTA ||
                n != night
        if (!changed) return

        lightAngle = nextAngle
        pitch = nextPitch
        roll = nextRoll
        intensity = nextIntensity
        night = n
        elevation = nextElevation
        renderDirty = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val cx = w * .5f
        val cy = h * .5f
        val r = min(w, h) * .455f
        val press = if (isPressed) .93f else 1f

        drop(c, cx, cy, r * press)

        val bitmap = ensureRenderBitmap(w, h)
        outer.reset()
        outer.addCircle(cx, cy, r, Path.Direction.CW)

        if (renderDirty) {
            bitmap.eraseColor(Color.TRANSPARENT)
            val bc = Canvas(bitmap)
            bc.save()
            bc.clipPath(outer)
            glass(bc, cx, cy, r)
            pavilion(bc, cx, cy, r)
            facets(bc, cx, cy, r)
            table(bc, cx, cy, r)
            refraction(bc, cx, cy, r)
            edges(bc, cx, cy, r)
            glints(bc, cx, cy, r)
            bc.restore()
            renderDirty = false
        }

        if (meshDirty) {
            buildConvexMesh(w.toFloat(), h.toFloat(), cx, cy, r)
            meshDirty = false
        }

        c.save()
        c.scale(press, press, cx, cy)
        c.clipPath(outer)
        c.drawBitmapMesh(bitmap, MESH, MESH, meshVerts, 0, null, 0, null)
        c.restore()

        girdle(c, cx, cy, r * press)
    }

    private fun ensureRenderBitmap(w: Int, h: Int): Bitmap {
        val current = renderBitmap
        if (current == null || current.width != w || current.height != h || current.isRecycled) {
            current?.let { if (!it.isRecycled) it.recycle() }
            renderBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            renderDirty = true
            meshDirty = true
        }
        return renderBitmap!!
    }

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

                if (dist > 0.0001f && dist < lensRadius) {
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

    private fun drop(c: Canvas, cx: Float, cy: Float, r: Float) {
        shadow.shader = RadialGradient(
            cx, cy + r * .08f, r * 1.16f,
            intArrayOf(Color.TRANSPARENT, Color.argb(88, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(.67f, .88f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy + r * .08f, r * 1.16f, shadow)
        shadow.shader = null
    }

    private fun glass(c: Canvas, cx: Float, cy: Float, r: Float) {
        // Couche de corps volontairement très légère : elle colore le cristal sans
        // masquer le fond de l'application. La translucidité visible vient des facettes.
        val q = Math.toRadians(lightAngle.toDouble())
        val lx = cx + cos(q).toFloat() * r * .24f
        val ly = cy + sin(q).toFloat() * r * .24f
        val t = diamondTint()
        val d = diamondDark()
        fill.shader = RadialGradient(
            lx, ly, r * 1.28f,
            intArrayOf(
                alpha(lighten(t, .26f), 18), alpha(t, 22), alpha(d, 28),
                Color.argb(34, Color.red(d) / 7, Color.green(d) / 7, Color.blue(d) / 7)
            ),
            floatArrayOf(0f, .34f, .72f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun pavilion(c: Canvas, cx: Float, cy: Float, r: Float) {
        val d = diamondDark()
        fill.shader = RadialGradient(
            cx - roll * .012f, cy + pitch * .008f, r,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, alpha(d, 18), alpha(Color.BLACK, 42)),
            floatArrayOf(0f, .43f, .73f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
    }

    private fun facets(c: Canvas, cx: Float, cy: Float, r: Float) {
        val tr = r * .28f
        val cr = r * .63f
        val gr = r * .96f

        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            facet(c, center(cx, cy, pt(cx, cy, tr, a0), pt(cx, cy, tr, a1)), i, i, (a0 + a1) * .5f, 0, 1.16f)
        }

        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            val am = (a0 + a1) * .5f
            val i0 = pt(cx, cy, tr, a0)
            val i1 = pt(cx, cy, tr, a1)
            val m0 = pt(cx, cy, cr, a0)
            val m1 = pt(cx, cy, cr, a1)
            val mm = pt(cx, cy, cr, am)
            facet(c, poly(i0, m0, mm, i1), 16 + i * 2, i + 3, (a0 + am) * .5f, 1, .98f)
            facet(c, poly(i1, mm, m1), 17 + i * 2, i + 9, (am + a1) * .5f, 1, .87f)
        }

        for (i in 0 until FACETS) {
            val a0 = angle(i)
            val a1 = angle(i + 1)
            val am = (a0 + a1) * .5f
            val m0 = pt(cx, cy, cr, a0)
            val m1 = pt(cx, cy, cr, a1)
            val o0 = pt(cx, cy, gr, a0)
            val o1 = pt(cx, cy, gr, a1)
            val om = pt(cx, cy, gr, am)
            facet(c, poly(m0, o0, om, m1), 48 + i * 2, i + 5, (a0 + am) * .5f, 2, .73f)
            facet(c, poly(m1, om, o1), 49 + i * 2, i + 12, (am + a1) * .5f, 2, .61f)
        }
    }

    private fun facet(c: Canvas, path: Path, facetId: Int, paletteIndex: Int, az: Float, ring: Int, energy: Float) {
        val bt = when (ring) { 0 -> 11f; 1 -> 30f; else -> 47f }
        val mt = (((paletteIndex * 37 + ring * 53) % 17) - 8) * .46f
        val ma = (((paletteIndex * 29 + ring * 11) % 13) - 6) * .42f
        val cutTilt = bt + mt
        val facetAzimuth = norm(az + ma + roll * .10f)
        val facetTilt = (cutTilt + pitch * .010f).coerceIn(6f, 74f)
        val state = facetState(facetId, paletteIndex, ring, az, cutTilt, facetAzimuth, facetTilt)

        val n = normal3(az + ma, cutTilt, pitch, roll)
        val l = light3(lightAngle, elevation)
        val v = floatArrayOf(0f, 0f, 1f)
        val ndl = max(0f, dot(n, l))
        val ndv = max(.001f, dot(n, v))
        val hv = normalize3(floatArrayOf(l[0] + v[0], l[1] + v[1], l[2] + v[2]))
        val ndh = max(0f, dot(n, hv))
        val r0 = ((N_AIR - N_DIAMOND) / (N_AIR + N_DIAMOND)).pow(2)
        val fres = r0 + (1 - r0) * (1 - ndv).pow(5)
        val spec = ndh.pow(if (night) 70f else 155f) * intensity
        val inc = Math.toDegrees(acos(ndl.coerceIn(0f, 1f)).toDouble()).toFloat()
        val crit = Math.toDegrees(asin((N_AIR / N_DIAMOND).toDouble())).toFloat()
        val tir = if (inc > crit) ((inc - crit) / (90f - crit)).coerceIn(0f, 1f) else 0f
        val trans = (1 - fres) * (1 - tir) * ndl
        val rd = when (ring) { 0 -> 1.05f; 1 -> .83f; else -> .58f }
        val dynamic = energy * rd * (ndl * .34f + trans * .14f) + fres * .08f + tir * .04f
        val targetLuminosity = (.72f + dynamic).coerceIn(.70f, 1.24f)
        val targetReflection = (spec * 1.18f + fres * .07f + tir * .05f).coerceIn(0f, .34f)

        state.luminosity += targetLuminosity - state.luminosity
        state.reflection += targetReflection - state.reflection
        state.lastAzimuth = facetAzimuth
        state.lastTilt = facetTilt

        val bright = state.luminosity
        val base = state.baseColor
        val hi = diamondHighlight()
        val rr = (Color.red(base) * bright).toInt().coerceIn(0, 255)
        val gg = (Color.green(base) * bright).toInt().coerceIn(0, 255)
        val bb = (Color.blue(base) * bright).toInt().coerceIn(0, 255)
        val top = mix(Color.rgb(rr, gg, bb), hi, state.reflection)
        val df = when (ring) { 0 -> .66f; 1 -> .54f; else -> .42f }
        val deep = Color.rgb(
            (rr * df).toInt().coerceIn(0, 255),
            (gg * df).toInt().coerceIn(0, 255),
            (bb * df).toInt().coerceIn(0, 255)
        )

        val a = dynamicFacetAlpha(state.referenceAlpha, facetAzimuth, ndv)
        val ga = Math.toRadians((az + 90).toDouble())
        val gx = cos(ga).toFloat() * width * .42f
        val gy = sin(ga).toFloat() * height * .42f
        fill.shader = LinearGradient(
            width * .5f - gx, height * .5f - gy,
            width * .5f + gx, height * .5f + gy,
            alpha(top, a), alpha(deep, (a * .90f).toInt()), Shader.TileMode.CLAMP
        )
        c.drawPath(path, fill)
        fill.shader = null
        if (spec > .62f) {
            fill.color = alpha(Color.WHITE, ((spec - .62f) / .38f * 48).toInt().coerceIn(0, 48))
            c.drawPath(path, fill)
        }
        fire(c, path, facetId, ring, ndh, trans, tir)
    }

    private fun facetState(
        facetId: Int,
        paletteIndex: Int,
        ring: Int,
        az: Float,
        cutTilt: Float,
        currentAzimuth: Float,
        currentTilt: Float
    ): FacetState {
        val existing = facetStates[facetId]
        if (existing != null) return existing
        val palette = diamondPalette()
        return FacetState(
            baseColor = palette[paletteIndex % palette.size],
            referenceAlpha = facetReferenceAlpha(facetId, ring, az, cutTilt),
            luminosity = 1f,
            reflection = 0f,
            lastAzimuth = currentAzimuth,
            lastTilt = currentTilt
        ).also { facetStates[facetId] = it }
    }

    private fun facetReferenceAlpha(facetId: Int, ring: Int, az: Float, cutTilt: Float): Int {
        // 70 % de translucidité comme référence (~30 % d'opacité = alpha 77),
        // avec une petite signature stable propre à chaque coupe/facette.
        val azimuthShape = ((sin(Math.toRadians((az + 37f).toDouble())).toFloat() + 1f) * 5f).toInt() - 5
        val cutShape = (((cutTilt.coerceIn(6f, 74f) - 40f) / 34f) * 8f).roundToInt()
        val signature = ((facetId * 13 + ring * 17) % 9) - 4
        val ringShift = when (ring) { 0 -> -3; 1 -> 0; else -> 4 }
        return (BASE_TRANSLUCENCY_ALPHA + azimuthShape + cutShape + signature + ringShift).coerceIn(55, 100)
    }

    private fun dynamicFacetAlpha(referenceAlpha: Int, facetAzimuth: Float, viewFacing: Float): Int {
        val tiltMagnitude = (hypot(pitch.toDouble(), roll.toDouble()).toFloat() / 70f).coerceIn(0f, 1f)
        if (tiltMagnitude < .01f) return referenceAlpha

        val tiltDirection = norm(
            Math.toDegrees(atan2(roll.toDouble(), -pitch.toDouble())).toFloat()
        )
        val delta = Math.toRadians(shortestDelta(tiltDirection, facetAzimuth).toDouble())
        val alignment = cos(delta).toFloat()

        // Alpha faible = plus transparent. L'orientation du téléphone fait varier
        // les facettes autour de la référence 70 %, sans jamais les rendre opaques.
        val facingTransparency = ((viewFacing.coerceIn(0f, 1f) - .5f) * 20f)
        val orientationShift = alignment * tiltMagnitude * 22f
        return (referenceAlpha - facingTransparency - orientationShift)
            .roundToInt()
            .coerceIn(38, 118)
    }

    private fun fire(c: Canvas, path: Path, index: Int, ring: Int, align: Float, trans: Float, tir: Float) {
        if (night) return
        val th = .90f + ring * .016f
        val gate = ((align - th) / (1 - th)).coerceIn(0f, 1f)
        if (((index * 17 + ring * 7) % 13) > 3 || gate <= 0) return
        val p = (gate.pow(2.1f) * trans * intensity * (1 - .30f * tir)).coerceIn(0f, .18f)
        if (p < .02f) return
        val q = Math.toRadians((lightAngle + index * 3.2f).toDouble())
        val dx = cos(q).toFloat() * width * .012f
        val dy = sin(q).toFloat() * height * .012f
        fill.shader = LinearGradient(
            width * .5f - dx, height * .5f - dy,
            width * .5f + dx, height * .5f + dy,
            intArrayOf(
                alpha(Color.rgb(255, 134, 58), (80 * p).toInt()),
                Color.TRANSPARENT,
                alpha(Color.rgb(80, 150, 255), (90 * p).toInt())
            ),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(path, fill)
        fill.shader = null
    }

    private fun table(c: Canvas, cx: Float, cy: Float, r: Float) {
        val tr = r * .285f
        val q = Math.toRadians(lightAngle.toDouble())
        val hx = cx + cos(q).toFloat() * tr * .34f
        val hy = cy + sin(q).toFloat() * tr * .34f
        val hi = diamondHighlight()
        fill.shader = RadialGradient(
            hx, hy, tr * 1.12f,
            intArrayOf(alpha(hi, if (night) 22 else 38), Color.TRANSPARENT, alpha(Color.BLACK, 16)),
            floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, tr, fill)
        fill.shader = null
        edge.strokeWidth = maxOf(1f, r * .014f)
        edge.color = alpha(hi, if (night) 30 else 54)
        c.drawCircle(cx, cy, tr, edge)
    }

    private fun refraction(c: Canvas, cx: Float, cy: Float, r: Float) {
        val q = Math.toRadians(lightAngle.toDouble())
        val ux = cos(q).toFloat()
        val uy = sin(q).toFloat()
        val hi = diamondHighlight()
        val t = diamondTint()
        fill.shader = LinearGradient(
            cx - ux * r * .72f, cy - uy * r * .72f,
            cx + ux * r * .72f, cy + uy * r * .72f,
            intArrayOf(Color.TRANSPARENT, alpha(t, 12), alpha(hi, if (night) 20 else 40), alpha(t, 14), Color.TRANSPARENT),
            floatArrayOf(0f, .32f, .50f, .68f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * .78f, fill)
        fill.shader = null
    }

    private fun edges(c: Canvas, cx: Float, cy: Float, r: Float) {
        val hi = diamondHighlight()
        edge.strokeWidth = maxOf(1f, r * .009f)
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
        edge.alpha = 46
        c.drawCircle(cx, cy, r * .28f, edge)
        edge.alpha = 34
        c.drawCircle(cx, cy, r * .63f, edge)
        edge.alpha = 255
    }

    private fun glints(c: Canvas, cx: Float, cy: Float, r: Float) {
        val q = Math.toRadians(lightAngle.toDouble())
        val ux = cos(q).toFloat()
        val uy = sin(q).toFloat()
        val hi = diamondHighlight()
        val ef = ((elevation + 5) / 70).coerceIn(.15f, 1f)
        val power = intensity * ef * (if (night) .30f else 1f)
        val x = cx + ux * r * (.43f + roll * .0010f).coerceIn(.24f, .65f)
        val y = cy + uy * r * (.43f - pitch * .0004f).coerceIn(.30f, .56f)
        glow.shader = RadialGradient(
            x, y, r * .17f,
            intArrayOf(
                alpha(Color.WHITE, (130 * power).toInt().coerceIn(0, 130)),
                alpha(hi, (64 * power).toInt().coerceIn(0, 64)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .10f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r * .17f, glow)
        glow.shader = null
        if (!night && power > .60f) {
            edge.strokeWidth = maxOf(1f, r * .010f)
            edge.color = alpha(Color.WHITE, (110 * power).toInt().coerceIn(0, 110))
            val len = r * (.035f + .08f * power)
            c.drawLine(x - len, y, x + len, y, edge)
            c.drawLine(x, y - len, x, y + len, edge)
        }
    }

    private fun girdle(c: Canvas, cx: Float, cy: Float, r: Float) {
        val d = diamondDark()
        val t = diamondTint()
        val hi = diamondHighlight()
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = maxOf(2.2f, r * .060f)
        edge.shader = SweepGradient(
            cx, cy,
            intArrayOf(alpha(d, 210), alpha(t, 170), alpha(hi, 180), alpha(d, 210), alpha(t, 165), alpha(hi, 175), alpha(d, 210)),
            null
        )
        c.drawCircle(cx, cy, r * .982f, edge)
        edge.shader = null
        edge.strokeWidth = maxOf(1f, r * .015f)
        edge.color = alpha(hi, if (night) 60 else 110)
        c.drawCircle(cx, cy, r * .952f, edge)
        edge.strokeWidth = maxOf(1f, r * .012f)
        edge.color = alpha(Color.BLACK, 110)
        c.drawCircle(cx, cy, r * 1.012f, edge)
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
        val l = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-8f))
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private fun angle(i: Int) = -90f + i * (360f / FACETS)
    private fun norm(v: Float) = ((v % 360) + 360) % 360
    private fun shortestDelta(from: Float, to: Float) = ((to - from + 540f) % 360f) - 180f

    private fun pt(cx: Float, cy: Float, r: Float, d: Float): FloatArray {
        val q = Math.toRadians(d.toDouble())
        return floatArrayOf(cx + cos(q).toFloat() * r, cy + sin(q).toFloat() * r)
    }

    private fun poly(vararg p: FloatArray) = Path().apply {
        moveTo(p[0][0], p[0][1])
        for (i in 1 until p.size) lineTo(p[i][0], p[i][1])
        close()
    }

    private fun center(cx: Float, cy: Float, vararg p: FloatArray) = Path().apply {
        moveTo(cx, cy)
        p.forEach { lineTo(it[0], it[1]) }
        close()
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun lighten(c: Int, t: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt().coerceIn(0, 255),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt().coerceIn(0, 255),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt().coerceIn(0, 255)
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
