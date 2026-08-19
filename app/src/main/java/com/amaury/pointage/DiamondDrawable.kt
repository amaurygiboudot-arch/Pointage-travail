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
 * Cristal diamant réaliste et très transparent.
 * Aucun grand aplat de facette : le relief est suggéré par les arêtes,
 * les caustiques et les éclats qui réagissent indépendamment à la lumière.
 */
class DiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val tint: Int = Color.parseColor("#DDF6FF")
) : Drawable() {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    fun setLightAngle(angle: Float) {
        val normalized = ((angle % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.4f) return
        lightAngle = normalized
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val next = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
        if (next == pressed) return false
        pressed = next
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())

        val radius = 19f * density
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clip)

        drawCrystalVolume(canvas)
        drawFacetEdges(canvas)
        drawCaustics(canvas)
        drawPrismaticFlashes(canvas)
        drawMovingSparkles(canvas)

        canvas.restoreToCount(save)
        drawPolishedEdge(canvas, clip, radius)
    }

    /** Très peu de matière : le fond de l'application reste réellement visible. */
    private fun drawCrystalVolume(canvas: Canvas) {
        val ice = if (dark) Color.argb(26, 126, 193, 225) else Color.argb(17, 205, 239, 252)
        val white = if (dark) Color.argb(14, 255, 255, 255) else Color.argb(10, 255, 255, 255)
        val shadow = if (dark) Color.argb(30, 8, 22, 35) else Color.argb(10, 87, 145, 175)
        bodyPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(white, ice, Color.TRANSPARENT, white, shadow, ice),
            floatArrayOf(0f, .18f, .36f, .55f, .78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, bodyPaint)
        bodyPaint.shader = null
    }

    /**
     * Les facettes sont seulement suggérées par de fines arêtes translucides.
     * Cela évite l'effet vitrail / triangles opaques.
     */
    private fun drawFacetEdges(canvas: Canvas) {
        val l = rect.left; val r = rect.right; val t = rect.top; val b = rect.bottom
        val w = rect.width(); val h = rect.height(); val cx = rect.centerX(); val cy = rect.centerY()

        val nodes = arrayOf(
            floatArrayOf(l + w*.08f, t + h*.18f),
            floatArrayOf(l + w*.23f, t + h*.08f),
            floatArrayOf(l + w*.39f, t + h*.22f),
            floatArrayOf(l + w*.56f, t + h*.10f),
            floatArrayOf(l + w*.74f, t + h*.20f),
            floatArrayOf(l + w*.91f, t + h*.13f),
            floatArrayOf(l + w*.15f, cy),
            floatArrayOf(l + w*.31f, cy - h*.05f),
            floatArrayOf(cx, cy + h*.04f),
            floatArrayOf(l + w*.68f, cy - h*.04f),
            floatArrayOf(l + w*.86f, cy + h*.03f),
            floatArrayOf(l + w*.11f, b - h*.16f),
            floatArrayOf(l + w*.28f, b - h*.08f),
            floatArrayOf(l + w*.46f, b - h*.20f),
            floatArrayOf(l + w*.64f, b - h*.08f),
            floatArrayOf(l + w*.82f, b - h*.17f)
        )

        val links = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5,
            0 to 6, 1 to 7, 2 to 7, 2 to 8, 3 to 8, 4 to 9, 5 to 10,
            6 to 7, 7 to 8, 8 to 9, 9 to 10,
            6 to 11, 7 to 12, 8 to 13, 9 to 14, 10 to 15,
            11 to 12, 12 to 13, 13 to 14, 14 to 15,
            1 to 6, 3 to 9, 5 to 9, 7 to 13, 9 to 13
        )

        links.forEachIndexed { index, pair ->
            val a = nodes[pair.first]; val c = nodes[pair.second]
            val pseudoNormal = (index * 37f + 11f) % 360f
            val facing = ((cos(Math.toRadians(shortestDelta(pseudoNormal, lightAngle).toDouble())) + 1.0) * .5).toFloat()
            val alpha = ((if (dark) 16 else 12) + facing * (if (pressed) 28 else 52)).toInt()
            linePaint.color = Color.argb(alpha.coerceIn(8, 72), 240, 251, 255)
            linePaint.strokeWidth = (if (facing > .72f) .82f else .48f) * density
            canvas.drawLine(a[0], a[1], c[0], c[1], linePaint)
        }
    }

    /** Une bande de lumière réfractée traverse le cristal en suivant la source. */
    private fun drawCaustics(canvas: Canvas) {
        val diagonal = sqrt(rect.width()*rect.width() + rect.height()*rect.height())
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad) * diagonal).toFloat()
        val dy = (sin(rad) * diagonal).toFloat()
        val cx = rect.centerX(); val cy = rect.centerY()

        lightPaint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(if (pressed) 12 else 24, 168, 222, 255),
                Color.argb(if (pressed) 28 else 58, 255, 255, 255),
                Color.argb(if (pressed) 8 else 20, 201, 179, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .39f, .50f, .58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, lightPaint)
        lightPaint.shader = null
    }

    /** Très légère dispersion spectrale, uniquement aux endroits les plus brillants. */
    private fun drawPrismaticFlashes(canvas: Canvas) {
        val w = rect.width(); val h = rect.height()
        val rad = Math.toRadians(lightAngle.toDouble())
        val baseX = rect.centerX() + (cos(rad) * w * .24f).toFloat()
        val baseY = rect.centerY() + (sin(rad) * h * .20f).toFloat()

        val colors = intArrayOf(
            Color.argb(if (pressed) 12 else 26, 120, 205, 255),
            Color.argb(if (pressed) 10 else 22, 192, 151, 255),
            Color.argb(if (pressed) 8 else 18, 255, 203, 167)
        )
        val offsets = floatArrayOf(-2.1f, 0f, 2.1f)
        for (i in offsets.indices) {
            lightPaint.shader = RadialGradient(
                baseX + offsets[i] * density,
                baseY + offsets[(i + 1) % offsets.size] * density * .45f,
                h * .38f,
                intArrayOf(colors[i], Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, lightPaint)
            lightPaint.shader = null
        }
    }

    /** Plusieurs éclats se déplacent indépendamment : aucun reflet n'est figé dans l'image. */
    private fun drawMovingSparkles(canvas: Canvas) {
        val w = rect.width(); val h = rect.height()
        val specs = arrayOf(
            floatArrayOf(.17f,.24f,.036f, 12f,1.00f),
            floatArrayOf(.35f,.70f,.025f, 79f,1.13f),
            floatArrayOf(.53f,.22f,.030f,151f,0.91f),
            floatArrayOf(.69f,.62f,.040f,224f,1.22f),
            floatArrayOf(.84f,.31f,.022f,302f,0.83f)
        )

        specs.forEachIndexed { index, s ->
            val phase = Math.toRadians((lightAngle * s[4] + s[3]).toDouble())
            val x = rect.left + w*s[0] + (cos(phase)*w*.026f).toFloat()
            val y = rect.top + h*s[1] + (sin(phase)*h*.045f).toFloat()
            val radius = h * s[2] * 2.7f
            val facing = ((cos(Math.toRadians((lightAngle - s[3]).toDouble())) + 1.0)*.5).toFloat()
            val alpha = ((if (pressed) 65 else 115) + facing*(if (pressed) 65 else 125)).toInt().coerceIn(40,240)

            lightPaint.shader = RadialGradient(
                x, y, radius,
                intArrayOf(
                    Color.argb(alpha,255,255,255),
                    Color.argb((alpha*.25f).toInt(),185,230,255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f,.16f,1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x,y,radius,lightPaint)
            lightPaint.shader = null

            if (facing > .70f) {
                linePaint.color = Color.argb((alpha*.88f).toInt(),255,255,255)
                linePaint.strokeWidth = .68f*density
                val longRay = (4.5f + facing*4f)*density
                val shortRay = longRay*.48f
                canvas.drawLine(x-longRay,y,x+longRay,y,linePaint)
                canvas.drawLine(x,y-shortRay,x,y+shortRay,linePaint)
                if (index % 2 == 0) {
                    canvas.save()
                    canvas.rotate(45f,x,y)
                    canvas.drawLine(x-shortRay,y,x+shortRay,y,linePaint)
                    canvas.restore()
                }
            }
        }
    }

    private fun drawPolishedEdge(canvas: Canvas, outline: Path, radius: Float) {
        edgePaint.strokeWidth = .82f*density
        edgePaint.color = Color.argb(if (dark) 96 else 82, 220, 246, 255)
        canvas.drawPath(outline,edgePaint)

        val inset = 2.0f*density
        val inner = RectF(rect.left+inset,rect.top+inset,rect.right-inset,rect.bottom-inset)
        edgePaint.strokeWidth = .42f*density
        edgePaint.color = Color.argb(if (pressed) 54 else 96,255,255,255)
        canvas.drawRoundRect(inner,(radius-inset).coerceAtLeast(2f),(radius-inset).coerceAtLeast(2f),edgePaint)
    }

    private fun shortestDelta(a: Float,b: Float): Float = ((b-a+540f)%360f)-180f

    override fun setAlpha(alpha: Int) {
        bodyPaint.alpha=alpha; linePaint.alpha=alpha; lightPaint.alpha=alpha; edgePaint.alpha=alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bodyPaint.colorFilter=colorFilter; linePaint.colorFilter=colorFilter
        lightPaint.colorFilter=colorFilter; edgePaint.colorFilter=colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
