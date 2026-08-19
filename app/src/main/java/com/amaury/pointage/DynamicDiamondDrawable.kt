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

/** Fond matériel dynamique pour les boutons HP Travail. */
class DynamicDiamondDrawable(
    private val dark: Boolean,
    private val density: Float,
    private val accent: Int = Color.parseColor("#D6A84B"),
    private val accentLight: Int = Color.parseColor("#F3D58A")
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var lightAngle = -55f
    private var pressed = false

    private val aluminumAccent = Color.parseColor("#7C858C")
    private val carbonAccent = Color.parseColor("#596166")
    private val diamondAccent = Color.parseColor("#8DC9E8")

    fun setLightAngle(degrees: Float) {
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.8f) return
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

        if (accent == diamondAccent) {
            drawDiamondCrystal(canvas)
            return
        }

        val radius = 18f * density
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(clip)

        when (accent) {
            aluminumAccent -> drawBrushedAluminum(canvas)
            carbonAccent -> drawCarbon(canvas)
            else -> drawElegantSurface(canvas)
        }

        canvas.restoreToCount(save)
        strokePaint.strokeWidth = 1.6f * density
        strokePaint.color = if (pressed) mix(accent, Color.BLACK, 0.22f) else accent
        val inset = strokePaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        rect.inset(-inset, -inset)
    }

    private fun drawDiamondCrystal(canvas: Canvas) {
        val cut = 15f * density
        val crystal = crystalPath(rect, cut)
        val save = canvas.save()
        canvas.clipPath(crystal)

        val deep = if (dark) Color.argb(210, 8, 24, 42) else Color.argb(145, 216, 241, 253)
        val mid = if (dark) Color.argb(170, 28, 66, 98) else Color.argb(120, 239, 250, 255)
        val clear = if (dark) Color.argb(100, 147, 215, 247) else Color.argb(90, 255, 255, 255)
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(deep, clear, mid, deep), floatArrayOf(0f, .27f, .63f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom
        val w = rect.width(); val h = rect.height(); val cx = rect.centerX(); val cy = rect.centerY()
        val facets = listOf(
            Path().apply { moveTo(l,t); lineTo(l+w*.22f,t); lineTo(l+w*.31f,cy); lineTo(l,b); close() } to 205f,
            Path().apply { moveTo(l+w*.22f,t); lineTo(cx,t+h*.10f); lineTo(l+w*.31f,cy); close() } to 260f,
            Path().apply { moveTo(cx,t+h*.10f); lineTo(r-w*.18f,t); lineTo(r-w*.28f,cy); lineTo(l+w*.31f,cy); close() } to 315f,
            Path().apply { moveTo(r-w*.18f,t); lineTo(r,t); lineTo(r,b); lineTo(r-w*.28f,cy); close() } to 350f,
            Path().apply { moveTo(l,b); lineTo(l+w*.31f,cy); lineTo(cx,b-h*.08f); lineTo(l+w*.20f,b); close() } to 145f,
            Path().apply { moveTo(l+w*.31f,cy); lineTo(r-w*.28f,cy); lineTo(cx,b-h*.08f); close() } to 80f,
            Path().apply { moveTo(r-w*.28f,cy); lineTo(r,b); lineTo(r-w*.20f,b); lineTo(cx,b-h*.08f); close() } to 25f
        )
        facets.forEach { (path, normal) ->
            val illumination = ((cos(Math.toRadians(shortestDelta(normal, lightAngle).toDouble())) + 1.0) / 2.0).toFloat()
            val alpha = ((if (dark) 26 else 18) + illumination * (if (pressed) 72 else 132)).toInt().coerceIn(14, 170)
            detailPaint.color = if (illumination > .48f) {
                withAlpha(mix(Color.WHITE, accentLight, .22f), alpha)
            } else {
                withAlpha(mix(Color.parseColor("#17334C"), accent, .20f), alpha)
            }
            canvas.drawPath(path, detailPaint)
        }

        val diagonal = sqrt(w*w + h*h)
        val rad = Math.toRadians(lightAngle.toDouble())
        val lx = cx + (cos(rad) * w * .34f).toFloat()
        val ly = cy + (sin(rad) * h * .34f).toFloat()

        paint.shader = RadialGradient(
            lx, ly, diagonal * .24f,
            intArrayOf(Color.argb(if (pressed) 100 else 215,255,255,255), Color.argb(82,151,220,255), Color.TRANSPARENT),
            floatArrayOf(0f,.20f,1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        val perpX = (-sin(rad) * w * .13f).toFloat()
        val perpY = (cos(rad) * h * .80f).toFloat()
        paint.shader = LinearGradient(
            lx-perpX, ly-perpY, lx+perpX, ly+perpY,
            intArrayOf(Color.TRANSPARENT, Color.argb(if (pressed) 80 else 180,255,255,255), Color.TRANSPARENT),
            floatArrayOf(0f,.5f,1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        detailPaint.color = Color.argb(if (pressed) 120 else 235,255,255,255)
        detailPaint.strokeWidth = 1.05f * density
        val ray = 5.2f * density
        canvas.drawLine(lx-ray, ly, lx+ray, ly, detailPaint)
        canvas.drawLine(lx, ly-ray, lx, ly+ray, detailPaint)
        canvas.restoreToCount(save)

        strokePaint.strokeWidth = 1.7f * density
        strokePaint.color = if (dark) Color.argb(238, 210, 241, 255) else Color.argb(245, 119, 190, 230)
        canvas.drawPath(crystal, strokePaint)

        val inset = 3f * density
        val inner = RectF(rect.left+inset, rect.top+inset, rect.right-inset, rect.bottom-inset)
        strokePaint.strokeWidth = .75f * density
        strokePaint.color = Color.argb(if (pressed) 90 else 175,255,255,255)
        canvas.drawPath(crystalPath(inner, (cut-inset).coerceAtLeast(4f*density)), strokePaint)
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

    private fun drawBrushedAluminum(canvas: Canvas) {
        val top = if (dark) Color.parseColor("#25292C") else Color.parseColor("#EEF0F1")
        val middle = if (dark) Color.parseColor("#3A3F43") else Color.parseColor("#C9CDD0")
        val bottom = if (dark) Color.parseColor("#1E2124") else Color.parseColor("#E0E3E5")
        paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom, intArrayOf(top, middle, bottom), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = null

        val step = maxOf(1f, 1.35f * density)
        var y = rect.top
        var i = 0
        while (y <= rect.bottom) {
            val alpha = when (i % 5) { 0 -> 34; 1 -> 15; 2 -> 25; 3 -> 9; else -> 20 }
            detailPaint.color = if (dark) Color.argb(alpha, 230, 235, 238) else Color.argb(alpha, 45, 50, 54)
            detailPaint.strokeWidth = if (i % 7 == 0) 0.8f * density else 0.45f * density
            canvas.drawLine(rect.left, y, rect.right, y, detailPaint)
            y += step
            i++
        }

        val rad = Math.toRadians(lightAngle.toDouble())
        val band = rect.width() * 0.28f
        val cx = rect.centerX(); val cy = rect.centerY()
        val px = (-sin(rad) * band).toFloat(); val py = (cos(rad) * band).toFloat()
        paint.shader = LinearGradient(cx-px, cy-py, cx+px, cy+py, intArrayOf(Color.TRANSPARENT, Color.argb(if (dark) 45 else 75,255,255,255), Color.TRANSPARENT), floatArrayOf(0f,.5f,1f), Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawCarbon(canvas: Canvas) {
        val base = if (dark) Color.parseColor("#090A0A") else Color.parseColor("#B9BCBD")
        val base2 = if (dark) Color.parseColor("#151717") else Color.parseColor("#D3D5D5")
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, base, base2, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = null

        val cell = maxOf(5f*density, 8f); val strip = cell*.48f
        var y = rect.top-cell; var row = 0
        while (y < rect.bottom+cell) {
            var x = rect.left-cell; var col = 0
            while (x < rect.right+cell) {
                val forward = (row+col)%2==0
                val path = Path()
                if (forward) {
                    path.moveTo(x,y+strip); path.lineTo(x+strip,y); path.lineTo(x+cell,y+cell-strip); path.lineTo(x+cell-strip,y+cell)
                } else {
                    path.moveTo(x,y); path.lineTo(x+strip,y+strip); path.lineTo(x+cell-strip,y+cell); path.lineTo(x+cell,y+cell-strip)
                }
                path.close()
                detailPaint.color = if (forward) Color.argb(if (dark) 38 else 28,210,215,215) else Color.argb(if (dark) 105 else 70,25,27,27)
                canvas.drawPath(path, detailPaint)
                x += cell; col++
            }
            y += cell; row++
        }
        paint.shader = LinearGradient(rect.left,rect.top,rect.right,rect.bottom,intArrayOf(Color.argb(if (dark)24 else 40,255,255,255),Color.TRANSPARENT,Color.argb(if (dark)45 else 30,0,0,0)),floatArrayOf(0f,.52f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(rect,paint); paint.shader=null
    }

    private fun drawElegantSurface(canvas: Canvas) {
        val base = if (dark) Color.parseColor("#111111") else Color.parseColor("#F2F0EA")
        val mid = mix(base, accent, if (dark) .24f else .10f)
        val light = mix(base, accentLight, if (dark) .32f else .16f)
        val diagonal = sqrt(rect.width()*rect.width()+rect.height()*rect.height())
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx=(cos(rad)*diagonal).toFloat(); val dy=(sin(rad)*diagonal).toFloat(); val cx=rect.centerX(); val cy=rect.centerY()
        paint.shader = LinearGradient(cx-dx,cy-dy,cx+dx,cy+dy,intArrayOf(if(pressed)mix(base,Color.BLACK,.12f) else base,light,mid),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(rect,paint); paint.shader=null
    }

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f,1f)
        return Color.rgb((Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0,255),Color.red(color),Color.green(color),Color.blue(color))
    private fun shortestDelta(a: Float, b: Float): Float = ((b-a+540f)%360f)-180f

    override fun setAlpha(alpha: Int) { paint.alpha=alpha; detailPaint.alpha=alpha; strokePaint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter=colorFilter; detailPaint.colorFilter=colorFilter; strokePaint.colorFilter=colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
