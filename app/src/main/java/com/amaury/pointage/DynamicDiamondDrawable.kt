package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicInteger

/** Fond diamant avec une taille pseudo-aleatoire stable propre a chaque bouton. */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float
) : Drawable() {
    companion object { private val nextSeed = AtomicInteger(1) }

    private val seed = nextSeed.getAndIncrement()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    private fun variant(index: Int, min: Float, max: Float): Float {
        var x = seed * 1103515245 + 12345 + index * 1013904223
        x = x xor (x ushr 16)
        val unit = (x and 0x7fffffff) / 2147483647f
        return min + (max - min) * unit
    }

    fun setLightAngle(degrees: Float) {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.8f) return
        lightAngle = normalized
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true
    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = state.any { it == android.R.attr.state_pressed || it == android.R.attr.state_focused }
        if (nowPressed == pressed) return false
        pressed = nowPressed
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val radius = variant(1, 11f, 20f) * density
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)

        val cx = rect.left + rect.width() * variant(2, 0.42f, 0.58f)
        val cy = rect.top + rect.height() * variant(3, 0.40f, 0.60f)
        val diagonal = sqrt(rect.width()*rect.width()+rect.height()*rect.height()) * 0.78f
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = (cos(rad)*diagonal).toFloat(); val dy = (sin(rad)*diagonal).toFloat()

        // Base volontairement jamais totalement noire/blanche : elle joue le rôle
        // d'une lumière ambiante permanente sur la pierre.
        val colors = if (dark) {
            if (pressed) intArrayOf(
                Color.parseColor("#11100D"),
                Color.parseColor("#4A3D1E"),
                Color.parseColor("#211B10"),
                Color.parseColor("#0B0B0A")
            ) else intArrayOf(
                Color.parseColor("#14130F"),
                Color.parseColor("#735D2A"),
                Color.parseColor("#2A2112"),
                Color.parseColor("#0D0D0C")
            )
        } else {
            if (pressed) intArrayOf(
                Color.parseColor("#B6A58B"),
                Color.WHITE,
                Color.parseColor("#F0E5D3"),
                Color.parseColor("#A69272")
            ) else intArrayOf(
                Color.parseColor("#A99370"),
                Color.WHITE,
                Color.parseColor("#FFF8EA"),
                Color.parseColor("#947A57")
            )
        }
        fillPaint.shader = LinearGradient(cx-dx,cy-dy,cx+dx,cy+dy,colors,floatArrayOf(0f,.28f,.62f,1f),Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,radius,radius,fillPaint)

        val leftMidX = rect.left + rect.width() * variant(4, .18f, .38f)
        val rightMidX = rect.right - rect.width() * variant(5, .16f, .36f)
        val topX = rect.left + rect.width() * variant(6, .35f, .65f)
        val bottomX = rect.left + rect.width() * variant(7, .35f, .65f)
        val splitY = rect.top + rect.height() * variant(8, .38f, .62f)

        val facets = listOf(
            Path().apply { moveTo(rect.left,rect.top); lineTo(topX,rect.top); lineTo(leftMidX,splitY); close() } to variant(9,195f,250f),
            Path().apply { moveTo(topX,rect.top); lineTo(rect.right,rect.top); lineTo(rightMidX,splitY); close() } to variant(10,285f,345f),
            Path().apply { moveTo(rect.right,rect.bottom); lineTo(rightMidX,splitY); lineTo(bottomX,rect.bottom); close() } to variant(11,20f,75f),
            Path().apply { moveTo(rect.left,rect.bottom); lineTo(leftMidX,splitY); lineTo(bottomX,rect.bottom); close() } to variant(12,105f,165f),
            Path().apply { moveTo(leftMidX,splitY); lineTo(topX,rect.top); lineTo(rightMidX,splitY); lineTo(bottomX,rect.bottom); close() } to variant(13,245f,295f)
        )
        facets.forEachIndexed { i,(path,normal) -> drawFacet(canvas,path,normal,i==4) }

        if (seed % 2 == 0) {
            val extra = Path().apply { moveTo(rect.left,rect.top); lineTo(leftMidX,splitY); lineTo(rect.left,rect.bottom); close() }
            drawFacet(canvas,extra,variant(14,150f,220f))
        }
        if (seed % 3 == 0) {
            val extra = Path().apply { moveTo(rect.right,rect.top); lineTo(rightMidX,splitY); lineTo(rect.right,rect.bottom); close() }
            drawFacet(canvas,extra,variant(15,320f,380f))
        }

        val beamWidth = rect.width()*variant(16,.10f,.18f)
        val perpX=(-sin(rad)*beamWidth).toFloat(); val perpY=(cos(rad)*beamWidth).toFloat()
        val beamAlpha=if(pressed)120 else if(dark)180 else 205
        val beamColor=if(dark) Color.argb(beamAlpha,255,224,125) else Color.argb(beamAlpha,255,255,255)
        shinePaint.shader=LinearGradient(cx-perpX,cy-perpY,cx+perpX,cy+perpY,intArrayOf(Color.TRANSPARENT,beamColor,Color.TRANSPARENT),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,radius,radius,shinePaint); shinePaint.shader=null
        canvas.restoreToCount(save)

        strokePaint.strokeWidth=1.7f*density
        strokePaint.color=if(dark){if(pressed)Color.parseColor("#9C7424") else Color.parseColor("#F0C35A")}else{if(pressed)Color.parseColor("#8A6117") else Color.parseColor("#D5A63A")}
        val half=strokePaint.strokeWidth/2f; rect.inset(half,half); canvas.drawRoundRect(rect,radius,radius,strokePaint); rect.inset(-half,-half)
    }

    /**
     * Eclairage plus physique :
     * - un minimum d'ambiante reste toujours présent ;
     * - la lumière directe dépend de l'angle de la facette ;
     * - les faces opposées reçoivent un petit rebond au lieu de devenir mortes ;
     * - la réponse est adoucie pour éviter les bascules clair/sombre trop franches.
     */
    private fun drawFacet(canvas: Canvas, path: Path, normalAngle: Float, centreFacet: Boolean = false) {
        val direct = illumination(normalAngle)
        val facing = ((direct + 1f) * 0.5f).coerceIn(0f, 1f)
        val smoothFacing = facing * facing * (3f - 2f * facing)

        val oppositeNormal = normalize(normalAngle + 180f)
        val bounceFacing = ((illumination(oppositeNormal) + 1f) * 0.5f).coerceIn(0f, 1f)
        val bounce = bounceFacing * 0.18f

        val ambient = if (dark) 0.20f else 0.28f
        val directContribution = smoothFacing * if (pressed) 0.58f else 0.72f
        val totalLight = (ambient + directContribution + bounce).coerceIn(0f, 1f)

        facetPaint.style = Paint.Style.FILL
        if (dark) {
            // Même la facette dos à la source garde un léger bronze chaud.
            val r = (18 + 237 * totalLight).toInt().coerceIn(0, 255)
            val g = (17 + 195 * totalLight).toInt().coerceIn(0, 255)
            val b = (13 + 92 * totalLight).toInt().coerceIn(0, 255)
            val alphaBase = if (centreFacet) 72 else 92
            val alpha = (alphaBase + 120 * totalLight).toInt().coerceIn(70, 215)
            facetPaint.color = Color.argb(alpha, r, g, b)
        } else {
            // En clair, l'ombre reste beige/brun chaud plutôt que noire.
            val shadowR = 118
            val shadowG = 94
            val shadowB = 63
            val r = (shadowR + (255 - shadowR) * totalLight).toInt().coerceIn(0, 255)
            val g = (shadowG + (255 - shadowG) * totalLight).toInt().coerceIn(0, 255)
            val b = (shadowB + (255 - shadowB) * totalLight).toInt().coerceIn(0, 255)
            val alphaBase = if (centreFacet) 58 else 78
            val alpha = (alphaBase + 112 * totalLight).toInt().coerceIn(55, 200)
            facetPaint.color = Color.argb(alpha, r, g, b)
        }
        canvas.drawPath(path, facetPaint)
    }

    private fun illumination(normalAngle:Float):Float =
        cos(Math.toRadians(shortestDelta(normalAngle,lightAngle).toDouble())).toFloat().coerceIn(-1f,1f)

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun shortestDelta(a:Float,b:Float):Float=((b-a+540f)%360f)-180f
    override fun setAlpha(alpha:Int){fillPaint.alpha=alpha;strokePaint.alpha=alpha;facetPaint.alpha=alpha;shinePaint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:android.graphics.ColorFilter?){fillPaint.colorFilter=colorFilter;strokePaint.colorFilter=colorFilter;facetPaint.colorFilter=colorFilter;shinePaint.colorFilter=colorFilter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity():Int=android.graphics.PixelFormat.TRANSLUCENT
}
